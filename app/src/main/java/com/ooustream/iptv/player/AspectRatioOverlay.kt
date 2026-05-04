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
 * Brief HUD label shown when the user cycles aspect ratio (button tap or pinch-zoom).
 * Centered on screen, fades after 1.5s. Replaces the older Toast feedback.
 */
class AspectRatioOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val label: TextView
    private val dpDensity = resources.displayMetrics.density

    init {
        label = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(
                (24 * dpDensity).toInt(),
                (12 * dpDensity).toInt(),
                (24 * dpDensity).toInt(),
                (12 * dpDensity).toInt()
            )
            background = GradientDrawable().apply {
                cornerRadius = 24f * dpDensity
                setColor(0xCC000000.toInt())
            }
        }
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })
        visibility = GONE
    }

    fun show(modeLabel: String) {
        label.text = modeLabel
        animate().cancel()
        visibility = VISIBLE
        alpha = 1f
        handler?.removeCallbacksAndMessages(null)
        postDelayed({
            animate().alpha(0f).setDuration(250).withEndAction {
                visibility = GONE
            }.start()
        }, 1500)
    }

    fun dismiss() {
        animate().cancel()
        handler?.removeCallbacksAndMessages(null)
        visibility = GONE
    }
}
