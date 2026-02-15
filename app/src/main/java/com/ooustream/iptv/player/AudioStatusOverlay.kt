package com.ooustream.iptv.player

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Small top-right indicator shown when audio issues are detected.
 * Non-intrusive: doesn't block video, doesn't steal focus.
 * Auto-dismisses after 8 seconds for transient issues.
 */
class AudioStatusOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val label: TextView
    private var persistent = false

    init {
        val bg = GradientDrawable().apply {
            setColor(0xCC000000.toInt())
            cornerRadius = dp(4f)
        }
        label = TextView(context).apply {
            setTextColor(0xFFFF6B6B.toInt()) // Soft red for warnings
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(12).toInt(), dp(6).toInt(), dp(12).toInt(), dp(6).toInt())
            background = bg
        }
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(48).toInt()
            marginEnd = dp(24).toInt()
        }
        addView(label, lp)
        visibility = GONE
        isFocusable = false
        isClickable = false
    }

    fun showNoAudio() {
        show("No audio track", persistent = true)
    }

    fun showCodecUnsupported() {
        show("Audio format not supported", persistent = true)
    }

    fun showSilentDetected() {
        show("No audio detected", persistent = false)
    }

    fun dismiss() {
        handler?.removeCallbacksAndMessages(null)
        persistent = false
        visibility = GONE
    }

    private fun show(text: String, persistent: Boolean) {
        this.persistent = persistent
        label.text = text
        visibility = VISIBLE
        alpha = 1f
        handler?.removeCallbacksAndMessages(null)
        if (!persistent) {
            postDelayed({
                animate().alpha(0f).setDuration(400).withEndAction {
                    visibility = GONE
                }.start()
            }, 8_000)
        }
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }

    private fun dp(value: Int): Float {
        return value * resources.displayMetrics.density
    }
}
