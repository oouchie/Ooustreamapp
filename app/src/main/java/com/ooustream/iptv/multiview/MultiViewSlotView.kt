package com.ooustream.iptv.multiview

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.graphics.PixelFormat
import android.view.SurfaceView
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.ui.PlayerView
import com.ooustream.iptv.R
import com.ooustream.iptv.data.model.LiveStream

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MultiViewSlotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    var slotIndex: Int = 0
        set(value) {
            field = value
            updateSlotNumber()
        }

    private val playerView: PlayerView
    private val channelBadge: LinearLayout
    private val channelNameText: TextView
    private val liveDot: View
    private val liveText: TextView
    private val audioIndicator: LinearLayout
    private val slotNumberBadge: TextView
    private val emptyIcon: ImageView
    private val emptyText: TextView
    private val bottomScrim: View
    private val signalLostOverlay: LinearLayout
    private val healthDot: View
    private val recoveryFadeMask: View
    private val recoverySpinner: ProgressBar

    // Pulsing animation for LIVE dot
    private var livePulseAnimator: ObjectAnimator? = null

    init {
        // This view is a rendering container only — focus lives on the parent FrameLayout
        isFocusable = false
        isFocusableInTouchMode = false

        setBackgroundResource(R.drawable.bg_multiview_slot)
        // No clipToOutline — SurfaceView ignores view clipping and background
        // resource swaps on focus would trigger outline recomputation + glitches

        // Video surface
        playerView = PlayerView(context).apply {
            useController = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            (videoSurfaceView as? SurfaceView)?.let { sv ->
                sv.setZOrderMediaOverlay(false)
                // OPAQUE format prevents transparent bleed-through when no video content
                // is rendering — surface shows black instead of punching through to window
                sv.holder.setFormat(PixelFormat.OPAQUE)
            }
        }
        addView(playerView)

        // Bottom gradient scrim for text readability
        bottomScrim = View(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0).also {
                it.gravity = Gravity.BOTTOM
                it.height = (resources.displayMetrics.heightPixels * 0.3f).toInt()
            }
            setBackgroundResource(R.drawable.bg_multiview_scrim_bottom)
            visibility = GONE
        }
        addView(bottomScrim)

        // Channel badge (top-left)
        channelBadge = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xB3000000.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
                it.gravity = Gravity.TOP or Gravity.START
                it.setMargins(dp(6), dp(6), 0, 0)
            }
            visibility = GONE
        }

        channelNameText = TextView(context).apply {
            textSize = 9f
            setTextColor(Color.WHITE)
            maxLines = 1
        }
        channelBadge.addView(channelNameText)

        // LIVE dot
        liveDot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(5), dp(5)).also {
                it.setMargins(dp(6), 0, dp(3), 0)
            }
            setBackgroundResource(R.drawable.bg_live_dot)
        }
        channelBadge.addView(liveDot)

        liveText = TextView(context).apply {
            text = "LIVE"
            textSize = 8f
            setTextColor(Color.WHITE)
            letterSpacing = 0.06f
        }
        channelBadge.addView(liveText)
        addView(channelBadge)

        // Audio indicator (top-right)
        audioIndicator = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x26FFD700.toInt())
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
                it.gravity = Gravity.TOP or Gravity.END
                it.setMargins(0, dp(6), dp(6), 0)
            }
            visibility = GONE
        }

        val audioText = TextView(context).apply {
            text = "AUDIO"
            textSize = 9f
            setTextColor(0xFFFFD700.toInt())
            letterSpacing = 0.03f
        }
        audioIndicator.addView(audioText)
        addView(audioIndicator)

        // Slot number badge (bottom-right, always visible)
        slotNumberBadge = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_slot_number_badge)
            layoutParams = LayoutParams(dp(28), dp(28)).also {
                it.gravity = Gravity.BOTTOM or Gravity.END
                it.setMargins(0, 0, dp(8), dp(8))
            }
        }
        addView(slotNumberBadge)

        // Empty state icon
        emptyIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_slot_empty)
            layoutParams = LayoutParams(dp(48), dp(48)).also {
                it.gravity = Gravity.CENTER
            }
            alpha = 0.15f
        }
        addView(emptyIcon)

        // Empty state text
        emptyText = TextView(context).apply {
            text = "Select Channel"
            textSize = 12f
            setTextColor(0x33FFFFFF)
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
                it.gravity = Gravity.CENTER
                it.topMargin = dp(32)
            }
        }
        addView(emptyText)

        // Signal Lost overlay (centered, shown when stream fails all retries)
        signalLostOverlay = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xCC0A0A0A.toInt())
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            visibility = GONE
        }
        val signalLostIcon = TextView(context).apply {
            text = "\u26A0"
            textSize = 24f
            gravity = Gravity.CENTER
        }
        signalLostOverlay.addView(signalLostIcon)
        val signalLostText = TextView(context).apply {
            text = "Signal Lost"
            textSize = 12f
            setTextColor(0xCCFF5252.toInt())
            gravity = Gravity.CENTER
        }
        signalLostOverlay.addView(signalLostText)
        val retryingText = TextView(context).apply {
            text = "Retrying..."
            textSize = 9f
            setTextColor(0x66FFFFFF)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(8) }
        }
        signalLostOverlay.addView(retryingText)
        addView(signalLostOverlay)

        // Health indicator dot (bottom-left, color-coded)
        healthDot = View(context).apply {
            layoutParams = LayoutParams(dp(6), dp(6)).also {
                it.gravity = Gravity.BOTTOM or Gravity.START
                it.setMargins(dp(10), 0, 0, dp(10))
            }
            setBackgroundColor(0xFF4CAF50.toInt()) // Green default
            visibility = GONE
        }
        addView(healthDot)

        // Recovery fade mask — dark overlay shown during hard/nuclear reset to hide visual glitches
        recoveryFadeMask = View(context).apply {
            setBackgroundColor(0xFF0A0A0A.toInt())
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            alpha = 0f
            visibility = GONE
        }
        addView(recoveryFadeMask)

        // Recovery spinner — shown during nuclear reset (gold tint, centered)
        recoverySpinner = ProgressBar(context).apply {
            isIndeterminate = true
            layoutParams = LayoutParams(dp(32), dp(32)).also {
                it.gravity = Gravity.CENTER
            }
            indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFFFFD700.toInt())
            visibility = GONE
        }
        addView(recoverySpinner)

        updateSlotNumber()
    }

    fun getPlayerView(): PlayerView = playerView

    fun setChannel(channel: LiveStream) {
        channelNameText.text = channel.name
        channelBadge.visibility = VISIBLE
        bottomScrim.visibility = VISIBLE
        emptyIcon.visibility = GONE
        emptyText.visibility = GONE

        // Start LIVE dot pulsing
        startLivePulse()
    }

    fun clearChannel() {
        channelBadge.visibility = GONE
        bottomScrim.visibility = GONE
        emptyIcon.visibility = VISIBLE
        emptyText.visibility = VISIBLE
        stopLivePulse()
    }

    fun setAudioActive(active: Boolean) {
        audioIndicator.animate()
            .alpha(if (active) 1f else 0f)
            .setDuration(300)
            .withStartAction { if (active) audioIndicator.visibility = VISIBLE }
            .withEndAction { if (!active) audioIndicator.visibility = GONE }
            .start()
    }

    fun setSlotFocused(focused: Boolean) {
        setBackgroundResource(
            if (focused) R.drawable.bg_multiview_slot_focused
            else R.drawable.bg_multiview_slot
        )

        // Update slot number badge colors
        if (focused) {
            slotNumberBadge.setTextColor(0xFFFFD700.toInt())
            slotNumberBadge.setBackgroundColor(0x33FFD700.toInt())
        } else {
            slotNumberBadge.setTextColor(Color.WHITE)
            slotNumberBadge.setBackgroundResource(R.drawable.bg_slot_number_badge)
        }

        // Update empty state colors on focus
        if (emptyIcon.visibility == VISIBLE) {
            emptyIcon.alpha = if (focused) 0.5f else 0.15f
            emptyText.setTextColor(if (focused) 0x80FFFFFF.toInt() else 0x33FFFFFF)
        }
    }

    /**
     * Visual indicator for swap mode — source slot gets gold pulsing border.
     */
    fun setSwapSource(active: Boolean) {
        if (active) {
            setBackgroundResource(R.drawable.bg_multiview_slot_swap_source)
        }
    }

    /**
     * Visual indicator for swap mode — target slots get cyan highlight border.
     */
    fun setSwapTarget(active: Boolean) {
        if (active) {
            setBackgroundResource(R.drawable.bg_multiview_slot_swap_target)
        }
    }

    /**
     * Clear swap mode visuals and restore normal background.
     */
    fun clearSwapVisuals() {
        setBackgroundResource(R.drawable.bg_multiview_slot)
    }

    /**
     * Show/hide signal lost overlay when stream fails all retries.
     */
    fun setSignalLost(lost: Boolean) {
        signalLostOverlay.visibility = if (lost) VISIBLE else GONE
    }

    /**
     * Update health indicator dot color based on stream health.
     */
    fun setHealthIndicator(health: PlaybackHealth) {
        healthDot.visibility = VISIBLE
        when (health) {
            PlaybackHealth.SMOOTH -> healthDot.setBackgroundColor(0xFF4CAF50.toInt())          // Green
            PlaybackHealth.SLIGHT_STUTTER -> healthDot.setBackgroundColor(0xFFFFEB3B.toInt())  // Yellow
            PlaybackHealth.CHOPPING -> healthDot.setBackgroundColor(0xFFFF9800.toInt())        // Orange
            PlaybackHealth.FROZEN -> healthDot.setBackgroundColor(0xFFFF5252.toInt())          // Red
            PlaybackHealth.DEAD -> healthDot.setBackgroundColor(0xFFFF5252.toInt())            // Red
        }
    }

    /**
     * Show fade mask for recovery. 150ms fade in, callback when visible.
     * @param withSpinner true for nuclear reset (shows gold loading spinner)
     * @param onVisible called when mask is fully visible — start the reset here
     */
    fun showRecoveryMask(withSpinner: Boolean = false, onVisible: (() -> Unit)? = null) {
        recoveryFadeMask.visibility = VISIBLE
        recoveryFadeMask.animate()
            .alpha(0.85f)
            .setDuration(150)
            .withEndAction {
                if (withSpinner) {
                    recoverySpinner.visibility = VISIBLE
                }
                onVisible?.invoke()
            }
            .start()
    }

    /**
     * Hide fade mask after first frame renders (smooth reveal).
     * @param durationMs fade out duration (200ms for hard reset, 300ms for nuclear)
     */
    fun hideRecoveryMask(durationMs: Long = 200) {
        recoverySpinner.visibility = GONE
        recoveryFadeMask.animate()
            .alpha(0f)
            .setDuration(durationMs)
            .withEndAction {
                recoveryFadeMask.visibility = GONE
            }
            .start()
    }

    private fun updateSlotNumber() {
        slotNumberBadge.text = (slotIndex + 1).toString()
    }

    private fun startLivePulse() {
        livePulseAnimator?.cancel()
        livePulseAnimator = ObjectAnimator.ofFloat(liveDot, "alpha", 1f, 0.3f).apply {
            duration = 1500
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopLivePulse() {
        livePulseAnimator?.cancel()
        livePulseAnimator = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopLivePulse()
    }
}
