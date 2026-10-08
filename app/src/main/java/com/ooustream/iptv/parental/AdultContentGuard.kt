package com.ooustream.iptv.parental

import android.content.Context
import com.ooustream.iptv.common.StreamDiagnosticLogger
import com.ooustream.iptv.data.repository.ContentRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything the provider files under an adult category, so Home can keep it off screen.
 *
 * This is NOT parental controls. It is always on, whether or not a PIN is set: adult titles
 * never surface on Home (hero, trending, Top 10, genre rows, For You, Continue Watching,
 * Pick Up & New, For You — Live Now). They stay reachable by browsing their category.
 *
 * Home's history rows carry no category id, so the guard resolves the actual item ids: it
 * fetches the category lists, picks the adult ones by name ([AdultCategoryDetector]), then
 * fetches only those categories' contents. That is a handful of small requests rather than
 * the full catalogue.
 *
 * [ids] is null until the first successful resolve. Callers must treat null as "unknown" and
 * hold the row back (fail closed) — an adult poster flashing up for a second is the failure we
 * are preventing. The last good result is persisted so a cold start has it immediately.
 */
@Singleton
class AdultContentGuard @Inject constructor(
    @ApplicationContext context: Context,
    private val contentRepository: ContentRepository,
    private val diagnosticLogger: StreamDiagnosticLogger
) {
    data class AdultIds(
        val vodCategories: Set<String>,
        val seriesCategories: Set<String>,
        val liveCategories: Set<String>,
        val vod: Set<Int>,
        val series: Set<Int>,
        val live: Set<Int>
    ) {
        fun isAdultCategory(section: String, categoryId: String?): Boolean = when (section) {
            "vod" -> categoryId in vodCategories
            "series" -> categoryId in seriesCategories
            "live" -> categoryId in liveCategories
            else -> false
        }

        /** [type] is "vod" / "series" / "live"; [id] is a stream id, or a series id for "series". */
        fun isAdultItem(type: String, id: Int?): Boolean = when (type) {
            "vod" -> id in vod
            "series" -> id in series
            "live" -> id in live
            else -> false
        }
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private var lastResolvedAt = 0L

    private val _ids = MutableStateFlow(loadPersisted())
    val ids: StateFlow<AdultIds?> = _ids.asStateFlow()

    /**
     * Returns current ids, resolving first if they are unknown or stale. Returns the previous
     * value (possibly null) if the resolve fails — never a partial set.
     */
    suspend fun ensure(): AdultIds? {
        val current = _ids.value
        if (current != null && System.currentTimeMillis() - lastResolvedAt < TTL_MS) return current
        return mutex.withLock {
            val again = _ids.value
            if (again != null && System.currentTimeMillis() - lastResolvedAt < TTL_MS) return@withLock again
            try {
                val resolved = resolve()
                lastResolvedAt = System.currentTimeMillis()
                _ids.value = resolved
                persist(resolved)
                resolved
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                diagnosticLogger.logAppEvent("ADULT_GUARD", "resolve FAILED: ${e.javaClass.simpleName}")
                _ids.value
            }
        }
    }

    private suspend fun resolve(): AdultIds = coroutineScope {
        val vodCatsJob = async { contentRepository.getVodCategories() }
        val seriesCatsJob = async { contentRepository.getSeriesCategories() }
        val liveCatsJob = async { contentRepository.getLiveCategories() }

        val vodCats = AdultCategoryDetector.findAdultCategories(vodCatsJob.await()).map { it.categoryId }.toSet()
        val seriesCats = AdultCategoryDetector.findAdultCategories(seriesCatsJob.await()).map { it.categoryId }.toSet()
        val liveCats = AdultCategoryDetector.findAdultCategories(liveCatsJob.await()).map { it.categoryId }.toSet()

        // Any failure throws out of here, so a half-resolved set is never published.
        val gate = Semaphore(4)
        val vod = vodCats.map { id -> async { gate.withPermit { contentRepository.getVodStreams(id).map { it.streamId } } } }
        val series = seriesCats.map { id -> async { gate.withPermit { contentRepository.getSeries(id).map { it.seriesId } } } }
        val live = liveCats.map { id -> async { gate.withPermit { contentRepository.getLiveStreams(id).map { it.streamId } } } }

        val result = AdultIds(
            vodCategories = vodCats,
            seriesCategories = seriesCats,
            liveCategories = liveCats,
            vod = vod.awaitAll().flatten().toSet(),
            series = series.awaitAll().flatten().toSet(),
            live = live.awaitAll().flatten().toSet()
        )
        diagnosticLogger.logAppEvent(
            "ADULT_GUARD",
            "categories vod=${vodCats.size} series=${seriesCats.size} live=${liveCats.size}, " +
                "items vod=${result.vod.size} series=${result.series.size} live=${result.live.size}"
        )
        result
    }

    private fun persist(ids: AdultIds) {
        prefs.edit()
            .putStringSet(K_VOD_CATS, ids.vodCategories)
            .putStringSet(K_SERIES_CATS, ids.seriesCategories)
            .putStringSet(K_LIVE_CATS, ids.liveCategories)
            .putStringSet(K_VOD, ids.vod.mapTo(HashSet()) { it.toString() })
            .putStringSet(K_SERIES, ids.series.mapTo(HashSet()) { it.toString() })
            .putStringSet(K_LIVE, ids.live.mapTo(HashSet()) { it.toString() })
            .apply()
    }

    private fun loadPersisted(): AdultIds? {
        if (!prefs.contains(K_VOD)) return null
        fun strings(key: String) = prefs.getStringSet(key, null)?.toSet() ?: emptySet()
        fun ints(key: String) = strings(key).mapNotNull { it.toIntOrNull() }.toSet()
        return AdultIds(
            vodCategories = strings(K_VOD_CATS),
            seriesCategories = strings(K_SERIES_CATS),
            liveCategories = strings(K_LIVE_CATS),
            vod = ints(K_VOD),
            series = ints(K_SERIES),
            live = ints(K_LIVE)
        )
    }

    private companion object {
        const val PREFS = "adult_content_guard"
        const val TTL_MS = 15 * 60 * 1000L
        const val K_VOD_CATS = "vod_categories"
        const val K_SERIES_CATS = "series_categories"
        const val K_LIVE_CATS = "live_categories"
        const val K_VOD = "vod_ids"
        const val K_SERIES = "series_ids"
        const val K_LIVE = "live_ids"
    }
}
