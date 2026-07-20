package com.ooustream.iptv.data.repository

import com.ooustream.iptv.common.StreamDiagnosticLogger
import com.ooustream.iptv.data.local.dao.SeriesTrackingDao
import com.ooustream.iptv.data.local.dao.WatchProgressDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drops watch-history rows whose content no longer exists in the provider's catalog.
 *
 * When a provider migrates a backend (as flarecoral did with VOD in July 2026) the stream ids are
 * renumbered, so every saved bookmark points at content that is simply gone. Those rows sit in
 * Continue Watching looking playable and fail the moment they are opened. This prunes them against
 * the live catalog the Home screen already fetches, so the row self-heals with no user action and
 * no extra network call.
 *
 * Two invariants that are easy to get wrong here:
 *
 *  1. **Never prune from an empty catalog.** A failed or partial fetch must be indistinguishable
 *     from "do nothing" — otherwise one bad network moment wipes the user's entire history.
 *  2. **Never send the catalog to SQLite.** The dead set is computed in Kotlin and only the (small)
 *     dead ids reach the DAO. A `NOT IN (:ids)` over ~8k catalog ids would blow SQLite's 999
 *     host-parameter ceiling on API 23.
 *
 * Series rows need care: `watch_progress.streamId` holds the EPISODE id, and the series id lives in
 * the separate `seriesId` column — so series are validated by `seriesId` against `get_series`, never
 * by `streamId`. A row with no `seriesId` and a non-"vod" type is left alone; we only delete what we
 * can positively prove is missing.
 */
@Singleton
class WatchHistoryPruner @Inject constructor(
    private val watchProgressDao: WatchProgressDao,
    private val seriesTrackingDao: SeriesTrackingDao,
    private val diagnosticLogger: StreamDiagnosticLogger
) {
    private val vodPruned = AtomicBoolean(false)
    private val seriesPruned = AtomicBoolean(false)

    /**
     * @param liveVodIds every VOD stream id currently in the catalog, UNFILTERED — pass the raw list,
     *   not the parental-filtered one, or blocked categories would look "missing" and be deleted.
     */
    suspend fun pruneVod(liveVodIds: Collection<Int>) = withContext(Dispatchers.Default) {
        if (liveVodIds.isEmpty()) return@withContext
        if (!vodPruned.compareAndSet(false, true)) return@withContext

        try {
            val live = liveVodIds.mapTo(HashSet()) { it.toString() }
            val dead = watchProgressDao.getAllOnce()
                .filter { it.seriesId == null && it.type.equals("vod", ignoreCase = true) }
                .map { it.streamId }
                .filter { it !in live }

            if (dead.isEmpty()) return@withContext
            dead.chunked(DELETE_CHUNK).forEach { watchProgressDao.deleteByStreamIds(it) }
            diagnosticLogger.logAppEvent(
                "WATCH_HISTORY_PRUNED",
                "type=vod removed=${dead.size} catalog=${live.size}"
            )
        } catch (c: CancellationException) {
            // Home navigated away mid-prune — let structured concurrency cancel cleanly, and re-arm
            // so the next Home load retries rather than skipping the prune for the session.
            vodPruned.set(false)
            throw c
        } catch (e: Exception) {
            // Data hygiene must never take the Home screen down with it.
            vodPruned.set(false)
            diagnosticLogger.logAppEvent("WATCH_HISTORY_PRUNE_FAILED", "type=vod error=${e.message}")
        }
    }

    /**
     * @param liveSeriesIds every series id currently in the catalog, UNFILTERED (see [pruneVod]).
     */
    suspend fun pruneSeries(liveSeriesIds: Collection<Int>) = withContext(Dispatchers.Default) {
        if (liveSeriesIds.isEmpty()) return@withContext
        if (!seriesPruned.compareAndSet(false, true)) return@withContext

        try {
            val live = liveSeriesIds.toHashSet()

            // Only the SERIES id is validated. If the provider also renumbered episodes within a
            // surviving series, those rows stay — proving that would need a get_series_info call per
            // series, and the URL rebuild at launch time already points them at the current server.
            val deadEpisodes = watchProgressDao.getAllOnce()
                .mapNotNull { row -> row.seriesId?.let { id -> row.streamId.takeIf { id !in live } } }
            deadEpisodes.chunked(DELETE_CHUNK).forEach { watchProgressDao.deleteByStreamIds(it) }

            val deadTracking = seriesTrackingDao.getAllTrackedSeries()
                .map { it.seriesId }
                .filter { it !in live }
            deadTracking.chunked(DELETE_CHUNK).forEach { seriesTrackingDao.deleteBySeriesIds(it) }

            if (deadEpisodes.isEmpty() && deadTracking.isEmpty()) return@withContext
            diagnosticLogger.logAppEvent(
                "WATCH_HISTORY_PRUNED",
                "type=series episodes=${deadEpisodes.size} tracked=${deadTracking.size} catalog=${live.size}"
            )
        } catch (c: CancellationException) {
            seriesPruned.set(false)
            throw c
        } catch (e: Exception) {
            seriesPruned.set(false)
            diagnosticLogger.logAppEvent("WATCH_HISTORY_PRUNE_FAILED", "type=series error=${e.message}")
        }
    }

    private companion object {
        /** Well under SQLite's 999-host-parameter ceiling on API 23. */
        const val DELETE_CHUNK = 400
    }
}
