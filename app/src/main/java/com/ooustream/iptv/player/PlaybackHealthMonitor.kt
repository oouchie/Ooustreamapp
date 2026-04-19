package com.ooustream.iptv.player

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.ooustream.iptv.common.StreamDiagnosticLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Periodic health monitor for main player. Logs buffer health every 15s,
 * memory every 60s, and detects black screen (no new frames for 3s+).
 */
class PlaybackHealthMonitor(
    private val logger: StreamDiagnosticLogger,
    private val scope: CoroutineScope
) {
    private var monitorJob: Job? = null
    private var blackScreenJob: Job? = null
    private var player: ExoPlayer? = null
    var channelName: String = "unknown"
    var bandwidthMeter: DefaultBandwidthMeter? = null

    // Black screen detection
    private var lastRenderedFrameCount: Int = 0
    private var blackSince: Long = 0L
    private var blackScreenLogged: Boolean = false

    // Audio stall detection (catches silent dropouts that don't trip onPlayerError)
    private var lastAudioRenderedCount: Int = 0
    private var audioStalledSince: Long = 0L
    private var audioStallLogged: Boolean = false

    // FPS calculation
    private var prevFrameCount: Int = 0
    private var prevFrameTime: Long = 0L

    companion object {
        private const val HEALTH_INTERVAL_MS = 15_000L  // 15 seconds
        private const val BLACK_CHECK_INTERVAL_MS = 3_000L // 3 seconds
        private const val BLACK_SCREEN_THRESHOLD_MS = 3_000L // 3 seconds no frames
        private const val AUDIO_STALL_THRESHOLD_MS = 10_000L // 10 seconds of silence
        private const val MEMORY_INTERVAL_TICKS = 4 // every 4th health tick = 60s
    }

    fun start(exoPlayer: ExoPlayer) {
        stop()
        player = exoPlayer
        lastRenderedFrameCount = 0
        blackSince = 0L
        blackScreenLogged = false
        lastAudioRenderedCount = 0
        audioStalledSince = 0L
        audioStallLogged = false
        logger.logAppEvent("HEALTH_MONITOR", "action=START, channel=$channelName")

        monitorJob = scope.launch {
            var tick = 0
            while (isActive) {
                val p = player ?: break

                if (p.playbackState == Player.STATE_READY && p.playWhenReady) {
                    // Buffer health
                    val bufferMs = p.bufferedPosition - p.currentPosition
                    val counters = p.videoDecoderCounters

                    // Network bandwidth from DefaultBandwidthMeter
                    val bandwidthBps = try {
                        bandwidthMeter?.bitrateEstimate ?: 0L
                    } catch (_: Exception) { 0L }

                    // FPS: frames rendered since last check / elapsed time
                    val currentFrames = counters?.renderedOutputBufferCount ?: 0
                    val now = SystemClock.elapsedRealtime()
                    val fps = if (prevFrameTime > 0 && now > prevFrameTime) {
                        val frameDelta = currentFrames - prevFrameCount
                        val timeDelta = (now - prevFrameTime) / 1000f
                        if (timeDelta > 0) frameDelta / timeDelta else 0f
                    } else 0f
                    prevFrameCount = currentFrames
                    prevFrameTime = now

                    logger.logBufferHealth(
                        bufferMs = bufferMs,
                        bandwidthBps = bandwidthBps,
                        droppedFrames = counters?.droppedBufferCount ?: 0,
                        renderedFps = fps
                    )

                    // Memory every 60s
                    if (tick % MEMORY_INTERVAL_TICKS == 0) {
                        val runtime = Runtime.getRuntime()
                        val usedMb = ((runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024).toInt()
                        val totalMb = (runtime.totalMemory() / 1024 / 1024).toInt()
                        val freeMb = (runtime.freeMemory() / 1024 / 1024).toInt()
                        logger.logMemory(usedMb, totalMb, freeMb)
                    }

                    // Black screen detection
                    checkBlackScreen(p)

                    // Audio stall detection
                    checkAudioStall(p)

                    tick++
                }

                delay(HEALTH_INTERVAL_MS)
            }
        }

        // Separate coroutine for faster black screen detection (every 3s)
        blackScreenJob = scope.launch {
            while (isActive) {
                val p = player ?: break
                if (p.playbackState == Player.STATE_READY && p.playWhenReady) {
                    checkBlackScreen(p)
                }
                delay(BLACK_CHECK_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        if (monitorJob != null) {
            logger.logAppEvent("HEALTH_MONITOR", "action=STOP, channel=$channelName")
        }
        monitorJob?.cancel()
        monitorJob = null
        blackScreenJob?.cancel()
        blackScreenJob = null
        player = null
    }

    private fun checkBlackScreen(p: ExoPlayer) {
        val counters = p.videoDecoderCounters ?: return
        val currentFrames = counters.renderedOutputBufferCount

        if (currentFrames == lastRenderedFrameCount && currentFrames > 0) {
            // No new frames rendered
            if (blackSince == 0L) {
                blackSince = SystemClock.elapsedRealtime()
            }

            val blackDuration = SystemClock.elapsedRealtime() - blackSince

            if (blackDuration > BLACK_SCREEN_THRESHOLD_MS && !blackScreenLogged) {
                blackScreenLogged = true

                val hasVideoTrack = p.currentTracks.groups
                    .any { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 }
                val audioPlaying = (p.volume > 0f)
                val bufferMs = p.bufferedPosition - p.currentPosition

                logger.logBlackScreen(
                    channelName = channelName,
                    decoderInfo = getDecoderName(p),
                    surfaceValid = p.videoSize.width > 0,
                    hasVideoTrack = hasVideoTrack,
                    audioPlaying = audioPlaying,
                    bufferMs = bufferMs
                )
            }
        } else {
            // Frames are rendering — reset
            if (blackSince > 0L) {
                val duration = SystemClock.elapsedRealtime() - blackSince
                if (blackScreenLogged) {
                    logger.logBlackScreenRecovered(duration, channelName)
                }
            }
            blackSince = 0L
            blackScreenLogged = false
            lastRenderedFrameCount = currentFrames
        }
    }

    private fun checkAudioStall(p: ExoPlayer) {
        val counters = p.audioDecoderCounters ?: return

        // Only watch when audio is actually meant to be playing
        val hasSelectedAudio = p.currentTracks.groups.any { group ->
            group.type == C.TRACK_TYPE_AUDIO &&
                (0 until group.length).any { group.isTrackSelected(it) }
        }
        if (!hasSelectedAudio || p.volume <= 0f) {
            audioStalledSince = 0L
            audioStallLogged = false
            lastAudioRenderedCount = counters.renderedOutputBufferCount
            return
        }

        val currentAudio = counters.renderedOutputBufferCount
        if (currentAudio == lastAudioRenderedCount && currentAudio > 0) {
            if (audioStalledSince == 0L) {
                audioStalledSince = SystemClock.elapsedRealtime()
            }
            val stalledMs = SystemClock.elapsedRealtime() - audioStalledSince
            if (stalledMs > AUDIO_STALL_THRESHOLD_MS && !audioStallLogged) {
                audioStallLogged = true
                val fmt = p.audioFormat
                logger.logAppEvent("AUDIO_STALL",
                    "silent=${stalledMs}ms, mime=${fmt?.sampleMimeType}, " +
                    "ch=${fmt?.channelCount}, rate=${fmt?.sampleRate}, " +
                    "channel=$channelName")
            }
        } else {
            if (audioStallLogged) {
                val duration = SystemClock.elapsedRealtime() - audioStalledSince
                logger.logAppEvent("AUDIO_STALL_RECOVERED",
                    "was_silent=${duration}ms, channel=$channelName")
            }
            audioStalledSince = 0L
            audioStallLogged = false
            lastAudioRenderedCount = currentAudio
        }
    }

    private fun getDecoderName(p: ExoPlayer): String {
        return try {
            // DecoderCounters doesn't have decoderName — use track info instead
            val videoFormat = p.videoFormat
            videoFormat?.codecs ?: videoFormat?.sampleMimeType ?: "unknown"
        } catch (_: Exception) { "unknown" }
    }
}
