package com.ooustream.iptv.player

import android.content.Context
import android.os.CountDownTimer
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.load
import com.ooustream.iptv.R
import com.ooustream.iptv.common.ChannelDisplayHelper
import com.ooustream.iptv.data.model.EpgProgram
import com.ooustream.iptv.data.model.LiveStream
import com.ooustream.iptv.epg.ChannelNameParser
import com.ooustream.iptv.epg.InferredEpg
import com.ooustream.iptv.epg.bindEpgText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Top-of-screen banner that appears when a live channel starts playing.
 * Shows channel name, logo, current program (from EPG), next program,
 * and a thin progress bar for the current show.
 *
 * Auto-hides after [DISPLAY_DURATION_MS]. Re-triggered on every channel switch.
 */
class ChannelBannerOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val card: LinearLayout
    private val logo: ImageView
    private val initials: TextView
    private val channelName: TextView
    private val channelNum: TextView
    private val nowPlaying: TextView
    private val nextShow: TextView
    private val progressContainer: FrameLayout
    private val progressFill: View
    private var dismissTimer: CountDownTimer? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.overlay_channel_banner, this, true)
        card = findViewById(R.id.banner_card)
        logo = findViewById(R.id.banner_logo)
        initials = findViewById(R.id.banner_initials)
        channelName = findViewById(R.id.banner_channel_name)
        channelNum = findViewById(R.id.banner_channel_num)
        nowPlaying = findViewById(R.id.banner_now_playing)
        nextShow = findViewById(R.id.banner_next_show)
        progressContainer = findViewById(R.id.banner_progress_container)
        progressFill = findViewById(R.id.banner_progress_fill)
        visibility = GONE
    }

    /**
     * Show the banner for [channel] at position [channelIndex] (0-based).
     * Optionally provide [epgPrograms] for current/next show info.
     */
    fun show(channel: LiveStream, channelIndex: Int, epgPrograms: List<EpgProgram> = emptyList(), inferredEpg: InferredEpg? = null) {
        val parsed = ChannelNameParser.parseForDisplay(channel.name)
        channelName.text = parsed.name
        channelNum.text = "Ch ${channelIndex + 1}"

        // Channel logo with initials fallback
        ChannelDisplayHelper.loadLogo(logo, initials, channel.streamIcon, channel.name)

        // EPG: find current and next program
        val now = System.currentTimeMillis() / 1000
        val currentProgram = epgPrograms.find { program ->
            val start = program.startTimestamp?.toLongOrNull() ?: return@find false
            val end = program.stopTimestamp?.toLongOrNull() ?: return@find false
            now in start..end
        }
        val nextProgram = if (currentProgram != null) {
            val currentEnd = currentProgram.stopTimestamp?.toLongOrNull() ?: 0
            epgPrograms.find { program ->
                val start = program.startTimestamp?.toLongOrNull() ?: return@find false
                start >= currentEnd
            }
        } else null

        // Now playing text
        if (currentProgram?.title != null) {
            nowPlaying.text = currentProgram.title
            nowPlaying.visibility = VISIBLE
        } else if (inferredEpg != null) {
            bindEpgText(nowPlaying, inferredEpg)
            nowPlaying.visibility = VISIBLE
        } else {
            nowPlaying.visibility = GONE
        }

        // Next show
        if (nextProgram?.title != null) {
            val nextTime = formatTime(nextProgram.startTimestamp?.toLongOrNull())
            nextShow.text = if (nextTime != null) {
                "Next: ${nextProgram.title} at $nextTime"
            } else {
                "Next: ${nextProgram.title}"
            }
            nextShow.visibility = VISIBLE
        } else {
            nextShow.visibility = GONE
        }

        // Progress bar
        if (currentProgram != null) {
            val start = currentProgram.startTimestamp?.toLongOrNull() ?: 0
            val end = currentProgram.stopTimestamp?.toLongOrNull() ?: 0
            val totalDuration = end - start
            if (totalDuration > 0) {
                val elapsed = now - start
                val pct = (elapsed.toFloat() / totalDuration).coerceIn(0f, 1f)
                progressContainer.visibility = VISIBLE
                progressFill.post {
                    val params = progressFill.layoutParams
                    params.width = (progressContainer.width * pct).toInt()
                    progressFill.layoutParams = params
                }
            } else {
                progressContainer.visibility = GONE
            }
        } else {
            progressContainer.visibility = GONE
        }

        // Animate in
        visibility = VISIBLE
        card.translationY = -card.height.toFloat().coerceAtLeast(200f)
        card.alpha = 0f
        card.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(300)
            .start()

        // Auto-dismiss
        resetDismissTimer()
    }

    fun dismiss() {
        dismissTimer?.cancel()
        card.animate()
            .alpha(0f)
            .setDuration(500)
            .withEndAction { visibility = GONE }
            .start()
    }

    val isShowing: Boolean get() = visibility == VISIBLE

    private fun resetDismissTimer() {
        dismissTimer?.cancel()
        dismissTimer = object : CountDownTimer(DISPLAY_DURATION_MS, DISPLAY_DURATION_MS) {
            override fun onTick(millisUntilFinished: Long) { /* no-op */ }
            override fun onFinish() { dismiss() }
        }.start()
    }

    private fun formatTime(epochSeconds: Long?): String? {
        if (epochSeconds == null || epochSeconds <= 0) return null
        return try {
            val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
            sdf.format(Date(epochSeconds * 1000))
        } catch (_: Exception) { null }
    }

    companion object {
        private const val DISPLAY_DURATION_MS = 5000L
    }
}
