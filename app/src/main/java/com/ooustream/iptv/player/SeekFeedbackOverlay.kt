package com.ooustream.iptv.player

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Brief on-screen text ("▶▶ +10s" / "◀◀ -10s") shown when the user
 * seeks forward or backward with DPAD_LEFT/RIGHT. Auto-dismisses after 800ms.
 * Tracks cumulative delta for rapid taps (e.g., 3 taps → "+30s").
 */
class SeekFeedbackOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val label: TextView
    private var cumulativeDeltaMs: Long = 0

    init {
        label = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setShadowLayer(8f, 0f, 2f, 0xCC000000.toInt())
        }
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        }
        addView(label, lp)
        visibility = GONE
    }

    /** Show cumulative seek feedback. [deltaMs] is signed: positive = forward, negative = backward. */
    fun showSeek(deltaMs: Long) {
        cumulativeDeltaMs += deltaMs
        val absSec = Math.abs(cumulativeDeltaMs) / 1000
        val text = if (cumulativeDeltaMs >= 0) {
            "\u25B6\u25B6 +${absSec}s"
        } else {
            "\u25C0\u25C0 -${absSec}s"
        }
        label.text = text
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
}
