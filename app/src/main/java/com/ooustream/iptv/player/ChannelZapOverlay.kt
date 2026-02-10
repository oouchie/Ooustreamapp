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
import com.ooustream.iptv.data.model.LiveStream

/**
 * Translucent bottom overlay that shows adjacent channels during live TV playback.
 * Mimics the channel zapping experience of traditional cable TV.
 *
 * Press DPAD_UP/DOWN to open; while open, subsequent presses navigate channels.
 * The overlay auto-dismisses after [DISMISS_DELAY_MS] of inactivity.
 * Selecting a channel (DPAD_CENTER / Enter) triggers [onChannelSelected].
 */
class ChannelZapOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val container: LinearLayout
    private var dismissTimer: CountDownTimer? = null

    /** Index currently highlighted in the overlay (may differ from the playing channel). */
    private var highlightedIndex: Int = 0

    /** Callback invoked when the user confirms a channel selection. */
    var onChannelSelected: ((LiveStream) -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.overlay_channel_zap, this, true)
        container = findViewById(R.id.zap_container)
        visibility = GONE
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Show the overlay centred on [currentIndex] within [channels].
     * Displays up to 3 channels: previous, current, and next.
     */
    fun show(channels: List<LiveStream>, currentIndex: Int) {
        if (channels.isEmpty()) return

        highlightedIndex = currentIndex.coerceIn(0, channels.lastIndex)
        rebuildItems(channels, highlightedIndex)

        visibility = VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(ANIM_DURATION).start()
        resetDismissTimer()
    }

    /**
     * Move the highlighted channel by [direction] (-1 = up / previous, +1 = down / next).
     * Returns the new highlighted index so the caller can track it.
     */
    fun moveSelection(direction: Int, channels: List<LiveStream>): Int {
        if (channels.isEmpty()) return highlightedIndex

        val newIndex = (highlightedIndex + direction).coerceIn(0, channels.lastIndex)
        if (newIndex != highlightedIndex) {
            highlightedIndex = newIndex
            rebuildItems(channels, highlightedIndex)
        }
        resetDismissTimer()
        return highlightedIndex
    }

    /**
     * Confirm the currently highlighted channel.
     * Invokes [onChannelSelected] and dismisses the overlay.
     */
    fun confirmSelection(channels: List<LiveStream>) {
        if (channels.isEmpty()) return
        val channel = channels.getOrNull(highlightedIndex) ?: return
        onChannelSelected?.invoke(channel)
        dismiss()
    }

    /** Fade-out and hide the overlay. */
    fun dismiss() {
        dismissTimer?.cancel()
        animate().alpha(0f).setDuration(ANIM_DURATION).withEndAction {
            visibility = GONE
        }.start()
    }

    /** Whether the overlay is currently visible. */
    val isShowing: Boolean get() = visibility == VISIBLE

    /** The index the user is currently hovering over. */
    val currentHighlightedIndex: Int get() = highlightedIndex

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private fun rebuildItems(channels: List<LiveStream>, centreIndex: Int) {
        container.removeAllViews()

        val startIdx = (centreIndex - 1).coerceAtLeast(0)
        val endIdx = (centreIndex + 1).coerceAtMost(channels.lastIndex)

        for (i in startIdx..endIdx) {
            val channel = channels[i]
            val isCurrent = i == centreIndex
            val itemView = LayoutInflater.from(context)
                .inflate(R.layout.item_zap_channel, container, false)

            // Channel name
            itemView.findViewById<TextView>(R.id.zap_name).text = channel.name

            // Channel number (1-based for user display)
            itemView.findViewById<TextView>(R.id.zap_number).text = "${i + 1}"

            // EPG placeholder -- the model doesn't carry EPG text, leave blank
            itemView.findViewById<TextView>(R.id.zap_epg).visibility = View.GONE

            // Channel logo via Coil
            val logo = itemView.findViewById<ImageView>(R.id.zap_logo)
            val iconUrl = channel.streamIcon
            if (!iconUrl.isNullOrBlank()) {
                logo.load(iconUrl) { crossfade(200) }
            }

            // Highlight the currently selected channel
            if (isCurrent) {
                itemView.setBackgroundResource(R.drawable.bg_card_focused)
            }

            // Tapping / DPAD_CENTER on an item confirms that channel
            itemView.setOnClickListener {
                onChannelSelected?.invoke(channel)
                dismiss()
            }

            container.addView(itemView)
        }
    }

    private fun resetDismissTimer() {
        dismissTimer?.cancel()
        dismissTimer = object : CountDownTimer(DISMISS_DELAY_MS, DISMISS_DELAY_MS) {
            override fun onTick(millisUntilFinished: Long) { /* no-op */ }
            override fun onFinish() { dismiss() }
        }.start()
    }

    companion object {
        private const val ANIM_DURATION = 200L
        private const val DISMISS_DELAY_MS = 4000L
    }
}
