package com.ooustream.iptv.recommendation

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ooustream.iptv.data.local.dao.SeriesTrackingDao
import com.ooustream.iptv.data.repository.ContentRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Periodic WorkManager worker that checks tracked series for new episodes.
 * Scheduled every 6 hours by OoustreamApp, also triggered on app launch if >2h stale.
 */
@HiltWorker
class NewEpisodeSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val seriesTrackingDao: SeriesTrackingDao,
    private val contentRepository: ContentRepository,
    private val detector: NewEpisodeDetector
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val trackedSeries = seriesTrackingDao.getAllTrackedSeries()
            val now = System.currentTimeMillis()

            for (tracking in trackedSeries) {
                try {
                    val seriesInfo = contentRepository.getSeriesInfo(tracking.seriesId)
                    val result = detector.detect(tracking, seriesInfo) ?: continue

                    seriesTrackingDao.updateNewEpisodeStatus(
                        seriesId = tracking.seriesId,
                        hasNew = result.newEpisodeCount > 0,
                        count = result.newEpisodeCount,
                        season = result.latestSeason,
                        episode = result.latestEpisode,
                        syncedAt = now
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to sync series ${tracking.seriesId}: ${e.message}")
                }
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "NewEpisodeSync"
    }
}
