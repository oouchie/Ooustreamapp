package com.ooustream.iptv.parental

import com.ooustream.iptv.common.StreamDiagnosticLogger
import com.ooustream.iptv.common.UhdContentDetector
import com.ooustream.iptv.data.UserPlanManager
import com.ooustream.iptv.data.local.dao.BlockedCategoryDao
import com.ooustream.iptv.data.local.entity.BlockedCategoryEntity
import com.ooustream.iptv.data.model.Category
import com.ooustream.iptv.data.repository.ContentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContentFilterManager @Inject constructor(
    private val blockedCategoryDao: BlockedCategoryDao,
    private val parentalControlManager: ParentalControlManager,
    private val userPlanManager: UserPlanManager,
    private val parentalRemapState: ParentalRemapState,
    private val contentRepository: ContentRepository,
    private val diagnosticLogger: StreamDiagnosticLogger
) {
    // Hide 4K categories on devices that can't handle them (900MB Fire TV Sticks)
    private val shouldHide4k: Boolean = !userPlanManager.isDeviceCapable()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // In-memory cache: keys are "section_categoryId" (e.g. "live_123")
    private val blockedKeys = ConcurrentHashMap.newKeySet<String>()

    // Same rows keyed by "section|lowercased category name". Ids are a provider's private
    // numbering and do not survive a panel change; names do. Used only while a section's
    // re-match is pending — see [ParentalRemapState].
    private val blockedNames = ConcurrentHashMap.newKeySet<String>()

    // Sections where the user has blocked at least one adult-looking category. Evidence that
    // adult blocking is intended, so it can be re-applied by name on the new catalogue without
    // inventing a preference the user never expressed.
    private val sectionsBlockingAdult = ConcurrentHashMap.newKeySet<String>()

    // Observable blocked state for reactive UI updates
    private val _blockedSnapshot = MutableStateFlow<Set<String>>(emptySet())
    val blockedSnapshot: StateFlow<Set<String>> = _blockedSnapshot.asStateFlow()

    val blockedCount: Flow<Int> = blockedCategoryDao.getCount()

    init {
        scope.launch {
            blockedCategoryDao.getAll().collect { entities ->
                val keys = entities.map { "${it.section}_${it.categoryId}" }.toSet()
                blockedKeys.clear()
                blockedKeys.addAll(keys)

                blockedNames.clear()
                entities.forEach { blockedNames.add(nameKey(it.section, it.categoryName)) }

                sectionsBlockingAdult.clear()
                entities.filter { AdultCategoryDetector.isAdultCategory(it.categoryName) }
                    .forEach { sectionsBlockingAdult.add(it.section) }

                _blockedSnapshot.value = keys
            }
        }
    }

    private fun nameKey(section: String, categoryName: String) =
        "$section|${categoryName.trim().lowercase()}"

    // --- Query methods ---

    fun isBlocked(section: String, categoryId: String?): Boolean {
        if (!shouldFilter()) return false
        return categoryId != null && blockedKeys.contains("${section}_${categoryId}")
    }

    /**
     * True when filtering is actually being applied right now (enabled and not temporarily
     * unlocked). Lets a caller fail CLOSED on an unknown categoryId — [filterContent] and
     * [isBlocked] both deliberately let a null category through, which is safe for catalog
     * data (the category is always known) but not for history rows, where it can be null.
     */
    val isFilteringActive: Boolean get() = shouldFilter()

    fun filterCategories(section: String, categories: List<Category>): List<Category> {
        var result = categories
        val remapPending = parentalRemapState.isPending(section)

        // Browsing a section is the natural retry point for a re-match that has not landed yet
        // (no network at startup, or not logged in then). Fire and forget — the filtering below
        // protects THIS pass regardless of when it completes.
        if (remapPending) {
            scope.launch { remapBlockedCategories(section) }
        }

        if (shouldFilter()) {
            result = result.filter { !blockedKeys.contains("${section}_${it.categoryId}") }

            if (remapPending) {
                // Ids have not been re-matched yet, so the check above may match nothing. Fall
                // back to the name, which survives a panel change, and re-assert adult blocking
                // for any section where the user had blocked adult content before the switch.
                val alsoBlockAdult = sectionsBlockingAdult.contains(section)
                result = result.filter { category ->
                    val isBlockedByName = blockedNames.contains(nameKey(section, category.categoryName))
                    val isAdult = alsoBlockAdult &&
                            AdultCategoryDetector.isAdultCategory(category.categoryName)
                    !isBlockedByName && !isAdult
                }
            }
        }
        if (shouldHide4k) {
            result = result.filter { !UhdContentDetector.is4kContent(it.categoryName) }
        }
        return result
    }

    fun <T> filterContent(section: String, items: List<T>, getCategoryId: (T) -> String?): List<T> {
        if (!shouldFilter()) return items
        return items.filter { item ->
            val catId = getCategoryId(item)
            catId == null || !blockedKeys.contains("${section}_${catId}")
        }
    }

    private fun shouldFilter(): Boolean {
        return parentalControlManager.isEnabled.value &&
                !parentalControlManager.isTemporarilyUnlocked()
    }

    // --- Provider cutover: re-match blocks onto the new catalogue ---

    private val remapInFlight = ConcurrentHashMap.newKeySet<String>()

    /** Re-matches every section still waiting on it. Used right after a provider cutover. */
    suspend fun remapPendingSections() {
        ParentalRemapState.SECTIONS.forEach { remapBlockedCategories(it) }
    }

    /**
     * Rebuilds this section's blocked rows against the current provider's catalogue, matching on
     * category NAME because ids do not survive a panel change.
     *
     * A block whose name is absent from the new catalogue is dropped — the category it referred
     * to no longer exists. Adult categories are re-blocked only when the user had already
     * blocked adult content in this section, so a new panel's extra adult categories are covered
     * without inventing a setting nobody chose.
     *
     * The category list is fetched here rather than accepted from the caller **on purpose**: a
     * partial list would look like "these categories no longer exist" and silently delete blocks.
     * Callers hand us filtered or favourites-only lists in other places, and this is child-safety
     * code — it must not depend on every future caller passing a complete list.
     *
     * Safe to call repeatedly and from anywhere: it no-ops unless the section is armed, and only
     * one pass per section runs at a time. On any failure the section stays armed, so the
     * name-based fallback keeps protecting it and the next browse retries.
     */
    suspend fun remapBlockedCategories(section: String) {
        if (!parentalRemapState.isPending(section)) return
        if (!remapInFlight.add(section)) return
        try {
            val existing = blockedCategoryDao.getListBySection(section)
            if (existing.isEmpty()) {
                parentalRemapState.clear(section)
                return
            }

            val categories = when (section) {
                "live" -> contentRepository.getLiveCategories()
                "vod" -> contentRepository.getVodCategories()
                "series" -> contentRepository.getSeriesCategories()
                else -> emptyList()
            }
            // An empty catalogue is indistinguishable from a failed fetch, and acting on it would
            // wipe every block in the section. Stay armed and try again later.
            if (categories.isEmpty()) return

            val byName = categories.associateBy { it.categoryName.trim().lowercase() }
            val blockedNamesInSection = existing.map { it.categoryName.trim().lowercase() }.toSet()
            val hadAdultBlocked = existing.any { AdultCategoryDetector.isAdultCategory(it.categoryName) }

            val rebuilt = LinkedHashMap<String, BlockedCategoryEntity>()
            existing.forEach { row ->
                val match = byName[row.categoryName.trim().lowercase()] ?: return@forEach
                rebuilt[match.categoryId] = BlockedCategoryEntity(
                    section = section,
                    categoryId = match.categoryId,
                    categoryName = match.categoryName,
                    blockedAt = row.blockedAt
                )
            }
            if (hadAdultBlocked) {
                categories.filter { AdultCategoryDetector.isAdultCategory(it.categoryName) }
                    .forEach { category ->
                        rebuilt.getOrPut(category.categoryId) {
                            BlockedCategoryEntity(
                                section = section,
                                categoryId = category.categoryId,
                                categoryName = category.categoryName
                            )
                        }
                    }
            }

            // A name the new catalogue no longer carries is intentionally not preserved: there is
            // no id to attach it to. Everything still present keeps its original blockedAt.
            val dropped = blockedNamesInSection.count { it !in byName.keys }

            blockedCategoryDao.deleteBySection(section)
            if (rebuilt.isNotEmpty()) blockedCategoryDao.insertAll(rebuilt.values.toList())
            parentalRemapState.clear(section)

            diagnosticLogger.logAppEvent(
                "PARENTAL_REMAP",
                "section=$section, was=${existing.size}, now=${rebuilt.size}, dropped=$dropped"
            )
        } catch (e: Exception) {
            // Leave the section armed so the name-based fallback keeps protecting it and the
            // next category load retries. Never clear on failure.
            diagnosticLogger.logAppEvent(
                "PARENTAL_REMAP",
                "section=$section FAILED: ${e.javaClass.simpleName}"
            )
        } finally {
            remapInFlight.remove(section)
        }
    }

    // --- Mutation methods ---

    suspend fun blockCategory(section: String, categoryId: String, categoryName: String) {
        blockedCategoryDao.insert(
            BlockedCategoryEntity(
                section = section,
                categoryId = categoryId,
                categoryName = categoryName
            )
        )
    }

    suspend fun unblockCategory(section: String, categoryId: String) {
        blockedCategoryDao.delete(section, categoryId)
    }

    suspend fun blockAll(entities: List<BlockedCategoryEntity>) {
        blockedCategoryDao.insertAll(entities)
    }

    suspend fun unblockAllInSection(section: String) {
        blockedCategoryDao.deleteBySection(section)
    }

    suspend fun unblockAll() {
        blockedCategoryDao.clearAll()
    }

    suspend fun getBlockedBySection(section: String): List<BlockedCategoryEntity> {
        return blockedCategoryDao.getListBySection(section)
    }

    fun getBlockedBySectionFlow(section: String): Flow<List<BlockedCategoryEntity>> {
        return blockedCategoryDao.getBySection(section)
    }

    fun getBlockedCountBySection(section: String): Flow<Int> {
        return blockedCategoryDao.getCountBySection(section)
    }
}
