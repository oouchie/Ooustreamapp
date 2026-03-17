package com.ooustream.iptv.parental

import com.ooustream.iptv.data.local.dao.BlockedCategoryDao
import com.ooustream.iptv.data.local.entity.BlockedCategoryEntity
import com.ooustream.iptv.data.model.Category
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
    private val parentalControlManager: ParentalControlManager
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // In-memory cache: keys are "section_categoryId" (e.g. "live_123")
    private val blockedKeys = ConcurrentHashMap.newKeySet<String>()

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
                _blockedSnapshot.value = keys
            }
        }
    }

    // --- Query methods ---

    fun isBlocked(section: String, categoryId: String?): Boolean {
        if (!shouldFilter()) return false
        return categoryId != null && blockedKeys.contains("${section}_${categoryId}")
    }

    fun filterCategories(section: String, categories: List<Category>): List<Category> {
        if (!shouldFilter()) return categories
        return categories.filter { !blockedKeys.contains("${section}_${it.categoryId}") }
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
