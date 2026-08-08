package com.ooustream.iptv

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.ooustream.iptv.common.CrashLogger
import com.ooustream.iptv.common.DeviceUtils
import com.ooustream.iptv.common.DpadSoundManager
import com.ooustream.iptv.common.NetworkMonitor
import com.ooustream.iptv.common.ProgressiveImageLoader
import com.ooustream.iptv.common.QualityPolicy
import com.ooustream.iptv.common.SessionIntegrityTracker
import com.ooustream.iptv.common.StreamDiagnosticLogger
import com.ooustream.iptv.recommendation.NewEpisodeSyncWorker
import com.ooustream.iptv.recommendation.ScoreRefreshWorker
import com.ooustream.iptv.recommendation.VodCastBackfillWorker
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class OoustreamApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject lateinit var dpadSoundManager: DpadSoundManager
    @Inject lateinit var qualityPolicy: QualityPolicy
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var streamDiagnosticLogger: StreamDiagnosticLogger
    @Inject lateinit var networkMonitor: NetworkMonitor

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)

        // Firebase Crashlytics: enable in release, disable in debug
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)

        dpadSoundManager.initialize()
        DpadSoundManager.setInstance(dpadSoundManager)
        ProgressiveImageLoader.setQualityPolicy(qualityPolicy)

        // Wire diagnostic logger into NetworkMonitor for network event logging
        networkMonitor.diagnosticLogger = streamDiagnosticLogger
        streamDiagnosticLogger.logAppEvent("APP_START",
            "version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        // Every TV-vs-phone branch in the app hangs off DeviceUtils.isTV(). A generic box that
        // misreports itself silently flips ALL of them to the touch paths, which strips D-pad
        // focus and strands the user (Ooustick/Allwinner, v4.2.0-v4.2.3). Log the raw signals so a
        // debug-log export identifies a misclassified device immediately, without a live adb session.
        streamDiagnosticLogger.logAppEvent("DEVICE_CLASS", DeviceUtils.describe(this))
        // CrashLogger only ever sees uncaught JVM exceptions. ANRs, native crashes and
        // low-memory kills — the three things that actually end this app's processes in the
        // field — leave it no trace at all, so a debug export can read "no crashes" while the
        // customer watches the app vanish. This records why the PREVIOUS process died.
        SessionIntegrityTracker.install(this, streamDiagnosticLogger)

        scheduleScoreRefresh()
        scheduleNewEpisodeSync()
        scheduleVodCastBackfill()
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

    /**
     * Gently backfill movie cast for actor search. Runs only when connected; each pass handles a
     * small batch and stops once the whole library is cached (see [VodCastBackfillWorker]).
     */
    private fun scheduleVodCastBackfill() {
        val request = PeriodicWorkRequestBuilder<VodCastBackfillWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "vod_cast_backfill",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
