package com.ooustream.iptv.data.repository

import com.ooustream.iptv.data.model.LiveStream
import com.ooustream.iptv.data.model.Series
import com.ooustream.iptv.data.model.VodStream
import com.ooustream.iptv.data.remote.VodListReader
import com.ooustream.iptv.parental.AdultCategoryDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One in-memory copy of the provider's full movie, series and live-channel lists.
 *
 * `get_vod_streams` is ~10 MB and `get_series` ~6.5 MB, and before this every consumer fetched its
 * own copy — Home's hero/trending, the Because-You-Watched rows, and the Movies / Series screens'
 * "Recently Added" each re-downloaded the catalogue. The Browse-by sidebar (genre / decade /
 * per-service counts) needs the whole list too, so it lives here once. Search reads all three
 * (v5.2.2): it used to download every list again on each query, which ran a 163 MB heap out.
 *
 * Held for [TTL_MS] and then dropped so a 1 GB stick gets the heap back; a request after expiry
 * re-fetches. Single-flight per list: concurrent callers share one download.
 *
 * Adult movies are NOT kept (2026-11-04): the provider's adult categories grew to 125k titles in one
 * evening, four times the rest of the catalogue, and holding them filled a 384MB heap (ANRs on the
 * Movies screens). Nothing that reads this list shows adult titles anyway — they are reachable only
 * inside their own category, which fetches that category on its own. The list is read one movie at
 * a time and an adult row is dropped on the spot; only its id is kept ([vodAdult]), which is what
 * [com.ooustream.iptv.parental.AdultContentGuard] needs.
 */
@Singleton
class CatalogCache @Inject constructor(
    private val contentRepository: ContentRepository
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val vodMutex = Mutex()
    private val seriesMutex = Mutex()
    private val liveMutex = Mutex()

    @Volatile private var vod: List<VodStream>? = null
    @Volatile private var vodAdult: AdultVod? = null
    @Volatile private var vodAt = 0L
    @Volatile private var series: List<Series>? = null
    @Volatile private var seriesAt = 0L
    @Volatile private var live: List<LiveStream>? = null
    @Volatile private var liveAt = 0L
    private var expiryJob: Job? = null

    private fun fresh(at: Long) = System.currentTimeMillis() - at < TTL_MS

    /** Adult movie categories (by name) and the sorted ids of every movie filed under them. */
    class AdultVod(val categories: Set<String>, val ids: IntArray)

    /** The whole movie list minus adult titles. */
    suspend fun vod(): List<VodStream> = loadVod().first

    /** What [vod] left out. Shares its download. */
    suspend fun vodAdult(): AdultVod = loadVod().second

    private suspend fun loadVod(): Pair<List<VodStream>, AdultVod> {
        cachedVod()?.let { return it }
        return vodMutex.withLock {
            cachedVod()?.let { return@withLock it }
            val adultCategories = AdultCategoryDetector
                .findAdultCategories(contentRepository.getVodCategories())
                .map { it.categoryId }
                .toSet()
            val kept = ArrayList<VodStream>(40_000)
            var adultIds = IntArray(1024)
            var adultCount = 0
            val slimmer = VodListReader.Slimmer()
            contentRepository.streamVodStreams { movie ->
                if (movie.categoryId in adultCategories) {
                    if (adultCount == adultIds.size) adultIds = adultIds.copyOf(adultIds.size * 2)
                    adultIds[adultCount++] = movie.streamId
                } else {
                    kept.add(slimmer.slim(movie))
                }
            }
            kept.trimToSize()
            val ids = adultIds.copyOf(adultCount).also { it.sort() }
            val adult = AdultVod(adultCategories, ids)
            vod = kept
            vodAdult = adult
            vodAt = System.currentTimeMillis()
            scheduleExpiry()
            kept to adult
        }
    }

    private fun cachedVod(): Pair<List<VodStream>, AdultVod>? {
        val v = vod ?: return null
        val a = vodAdult ?: return null
        return if (fresh(vodAt)) v to a else null
    }

    suspend fun series(): List<Series> {
        series?.takeIf { fresh(seriesAt) }?.let { return it }
        return seriesMutex.withLock {
            series?.takeIf { fresh(seriesAt) }?.let { return@withLock it }
            val fetched = contentRepository.getSeries()
            series = fetched
            seriesAt = System.currentTimeMillis()
            scheduleExpiry()
            fetched
        }
    }

    suspend fun live(): List<LiveStream> {
        live?.takeIf { fresh(liveAt) }?.let { return it }
        return liveMutex.withLock {
            live?.takeIf { fresh(liveAt) }?.let { return@withLock it }
            val fetched = contentRepository.getLiveStreams()
            live = fetched
            liveAt = System.currentTimeMillis()
            scheduleExpiry()
            fetched
        }
    }

    /** The series list if it is already in memory — never triggers a download. */
    fun peekSeries(): List<Series>? = series?.takeIf { fresh(seriesAt) }

    /**
     * TMDB id for a series, from the bulk list only (the panel's `get_series_info` carries none).
     * Null when the list isn't loaded or the series has no id.
     */
    fun seriesTmdbId(seriesId: Int): Int? =
        peekSeries()?.firstOrNull { it.seriesId == seriesId }?.tmdbId?.toIntOrNull()?.takeIf { it > 0 }

    /** Drop every list — "Update Playlist" and a provider change call this. */
    fun invalidate() {
        vod = null
        vodAdult = null
        series = null
        live = null
        vodAt = 0L
        seriesAt = 0L
        liveAt = 0L
    }

    private fun scheduleExpiry() {
        expiryJob?.cancel()
        expiryJob = scope.launch {
            delay(TTL_MS + 1_000)
            if (!fresh(vodAt)) {
                vod = null
                vodAdult = null
            }
            if (!fresh(seriesAt)) series = null
            if (!fresh(liveAt)) live = null
        }
    }

    private companion object {
        const val TTL_MS = 10 * 60 * 1000L
    }
}
