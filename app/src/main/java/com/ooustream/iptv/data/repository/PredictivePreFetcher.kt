package com.ooustream.iptv.data.repository

import android.content.Context
import coil.ImageLoader
import com.ooustream.iptv.common.NetworkMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pre-loads EPG data for the user's most-watched live channels when on WiFi.
 * All operations are non-fatal — failures are caught silently so they never
 * interfere with normal app behaviour.
 */
@Singleton
class PredictivePreFetcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val watchAnalyticsRepository: WatchAnalyticsRepository,
    private val epgCacheRepository: EpgCacheRepository,
    private val networkMonitor: NetworkMonitor,
    private val imageLoader: ImageLoader
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var hasPrefetched = false

    /**
     * Kick off pre-fetching if the user is on WiFi and we haven't already done
     * so this session.  Safe to call from any thread; the actual work runs on
     * [Dispatchers.IO].
     */
    fun prefetchIfNeeded() {
        if (hasPrefetched) return
        val state = networkMonitor.state.value
        if (!state.isConnected || !state.isWifi) return

        hasPrefetched = true
        scope.launch {
            try {
                val topChannels = watchAnalyticsRepository.getTopStreams("live", 20)
                topChannels.forEach { channel ->
                    // Pre-fetch EPG data for each top channel in parallel
                    launch {
                        try {
                            epgCacheRepository.getEpg(channel.streamId)
                        } catch (_: Exception) { }
                    }
                }
            } catch (_: Exception) { }
        }
    }

    /**
     * Reset the prefetch flag so the next call to [prefetchIfNeeded] will
     * run again.  Useful after a logout or session change.
     */
    fun reset() {
        hasPrefetched = false
    }
}
