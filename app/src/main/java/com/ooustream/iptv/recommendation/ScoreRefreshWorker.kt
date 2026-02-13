package com.ooustream.iptv.recommendation

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Periodic WorkManager worker that recomputes channel recommendation scores.
 * Scheduled every 6 hours by OoustreamApp.
 */
@HiltWorker
class ScoreRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: ChannelRecommendationEngine
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            engine.recomputeScores()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
