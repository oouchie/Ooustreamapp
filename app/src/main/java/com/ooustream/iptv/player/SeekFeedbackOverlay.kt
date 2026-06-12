package com.ooustream.iptv.player

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Brief on-screen text ("▶▶ +10s" / "◀◀ -10s") shown when the user
 * seeks forward or backward with DPAD_LEFT/RIGHT. Auto-dismisses after 800ms.
 * Tracks cumulative delta for rapid taps (e.g., 3 taps → "+30s") and, when the
 * caller supplies it, shows the absolute landing timecode ("1:24:05") under the
 * delta so coarse scrubs are no longer blind.
 */
class SeekFeedbackOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val label: TextView
    private val targetLabel: TextView
    private var cumulativeDeltaMs: Long = 0

    init {
        label = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setShadowLayer(8f, 0f, 2f, 0xCC000000.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
        }
        targetLabel = TextView(context).apply {
            setTextColor(0xFFFFC107.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setShadowLayer(8f, 0f, 2f, 0xCC000000.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = GONE
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(label)
            addView(targetLabel)
        }
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        }
        addView(column, lp)
        visibility = GONE
    }

    /**
     * Show cumulative seek feedback. [deltaMs] is signed: positive = forward, negative = backward.
     * [targetMs] >= 0 additionally shows the absolute landing timecode.
     */
    fun showSeek(deltaMs: Long, targetMs: Long = -1L) {
        cumulativeDeltaMs += deltaMs
        val absSec = Math.abs(cumulativeDeltaMs) / 1000
        val text = if (cumulativeDeltaMs >= 0) {
            "▶▶ +${absSec}s"
        } else {
            "◀◀ -${absSec}s"
        }
        label.text = text
        if (targetMs >= 0) {
            targetLabel.text = formatTime(targetMs)
            targetLabel.visibility = VISIBLE
        } else {
            targetLabel.visibility = GONE
        }
        // Cancel any in-flight fade animation before making visible again
        animate().cancel()
        visibility = VISIBLE
        alpha = 1f
        handler?.removeCallbacksAndMessages(null)
        postDelayed({
            cumulativeDeltaMs = 0
            animate().alpha(0f).setDuration(300).withEndAction {
                visibility = GONE
            }.start()
        }, 800)
    }

    fun showForward() = showSeek(10_000)
    fun showBackward() = showSeek(-10_000)

    fun dismiss() {
        cumulativeDeltaMs = 0
        handler?.removeCallbacksAndMessages(null)
        visibility = GONE
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }
}
