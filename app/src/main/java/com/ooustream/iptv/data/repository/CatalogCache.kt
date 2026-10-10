package com.ooustream.iptv.data.repository

import com.ooustream.iptv.data.model.Series
import com.ooustream.iptv.data.model.VodStream
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
 * One in-memory copy of the provider's full movie and series lists.
 *
 * `get_vod_streams` is ~10 MB and `get_series` ~6.5 MB, and before this every consumer fetched its
 * own copy — Home's hero/trending, the Because-You-Watched rows, and the Movies / Series screens'
 * "Recently Added" each re-downloaded the catalogue. The Browse-by sidebar (genre / decade /
 * per-service counts) needs the whole list too, so it lives here once.
 *
 * Held for [TTL_MS] and then dropped so a 1 GB stick gets the heap back; a request after expiry
 * re-fetches. Single-flight per list: concurrent callers share one download.
 */
@Singleton
class CatalogCache @Inject constructor(
    private val contentRepository: ContentRepository
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val vodMutex = Mutex()
    private val seriesMutex = Mutex()

    @Volatile private var vod: List<VodStream>? = null
    @Volatile private var vodAt = 0L
    @Volatile private var series: List<Series>? = null
    @Volatile private var seriesAt = 0L
    private var expiryJob: Job? = null

    private fun fresh(at: Long) = System.currentTimeMillis() - at < TTL_MS

    suspend fun vod(): List<VodStream> {
        vod?.takeIf { fresh(vodAt) }?.let { return it }
        return vodMutex.withLock {
            vod?.takeIf { fresh(vodAt) }?.let { return@withLock it }
            val fetched = contentRepository.getVodStreams()
            vod = fetched
            vodAt = System.currentTimeMillis()
            scheduleExpiry()
            fetched
        }
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

    /** The series list if it is already in memory — never triggers a download. */
    fun peekSeries(): List<Series>? = series?.takeIf { fresh(seriesAt) }

    /**
     * TMDB id for a series, from the bulk list only (the panel's `get_series_info` carries none).
     * Null when the list isn't loaded or the series has no id.
     */
    fun seriesTmdbId(seriesId: Int): Int? =
        peekSeries()?.firstOrNull { it.seriesId == seriesId }?.tmdbId?.toIntOrNull()?.takeIf { it > 0 }

    /** Drop both lists — "Update Playlist" and a provider change call this. */
    fun invalidate() {
        vod = null
        series = null
        vodAt = 0L
        seriesAt = 0L
    }

    private fun scheduleExpiry() {
        expiryJob?.cancel()
        expiryJob = scope.launch {
            delay(TTL_MS + 1_000)
            if (!fresh(vodAt)) vod = null
            if (!fresh(seriesAt)) series = null
        }
    }

    private companion object {
        const val TTL_MS = 10 * 60 * 1000L
    }
}
