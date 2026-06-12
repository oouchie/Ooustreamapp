package com.ooustream.iptv.player

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
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

    /** Invoked on the first rendered video frame — the fragment uses it to crossfade out the art backdrop. */
    var onFirstFrame: (() -> Unit)? = null

    /** Invoked on a non-fatal audio sink fault — the fragment routes it into audio-stall recovery. */
    var onAudioSinkFault: (() -> Unit)? = null

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
        onFirstFrame?.invoke()
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

    // Audio renderer starved the AudioTrack — actual silent dropout.
    // Classic Reelz ad-break symptom when the elementary stream swaps mid-play.
    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long
    ) {
        logger.logAppEvent("AUDIO_UNDERRUN",
            "bufSize=${bufferSize}B, bufMs=$bufferSizeMs, " +
            "gap=${elapsedSinceLastFeedMs}ms, channel=$channelName")
    }

    // Mid-stream audio format change. Reelz-style channel-count / codec swaps trip this;
    // reuse=0 means decoder had to be fully re-initialized.
    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?
    ) {
        logger.logAppEvent("AUDIO_FORMAT_CHANGED",
            "mime=${format.sampleMimeType}, ch=${format.channelCount}, " +
            "rate=${format.sampleRate}, lang=${format.language ?: "und"}, " +
            "reuse=${decoderReuseEvaluation?.result ?: "null"}, channel=$channelName")
    }

    // Non-fatal audio sink error. Doesn't fire onPlayerError but user hears silence.
    override fun onAudioSinkError(
        eventTime: AnalyticsListener.EventTime,
        audioSinkError: Exception
    ) {
        logger.logAppEvent("AUDIO_SINK_ERROR",
            "type=${audioSinkError.javaClass.simpleName}, " +
            "msg=${audioSinkError.message}, channel=$channelName")
        // UnexpectedDiscontinuityException = sloppy source timestamps; DefaultAudioSink
        // self-recovers and playback continues. Triggering the recovery ladder on these
        // caused an audible re-init + a FULL mid-movie player rebuild on titles that merely
        // have imperfect muxing (customer report: Meet the Robinsons, dozens of ~200ms
        // discontinuities). Only escalate genuine sink failures (init/write).
        if (audioSinkError !is androidx.media3.exoplayer.audio.AudioSink.UnexpectedDiscontinuityException) {
            onAudioSinkFault?.invoke()
        }
    }

    // Audio renderer was disabled — usually from our own fallback path.
    // Without this log we can't tell disabled-by-code from disabled-by-decoder-crash.
    override fun onAudioDisabled(
        eventTime: AnalyticsListener.EventTime,
        decoderCounters: DecoderCounters
    ) {
        logger.logAppEvent("AUDIO_DISABLED", "channel=$channelName")
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

        // Log ALL audio tracks with selection state and language
        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val audioSummary = if (audioGroups.isEmpty()) {
            "none"
        } else {
            audioGroups.flatMap { group ->
                (0 until group.length).map { i ->
                    val fmt = group.getTrackFormat(i)
                    val sel = if (group.isTrackSelected(i)) "*" else ""
                    "$sel${fmt.sampleMimeType ?: "?"} ${fmt.language ?: "und"} ${fmt.channelCount}ch"
                }
            }.joinToString(" | ")
        }

        logger.logAppEvent("TRACKS_CHANGED",
            "video=${videoFormat?.sampleMimeType} ${videoFormat?.width}x${videoFormat?.height}, " +
            "audio=[$audioSummary]")
    }
}
