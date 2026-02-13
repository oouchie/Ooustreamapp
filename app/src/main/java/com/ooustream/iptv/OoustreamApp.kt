package com.ooustream.iptv

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ooustream.iptv.common.DpadSoundManager
import com.ooustream.iptv.common.ProgressiveImageLoader
import com.ooustream.iptv.common.QualityPolicy
import com.ooustream.iptv.recommendation.ScoreRefreshWorker
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class OoustreamApp : Application(), Configuration.Provider {

    @Inject lateinit var dpadSoundManager: DpadSoundManager
    @Inject lateinit var qualityPolicy: QualityPolicy
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        dpadSoundManager.initialize()
        DpadSoundManager.setInstance(dpadSoundManager)
        ProgressiveImageLoader.setQualityPolicy(qualityPolicy)
        scheduleScoreRefresh()
    }

    private fun scheduleScoreRefresh() {
        val request = PeriodicWorkRequestBuilder<ScoreRefreshWorker>(6, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "score_refresh",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
