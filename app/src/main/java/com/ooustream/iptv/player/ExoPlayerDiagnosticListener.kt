package com.ooustream.iptv.player

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.ooustream.iptv.common.StreamDiagnosticLogger

/**
 * Hooks into ExoPlayer's event system to log every meaningful playback event.
 * Registered on the main player in OoustreamPlaybackFragment.
 */
class ExoPlayerDiagnosticListener(
    private val logger: StreamDiagnosticLogger,
    var channelName: String = "unknown"
) : Player.Listener, AnalyticsListener {

    private var lastBufferingStart = 0L
    private var lastBandwidthLogTime = 0L

    companion object {
        private const val BANDWIDTH_LOG_INTERVAL_MS = 30_000L // 30 seconds
    }

    // ═══ Player State Changes ═══

    override fun onPlaybackStateChanged(state: Int) {
        val stateName = when (state) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> {
                lastBufferingStart = SystemClock.elapsedRealtime()
                "BUFFERING"
            }
            Player.STATE_READY -> {
                if (lastBufferingStart > 0) {
                    val bufferDuration = SystemClock.elapsedRealtime() - lastBufferingStart
                    logger.logAppEvent("BUFFER_COMPLETE",
                        "took=${bufferDuration}ms channel=$channelName")
                    lastBufferingStart = 0
                }
                "READY"
            }
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN($state)"
        }
        logger.logPlayerState(stateName, channelName)
    }

    // ═══ Errors ═══

    override fun onPlayerError(error: PlaybackException) {
        logger.logPlayerError(error, channelName)
        logger.logStreamError(error.errorCode, error.message ?: "unknown", channelName)
    }

    override fun onPlayerErrorChanged(error: PlaybackException?) {
        if (error == null) {
            logger.logAppEvent("ERROR_CLEARED", "channel=$channelName")
        }
    }

    // ═══ Video Rendering ═══

    override fun onRenderedFirstFrame() {
        logger.logAppEvent("FIRST_FRAME_RENDERED", "channel=$channelName")
    }

    override fun onDroppedVideoFrames(
        eventTime: AnalyticsListener.EventTime,
        count: Int,
        elapsedMs: Long
    ) {
        if (count > 3) {
            val dropsPerSecond = if (elapsedMs > 0) count * 1000f / elapsedMs else 0f
            logger.logAppEvent("FRAMES_DROPPED",
                "count=$count, elapsed=${elapsedMs}ms, " +
                "rate=${"%.1f".format(dropsPerSecond)}/sec, " +
                "channel=$channelName")
        }
    }

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long
    ) {
        val isHardware = !decoderName.contains("sw", ignoreCase = true) &&
                !decoderName.contains("c2.android", ignoreCase = true)
        logger.logDecoderInfo("video", isHardware, decoderName)
        logger.logAppEvent("DECODER_INIT",
            "decoder=$decoderName, hw=$isHardware, took=${initializationDurationMs}ms")
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long
    ) {
        val isHardware = !decoderName.contains("sw", ignoreCase = true)
        logger.logDecoderInfo("audio", isHardware, decoderName)
    }

    override fun onVideoSizeChanged(
        eventTime: AnalyticsListener.EventTime,
        videoSize: VideoSize
    ) {
        if (videoSize.width > 0 && videoSize.height > 0) {
            logger.logAppEvent("VIDEO_SIZE",
                "width=${videoSize.width}, height=${videoSize.height}, " +
                "ratio=${"%.2f".format(videoSize.pixelWidthHeightRatio)}")
        }
    }

    // ═══ Bandwidth ═══

    override fun onBandwidthEstimate(
        eventTime: AnalyticsListener.EventTime,
        totalLoadTimeMs: Int,
        totalBytesLoaded: Long,
        bitrateEstimate: Long
    ) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBandwidthLogTime >= BANDWIDTH_LOG_INTERVAL_MS) {
            lastBandwidthLogTime = now
            logger.logAppEvent("BANDWIDTH",
                "estimate=${bitrateEstimate / 1000}kbps, " +
                "loaded=${totalBytesLoaded / 1024}KB")
        }
    }

    // ═══ Track Changes ═══

    override fun onTracksChanged(tracks: Tracks) {
        val videoFormat = tracks.groups
            .firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 }
            ?.getTrackFormat(0)
        val audioFormat = tracks.groups
            .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }
            ?.getTrackFormat(0)

        logger.logAppEvent("TRACKS_CHANGED",
            "video=${videoFormat?.sampleMimeType} ${videoFormat?.width}x${videoFormat?.height}, " +
            "audio=${audioFormat?.sampleMimeType} ${audioFormat?.channelCount}ch")
    }
}
