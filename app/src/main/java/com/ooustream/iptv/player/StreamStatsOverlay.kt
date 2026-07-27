package com.ooustream.iptv.player

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.ooustream.iptv.R
import kotlinx.coroutines.*

/**
 * Translucent top-right overlay displaying real-time stream diagnostics:
 * resolution, bitrate, codecs, and buffer health.
 *
 * Toggle visibility with [toggle]. Attach an [ExoPlayer] instance via
 * [attachPlayer] before toggling so stats can be read from the active session.
 * Call [cleanup] in the host's onDestroyView to cancel the polling coroutine
 * and release the player reference.
 */
class StreamStatsOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val container: LinearLayout
    private val resolutionText: TextView
    private val bitrateText: TextView
    private val codecText: TextView
    private val audioText: TextView
    private val bufferText: TextView

    private var updateJob: Job? = null
    private var player: ExoPlayer? = null

    // Rebuffer counter — the single most useful number when diagnosing "it keeps buffering".
    private var stallCount = 0
    private var wasBuffering = false

    init {
        LayoutInflater.from(context).inflate(R.layout.overlay_stream_stats, this, true)
        container = findViewById(R.id.stats_container)
        resolutionText = findViewById(R.id.stats_resolution)
        bitrateText = findViewById(R.id.stats_bitrate)
        codecText = findViewById(R.id.stats_codec)
        audioText = findViewById(R.id.stats_audio)
        bufferText = findViewById(R.id.stats_buffer)
    }

    /**
     * Bind the player whose stats will be displayed.
     *
     * MUST be re-called after every player rebuild. Before v4.2.9 it was called once, on the
     * initial player, so any decoder rebuild left this polling a RELEASED player. Note a released
     * ExoPlayer does NOT throw here — Media3 pins bufferedPosition to currentPosition and nulls
     * videoFormat, so the overlay silently rendered "Buffer: 0.0s" and "Bitrate: --" forever, which
     * reads as a broken stream rather than a broken readout.
     */
    fun attachPlayer(exoPlayer: ExoPlayer) {
        player = exoPlayer
        stallCount = 0
        wasBuffering = false
        StreamThroughputMeter.reset()
    }

    /** Show or hide the stats overlay. */
    fun toggle() {
        if (container.visibility == VISIBLE) {
            hide()
        } else {
            show()
        }
    }

    val isShowing: Boolean get() = container.visibility == VISIBLE

    /** Release references and stop the polling loop. */
    fun cleanup() {
        stopUpdating()
        player = null
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private fun show() {
        container.visibility = VISIBLE
        container.alpha = 0f
        container.animate().alpha(1f).setDuration(ANIM_DURATION).start()
        startUpdating()
    }

    private fun hide() {
        container.animate().alpha(0f).setDuration(ANIM_DURATION).withEndAction {
            container.visibility = GONE
        }.start()
        stopUpdating()
    }

    private fun startUpdating() {
        updateJob?.cancel()
        updateJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                updateStats()
                delay(UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun stopUpdating() {
        updateJob?.cancel()
        updateJob = null
    }

    private fun updateStats() {
        // Defensive only. A released Media3 player does NOT throw from these getters — it pins
        // bufferedPosition to currentPosition and nulls videoFormat, which is precisely why the
        // stale-player bug was invisible. But an off-thread or mid-release call CAN throw, and the
        // polling coroutine must never die with it and freeze the readout on stale text.
        try {
            updateStatsInternal()
        } catch (t: Throwable) {
            resolutionText.text = "Player released"
            bitrateText.text = "Bitrate: --"
            bufferText.text = "Buffer: --"
        }
    }

    private fun updateStatsInternal() {
        val p = player ?: return

        // Rebuffer counting — a rising number here is the signature of a starving stream.
        val buffering = p.playbackState == Player.STATE_BUFFERING
        if (buffering && !wasBuffering) stallCount++
        wasBuffering = buffering

        // Resolution
        val videoFormat = p.videoFormat
        if (videoFormat != null) {
            val width = videoFormat.width
            val height = videoFormat.height
            val label = when {
                height >= 2160 -> "4K"
                height >= 1080 -> "1080p"
                height >= 720  -> "720p"
                height >= 480  -> "480p"
                else -> "${width}x${height}"
            }
            resolutionText.text = "$label (${width}x${height})"
        } else {
            resolutionText.text = "Loading..."
        }

        // Declared bitrate comes from the container's btrt/esds/stbl boxes — present for some MP4,
        // never for MKV (MatroskaExtractor sets neither averageBitrate nor peakBitrate), so it is
        // absent for most IPTV VOD. Measured arrival rate is the number that actually diagnoses a
        // stall, and it is only meaningful next to the buffer depth: 0 Mbps with a deep buffer is
        // normal (LoadControl stopped reading on purpose), 0 Mbps with an empty buffer is the bug.
        val declared = videoFormat?.bitrate ?: Format.NO_VALUE
        val measuredBps = StreamThroughputMeter.bitsPerSecond()
        val rateParts = mutableListOf<String>()
        if (declared > 0) rateParts.add("%.1f Mbps".format(declared / 1_000_000f))
        // Always show the measured rate, including 0 — "net 0.0 Mbps" beside an empty buffer IS the
        // diagnosis, so suppressing the zero would hide the failure this readout exists to reveal.
        rateParts.add("net %.1f Mbps".format(measuredBps / 1_000_000f))
        bitrateText.text = "Bitrate: ${rateParts.joinToString(" | ")}"

        // Codec
        val videoCodec = videoFormat?.codecs
            ?: videoFormat?.sampleMimeType
            ?: "Unknown"
        val audioCodec = p.audioFormat?.codecs
            ?: p.audioFormat?.sampleMimeType
            ?: "Unknown"
        codecText.text = "Video: $videoCodec | Audio: $audioCodec"

        // Audio
        val audioFormat = p.audioFormat
        if (audioFormat != null) {
            val parts = mutableListOf<String>()
            val audioBitrate = audioFormat.bitrate
            if (audioBitrate > 0) parts.add("${audioBitrate / 1000}kbps")
            val sampleRate = audioFormat.sampleRate
            if (sampleRate > 0) parts.add("${sampleRate}Hz")
            val channels = audioFormat.channelCount
            val channelLabel = when {
                channels <= 0 -> null
                channels == 1 -> "Mono"
                channels == 2 -> "Stereo"
                channels == 6 -> "5.1"
                channels == 8 -> "7.1"
                else -> "${channels}ch"
            }
            channelLabel?.let { parts.add(it) }
            audioText.text = "Audio: ${parts.joinToString(" | ")}"
        } else {
            audioText.text = "Audio: --"
        }

        // Buffer + rebuffer count. "Buffer sawtooths and stalls climb" is the whole diagnosis.
        val bufferedMs = (p.bufferedPosition - p.currentPosition).coerceAtLeast(0L)
        val bufferSec = bufferedMs / 1000f
        val stallSuffix = if (stallCount > 0) "  •  stalls: $stallCount" else ""
        val state = if (buffering) " (buffering)" else ""
        bufferText.text = "Buffer: %.1fs%s%s".format(bufferSec, state, stallSuffix)
    }

    companion object {
        private const val ANIM_DURATION = 200L
        // 1s: a buffer sawtooth swings several seconds between polls at 2s, which reads as noise.
        private const val UPDATE_INTERVAL_MS = 1000L
    }
}
