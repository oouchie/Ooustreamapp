package com.ooustream.iptv.player

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
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
    private val bufferText: TextView

    private var updateJob: Job? = null
    private var player: ExoPlayer? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.overlay_stream_stats, this, true)
        container = findViewById(R.id.stats_container)
        resolutionText = findViewById(R.id.stats_resolution)
        bitrateText = findViewById(R.id.stats_bitrate)
        codecText = findViewById(R.id.stats_codec)
        bufferText = findViewById(R.id.stats_buffer)
    }

    /** Bind the player whose stats will be displayed. */
    fun attachPlayer(exoPlayer: ExoPlayer) {
        player = exoPlayer
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
        val p = player ?: return

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

        // Bitrate
        val bitrate = videoFormat?.bitrate ?: 0
        if (bitrate > 0) {
            val mbps = bitrate / 1_000_000f
            bitrateText.text = "Bitrate: %.1f Mbps".format(mbps)
        } else {
            bitrateText.text = "Bitrate: --"
        }

        // Codec
        val videoCodec = videoFormat?.codecs
            ?: videoFormat?.sampleMimeType
            ?: "Unknown"
        val audioCodec = p.audioFormat?.codecs
            ?: p.audioFormat?.sampleMimeType
            ?: "Unknown"
        codecText.text = "Video: $videoCodec | Audio: $audioCodec"

        // Buffer
        val bufferedMs = p.bufferedPosition - p.currentPosition
        val bufferSec = bufferedMs / 1000f
        bufferText.text = "Buffer: %.1fs".format(bufferSec)
    }

    companion object {
        private const val ANIM_DURATION = 200L
        private const val UPDATE_INTERVAL_MS = 2000L
    }
}
