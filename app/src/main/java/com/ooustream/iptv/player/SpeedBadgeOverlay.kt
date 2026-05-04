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
 * "2×" badge shown top-center while the user is long-pressing the player surface
 * to fast-forward. Visible while held; caller calls [show] on long-press start
 * and [dismiss] on touch release.
 */
class SpeedBadgeOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val label: TextView
    private val dpDensity = resources.displayMetrics.density

    init {
        label = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(
                (16 * dpDensity).toInt(),
                (8 * dpDensity).toInt(),
                (16 * dpDensity).toInt(),
                (8 * dpDensity).toInt()
            )
            background = GradientDrawable().apply {
                cornerRadius = 18f * dpDensity
                setColor(0xCC000000.toInt())
            }
            text = "2×  »»"
        }
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = (40 * dpDensity).toInt()
        })
        visibility = GONE
    }

    fun show() {
        animate().cancel()
        visibility = VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(150).start()
    }

    fun dismiss() {
        animate().cancel()
        animate().alpha(0f).setDuration(150).withEndAction {
            visibility = GONE
        }.start()
    }
}
