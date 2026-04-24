package com.ooustream.iptv.multiview

import android.animation.ValueAnimator
import android.content.Context
import android.os.HandlerThread
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.ooustream.iptv.common.AudioLogger
import com.ooustream.iptv.common.AudioPipelineFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MultiViewPlayerManager(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val isLowMemory: Boolean = false
) {
    private val players = arrayOfNulls<ExoPlayer>(4)
    private val trackSelectors = arrayOfNulls<DefaultTrackSelector>(4)
    private val playbackThreads = arrayOfNulls<HandlerThread>(4)
    private val streamUrls = arrayOfNulls<String>(4)

    private val _audioSlot = MutableStateFlow(0)
    val audioSlot: StateFlow<Int> = _audioSlot

    private var focusedSlot = 0
    private var audioFadeAnimator: ValueAnimator? = null
    private var emergencyQuality = false

    /** Callback for player errors — wired in Fragment before stall detector starts */
    var onPlayerError: ((slotIndex: Int, error: PlaybackException) -> Unit)? = null

    /**
     * Creates an ExoPlayer instance for a slot and starts playback.
     * Each slot gets its own HandlerThread for decoder isolation.
     * Non-audio slots have audio tracks disabled (no FFmpeg decoder).
     * Non-focused slots capped to 480p. Low-memory caps all to 480p.
     */
    fun createPlayer(slotIndex: Int, streamUrl: String, playerView: PlayerView?) {
        // Release existing player and thread for this slot
        safeRelease(players[slotIndex])
        playbackThreads[slotIndex]?.quitSafely()

        // Store URL for recovery
        streamUrls[slotIndex] = streamUrl

        // Dedicated playback thread — prevents cascade failures between slots
        val thread = HandlerThread("MultiViewSlot-$slotIndex").apply { start() }
        playbackThreads[slotIndex] = thread

        val isFocused = slotIndex == focusedSlot
        val isAudio = slotIndex == _audioSlot.value

        // Track selector: resolution caps, audio/subtitle/metadata disabling, tunneling off
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioMimeTypes(
                        MimeTypes.AUDIO_AAC,
                        MimeTypes.AUDIO_E_AC3,
                        MimeTypes.AUDIO_AC3
                    )
                    .setPreferredAudioLanguage("en")
                    .setTunnelingEnabled(false) // Prevents Amlogic crash with concurrent decoders
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)     // No subtitles in multiview
                    .setTrackTypeDisabled(C.TRACK_TYPE_METADATA, true) // No metadata overhead
                    .apply {
                        // MultiView: cap ALL slots to stay within hardware decoder budget
                        // mt8696 does 300-450fps@720p but only 200-300fps@1080p
                        // 4 streams × 60fps = 240fps needed — 720p has 25-87% headroom
                        if (isFocused && !isLowMemory) {
                            setMaxVideoSize(1280, 720)   // focused: 720p max
                        } else {
                            setMaxVideoSize(640, 360)    // non-focused: 360p (small quadrant)
                        }
                        if (!isAudio) {
                            // Disable audio + text + metadata — preserve all disabled states
                            setDisabledTrackTypes(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT, C.TRACK_TYPE_METADATA))
                        }
                    }
            )
        }
        trackSelectors[slotIndex] = trackSelector

        // Buffer config — reduced on low-memory devices
        val loadControl = if (isLowMemory) {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(3_000, 8_000, 500, 1_000)
                .build()
        } else {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(3_000, 10_000, 800, 1_200)
                .build()
        }

        val dataSourceFactory = com.ooustream.iptv.player.StreamingDataFactories.buildDataSourceFactory(okHttpClient)

        // Renderers factory with stereo downmix + decoder fallback
        val renderersFactory = createRenderersFactory()

        val player = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setPlaybackLooper(thread.looper)
            .build()

        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            false // Don't handle audio focus per-player; we manage it ourselves
        )

        // Only audio slot gets volume
        player.volume = if (isAudio) 1f else 0f

        // Error listener — catches errors before stall detector monitoring starts
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                AudioLogger.log("MultiView slot $slotIndex: error: ${error.errorCodeName}")
                onPlayerError?.invoke(slotIndex, error)
            }
        })

        // Debug logging
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                AudioLogger.logDecoderInitialized(decoderName, initializationDurationMs)
            }
        })

        // Live offset: start 3s behind live edge for faster keyframe acquisition
        player.setMediaItem(buildLiveMediaItem(streamUrl))
        player.prepare()
        player.play()

        players[slotIndex] = player
        playerView?.player = player

        AudioLogger.log("MultiView slot $slotIndex: player created (focused=$isFocused, audio=$isAudio, lowMem=$isLowMemory, thread=${thread.name})")
    }

    /**
     * Switches audio to the target slot with a 200ms crossfade.
     * Disables audio tracks on non-target slots to free FFmpeg decoders.
     */
    fun setAudioSlot(targetSlot: Int) {
        val oldSlot = _audioSlot.value
        audioFadeAnimator?.cancel()

        // Disable audio on ALL non-target slots (frees FFmpeg decoder + AudioSink resources)
        for (i in 0 until 4) {
            if (i != targetSlot && players[i] != null) {
                players[i]?.volume = 0f
                trackSelectors[i]?.setParameters(
                    trackSelectors[i]!!.buildUponParameters()
                        .setDisabledTrackTypes(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT, C.TRACK_TYPE_METADATA))
                )
            }
        }

        // Enable audio on target slot (keep text/metadata disabled)
        trackSelectors[targetSlot]?.setParameters(
            trackSelectors[targetSlot]!!.buildUponParameters()
                .setDisabledTrackTypes(setOf(C.TRACK_TYPE_TEXT, C.TRACK_TYPE_METADATA))
        )

        if (oldSlot == targetSlot) {
            players[targetSlot]?.volume = 1f
            _audioSlot.value = targetSlot
            return
        }

        val newPlayer = players[targetSlot]

        // Fade in the new audio slot
        audioFadeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            addUpdateListener { anim ->
                newPlayer?.volume = anim.animatedValue as Float
            }
            start()
        }

        _audioSlot.value = targetSlot
        AudioLogger.log("MultiView audio: slot $oldSlot → slot $targetSlot (audio tracks toggled)")
    }

    /**
     * Updates resolution caps when focus changes between slots.
     * Focused slot gets full resolution; others capped to 480p.
     */
    fun setFocusedSlot(slotIndex: Int) {
        val oldFocused = focusedSlot
        focusedSlot = slotIndex

        // Cap old focused slot to 360p (or 320p in emergency mode)
        trackSelectors[oldFocused]?.let { ts ->
            ts.setParameters(
                ts.buildUponParameters()
                    .setMaxVideoSize(
                        if (emergencyQuality) 480 else 640,
                        if (emergencyQuality) 320 else 360
                    )
            )
        }

        // Cap new focused slot to 720p (not uncapped — decoder budget)
        if (!isLowMemory) {
            trackSelectors[slotIndex]?.let { ts ->
                ts.setParameters(
                    ts.buildUponParameters()
                        .setMaxVideoSize(1280, 720)
                )
            }
        }
    }

    /**
     * Returns the ExoPlayer instance for a slot, or null if no player.
     */
    fun getPlayer(slotIndex: Int): ExoPlayer? = players[slotIndex]

    /**
     * Returns the stored stream URL for a slot, or null.
     */
    fun getStreamUrl(slotIndex: Int): String? = streamUrls[slotIndex]

    /**
     * Manages resolution caps for fullscreen mode.
     * When slot is non-null: fullscreen slot gets full resolution, others cap to 240p.
     * When null: restore normal caps (focused=full, others=480p).
     */
    fun setFullscreenMode(slot: Int?) {
        if (slot != null) {
            for (i in 0 until 4) {
                trackSelectors[i]?.let { ts ->
                    ts.setParameters(
                        ts.buildUponParameters()
                            .setMaxVideoSize(
                                if (i == slot) 1280 else 426,
                                if (i == slot) 720 else 240
                            )
                    )
                }
            }
        } else {
            // Restore normal: focused = 720p, others = 360p
            for (i in 0 until 4) {
                trackSelectors[i]?.let { ts ->
                    ts.setParameters(
                        ts.buildUponParameters()
                            .setMaxVideoSize(
                                if (i == focusedSlot && !isLowMemory) 1280 else 640,
                                if (i == focusedSlot && !isLowMemory) 720 else 360
                            )
                    )
                }
            }
        }
    }

    /**
     * Swaps player and track selector references between two slots.
     * Rebinds PlayerViews without recreating players (avoids rebuffering).
     */
    fun swapSlots(
        slotA: Int, slotB: Int,
        playerViewA: PlayerView?, playerViewB: PlayerView?
    ) {
        // Swap player references
        val tempPlayer = players[slotA]
        players[slotA] = players[slotB]
        players[slotB] = tempPlayer

        // Swap track selectors
        val tempTrackSelector = trackSelectors[slotA]
        trackSelectors[slotA] = trackSelectors[slotB]
        trackSelectors[slotB] = tempTrackSelector

        // Swap playback threads
        val tempThread = playbackThreads[slotA]
        playbackThreads[slotA] = playbackThreads[slotB]
        playbackThreads[slotB] = tempThread

        // Swap stream URLs
        val tempUrl = streamUrls[slotA]
        streamUrls[slotA] = streamUrls[slotB]
        streamUrls[slotB] = tempUrl

        // Rebind PlayerViews
        playerViewA?.player = players[slotA]
        playerViewB?.player = players[slotB]

        // Update audio slot to follow content
        val audio = _audioSlot.value
        if (audio == slotA) {
            setAudioSlot(slotB)
        } else if (audio == slotB) {
            setAudioSlot(slotA)
        }

        AudioLogger.log("MultiView: swapped slot $slotA <-> slot $slotB")
    }

    // ── Recovery Methods ──────────────────────────────────────────────

    /**
     * Soft reset: seek to live edge to resync decoder. ~100ms, invisible to user.
     * Clears accumulated decoder buffer debt without rebuilding player.
     */
    fun softReset(slotIndex: Int) {
        val player = players[slotIndex] ?: return
        AudioLogger.log("MultiView slot $slotIndex: SOFT RESET (seek to live edge)")
        if (player.isCurrentMediaItemLive) {
            player.seekToDefaultPosition()
        } else {
            player.seekTo(player.currentPosition)
        }
    }

    /**
     * Hard reset: stop → clearMediaItems → setMediaSource → prepare → play.
     * This is exactly what clicking the channel does manually.
     * Uses stored stream URL. Player instance and thread are preserved.
     */
    fun hardReset(slotIndex: Int, playerView: PlayerView?) {
        val player = players[slotIndex] ?: return
        val url = streamUrls[slotIndex] ?: return
        AudioLogger.log("MultiView slot $slotIndex: HARD RESET (stop/prepare/play)")

        player.stop()
        player.clearMediaItems()
        player.setMediaItem(buildLiveMediaItem(url))
        player.prepare()
        player.play()
    }

    /**
     * Nuclear reset: release player + quit thread → rebuild from scratch.
     * Last resort when hard reset fails. Creates fresh decoder pipeline.
     */
    fun nuclearReset(slotIndex: Int, playerView: PlayerView?) {
        val url = streamUrls[slotIndex] ?: return
        AudioLogger.log("MultiView slot $slotIndex: NUCLEAR RESET (full rebuild)")

        // Release old player and thread
        safeRelease(players[slotIndex])
        players[slotIndex] = null
        trackSelectors[slotIndex] = null
        playbackThreads[slotIndex]?.quitSafely()
        playbackThreads[slotIndex] = null

        // Rebuild from scratch (createPlayer handles new thread creation)
        createPlayer(slotIndex, url, playerView)
    }

    /**
     * Emergency quality reduction when chop is detected on any slot.
     * Drops ALL non-focused slots to 480x320 / 1.5Mbps to reduce decoder load.
     * Focused slot stays at full resolution.
     */
    fun setEmergencyQuality(enable: Boolean) {
        if (emergencyQuality == enable) return
        emergencyQuality = enable

        for (i in 0 until 4) {
            if (i == focusedSlot) continue // Never degrade focused slot
            trackSelectors[i]?.let { ts ->
                ts.setParameters(
                    ts.buildUponParameters()
                        .setMaxVideoSize(
                            if (enable) 426 else 640,
                            if (enable) 240 else 360
                        )
                        .setMaxVideoBitrate(
                            if (enable) 1_000_000 else Int.MAX_VALUE
                        )
                )
            }
        }
        AudioLogger.log("MultiView: emergency quality ${if (enable) "ON (240p)" else "OFF (360p)"}")
    }

    /**
     * Creates a player with reduced buffers for recovery attempts.
     * Smaller buffers reduce time-to-first-frame at the cost of less buffer headroom.
     */
    fun createPlayerReduced(slotIndex: Int, streamUrl: String, playerView: PlayerView?) {
        safeRelease(players[slotIndex])
        playbackThreads[slotIndex]?.quitSafely()

        // Store URL for recovery
        streamUrls[slotIndex] = streamUrl

        // Dedicated playback thread
        val thread = HandlerThread("MultiViewSlot-$slotIndex").apply { start() }
        playbackThreads[slotIndex] = thread

        val isAudio = slotIndex == _audioSlot.value
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioMimeTypes(
                        MimeTypes.AUDIO_AAC,
                        MimeTypes.AUDIO_E_AC3,
                        MimeTypes.AUDIO_AC3
                    )
                    .setPreferredAudioLanguage("en")
                    .setMaxVideoSize(854, 480)
                    .setTunnelingEnabled(false)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .setTrackTypeDisabled(C.TRACK_TYPE_METADATA, true)
                    .apply {
                        if (!isAudio) {
                            setDisabledTrackTypes(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT, C.TRACK_TYPE_METADATA))
                        }
                    }
            )
        }
        trackSelectors[slotIndex] = trackSelector

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(2_000, 5_000, 500, 800)
            .build()

        val dataSourceFactory = com.ooustream.iptv.player.StreamingDataFactories.buildDataSourceFactory(okHttpClient)
        val renderersFactory = createRenderersFactory()

        val player = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setPlaybackLooper(thread.looper)
            .build()

        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            false
        )

        // Error listener
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                AudioLogger.log("MultiView slot $slotIndex: reduced error: ${error.errorCodeName}")
                onPlayerError?.invoke(slotIndex, error)
            }
        })

        player.volume = if (slotIndex == _audioSlot.value) 1f else 0f
        player.setMediaItem(buildLiveMediaItem(streamUrl))
        player.prepare()
        player.play()

        players[slotIndex] = player
        playerView?.player = player

        AudioLogger.log("MultiView slot $slotIndex: reduced-buffer player created (thread=${thread.name})")
    }

    /**
     * Releases a single slot's player and its playback thread.
     */
    fun releaseSlot(slotIndex: Int) {
        safeRelease(players[slotIndex])
        players[slotIndex] = null
        trackSelectors[slotIndex] = null
        streamUrls[slotIndex] = null
        playbackThreads[slotIndex]?.quitSafely()
        playbackThreads[slotIndex] = null
    }

    /**
     * Releases all players and threads. Call in fragment onDestroyView.
     */
    fun releaseAll() {
        audioFadeAnimator?.cancel()
        for (i in 0 until 4) {
            safeRelease(players[i])
            players[i] = null
            trackSelectors[i] = null
            streamUrls[i] = null
            playbackThreads[i]?.quitSafely()
            playbackThreads[i] = null
        }
        AudioLogger.log("MultiView: all players released")
    }

    /**
     * Returns memory usage estimate across all active players.
     */
    fun getActivePlayerCount(): Int = players.count { it != null }

    // ── Private Helpers ──────────────────────────────────────────────

    private fun safeRelease(player: ExoPlayer?) {
        try {
            player?.stop()
            player?.clearVideoSurface()
            player?.release()
        } catch (_: Exception) {}
    }

    private fun buildLiveMediaItem(streamUrl: String): MediaItem {
        return MediaItem.Builder()
            .setUri(streamUrl)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(3_000)
                    .setMinPlaybackSpeed(0.97f)
                    .setMaxPlaybackSpeed(1.03f)
                    .build()
            )
            .build()
    }

    private fun createRenderersFactory(): DefaultRenderersFactory {
        return AudioPipelineFactory.createRenderersFactory(context)
    }
}
