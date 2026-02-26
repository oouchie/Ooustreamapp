package com.ooustream.iptv.multiview

import android.animation.ObjectAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.ooustream.iptv.R

/**
 * Controller for MultiView bottom bar UI.
 * Manages audio slot buttons (dynamically created) and EPG ticker.
 */
class MultiViewBottomBarController(private val rootView: View) {

    // Audio buttons container (buttons are added dynamically)
    private val audioButtonsContainer: LinearLayout = rootView.findViewById(R.id.audio_buttons_container)

    // EPG ticker views
    private val tickerScroll: HorizontalScrollView = rootView.findViewById(R.id.ticker_scroll)
    private val tickerContent: LinearLayout = rootView.findViewById(R.id.ticker_content)

    // Active/inactive colors
    private val goldBgColor = 0xFFFFC107.toInt()
    private val goldTextColor = 0xFFFFD700.toInt()
    private val transparentColor = Color.TRANSPARENT
    private val dimWhiteColor = 0x66FFFFFF
    private val darkTextColor = 0xFF0A0A0A.toInt()
    private val whiteColor = 0xCCFFFFFF.toInt()

    // Audio buttons (created on demand)
    private val audioButtons = mutableListOf<TextView>()

    // Ticker scroll animation
    private var tickerAnimator: ObjectAnimator? = null

    /**
     * Initialize audio buttons for the given number of visible slots.
     * Creates clickable buttons numbered 1, 2, 3, 4.
     */
    fun setupAudioButtons(slotCount: Int, clickListener: (Int) -> Unit) {
        audioButtonsContainer.removeAllViews()
        audioButtons.clear()

        for (i in 0 until slotCount) {
            val button = TextView(rootView.context).apply {
                text = "${i + 1}"
                textSize = 11f
                setTextColor(dimWhiteColor)
                setPadding(
                    (14 * resources.displayMetrics.density).toInt(),
                    (6 * resources.displayMetrics.density).toInt(),
                    (14 * resources.displayMetrics.density).toInt(),
                    (6 * resources.displayMetrics.density).toInt()
                )
                setBackgroundColor(transparentColor)
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true

                setOnClickListener { clickListener(i) }
            }

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (i > 0) marginStart = (4 * rootView.resources.displayMetrics.density).toInt()
            }

            audioButtonsContainer.addView(button, params)
            audioButtons.add(button)
        }
    }

    /**
     * Update audio button styles to highlight the active audio slot.
     * Active button gets gold background + dark text, others get transparent + dim white text.
     */
    fun updateAudioSlot(slotIndex: Int) {
        audioButtons.forEachIndexed { index, btn ->
            if (index == slotIndex) {
                // Active state: gold background + dark text
                btn.setBackgroundColor(goldBgColor)
                btn.setTextColor(darkTextColor)
            } else {
                // Inactive state: transparent background + dim white text
                btn.setBackgroundColor(transparentColor)
                btn.setTextColor(dimWhiteColor)
            }
        }
    }

    /**
     * Set a click listener for all audio buttons.
     * Listener receives the slot index (0-based).
     */
    fun setAudioClickListener(listener: (Int) -> Unit) {
        audioButtons.forEachIndexed { index, btn ->
            btn.setOnClickListener { listener(index) }
        }
    }

    /**
     * Update EPG ticker with current slot data.
     * Shows "[slot#] channel_name — epg_title" for each active slot, separated by gold bullets.
     */
    fun updateTicker(slotData: List<TickerItem>) {
        tickerContent.removeAllViews()
        tickerAnimator?.cancel()

        if (slotData.isEmpty()) {
            addTickerText("No channels loaded", dimWhiteColor, false)
            return
        }

        slotData.forEachIndexed { index, item ->
            // Separator bullet between items
            if (index > 0) {
                addTickerText("  \u2022  ", goldTextColor, true)
            }

            // Slot number badge
            addTickerText("[${item.slotNumber}] ", goldTextColor, true)

            // Channel name
            addTickerText(item.channelName, whiteColor, true)

            // EPG title (if available)
            if (item.epgTitle.isNotBlank()) {
                addTickerText(" \u2014 ", dimWhiteColor, false)
                addTickerText(item.epgTitle, dimWhiteColor, false)
            }
        }

        // Start auto-scroll if content overflows
        tickerContent.post {
            val contentWidth = tickerContent.width
            val scrollWidth = tickerScroll.width
            if (contentWidth > scrollWidth) {
                startTickerScroll(contentWidth - scrollWidth)
            }
        }
    }

    private fun addTickerText(text: String, color: Int, bold: Boolean) {
        val tv = TextView(rootView.context).apply {
            this.text = text
            textSize = 10f
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }
        tickerContent.addView(tv)
    }

    private fun startTickerScroll(scrollDistance: Int) {
        tickerAnimator?.cancel()
        tickerScroll.scrollTo(0, 0)

        // Scroll right over duration proportional to content length, then reset
        val durationMs = (scrollDistance * 20L).coerceIn(3000L, 15000L)
        tickerAnimator = ObjectAnimator.ofInt(tickerScroll, "scrollX", 0, scrollDistance).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE // Smooth ping-pong instead of snap back
            startDelay = 2000 // Pause before first scroll
            start()
        }
    }

    fun stopTicker() {
        tickerAnimator?.cancel()
        tickerAnimator = null
    }

    data class TickerItem(
        val slotNumber: Int,
        val channelName: String,
        val epgTitle: String
    )
}
