package com.ooustream.iptv

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.ooustream.iptv.common.CrashLogger
import com.ooustream.iptv.common.DpadSoundManager
import com.ooustream.iptv.common.ProgressiveImageLoader
import com.ooustream.iptv.common.QualityPolicy
import com.ooustream.iptv.recommendation.NewEpisodeSyncWorker
import com.ooustream.iptv.recommendation.ScoreRefreshWorker
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class OoustreamApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject lateinit var dpadSoundManager: DpadSoundManager
    @Inject lateinit var qualityPolicy: QualityPolicy
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        dpadSoundManager.initialize()
        DpadSoundManager.setInstance(dpadSoundManager)
        ProgressiveImageLoader.setQualityPolicy(qualityPolicy)
        scheduleScoreRefresh()
        scheduleNewEpisodeSync()
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15) // 15% of available RAM (conservative for 1GB Fire Sticks)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("poster_cache"))
                    .maxSizeBytes(100L * 1024 * 1024) // 100MB disk cache
                    .build()
            }
            .crossfade(200)
            .respectCacheHeaders(false) // TMDB images are permanent — same URL = same image
            .build()
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

    private fun scheduleNewEpisodeSync() {
        val request = PeriodicWorkRequestBuilder<NewEpisodeSyncWorker>(6, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "new_episode_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
