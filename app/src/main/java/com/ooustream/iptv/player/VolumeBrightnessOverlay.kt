package com.ooustream.iptv.player

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Vertical-bar HUD shown during left-half (brightness) or right-half (volume)
 * vertical drag on the player surface. Auto-fades 800ms after last update.
 *
 * Two modes — VOLUME (right side, speaker icon) and BRIGHTNESS (left side, sun icon).
 * Both render a thin vertical pill with a gold fill from bottom representing
 * current value 0..1, an icon at top, and an integer percentage label.
 */
class VolumeBrightnessOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    enum class Mode { VOLUME, BRIGHTNESS }

    private val column: LinearLayout
    private val iconView: ImageView
    private val percentLabel: TextView
    private val barTrack: View
    private val barFill: View
    private val dpDensity = resources.displayMetrics.density

    init {
        column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // dark scrim pill
            background = GradientDrawable().apply {
                cornerRadius = 22f * dpDensity
                setColor(0xCC000000.toInt())
            }
            setPadding(
                (12 * dpDensity).toInt(),
                (16 * dpDensity).toInt(),
                (12 * dpDensity).toInt(),
                (16 * dpDensity).toInt()
            )
        }
        iconView = ImageView(context).apply {
            setColorFilter(Color.WHITE)
        }
        column.addView(iconView, LinearLayout.LayoutParams(
            (24 * dpDensity).toInt(), (24 * dpDensity).toInt()
        ).apply { bottomMargin = (10 * dpDensity).toInt() })

        // Vertical bar wrapper (track + fill)
        val barWrapper = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = 4f * dpDensity
                setColor(0x33FFFFFF)
            }
        }
        barTrack = View(context)
        barWrapper.addView(barTrack, LayoutParams(
            (6 * dpDensity).toInt(), (140 * dpDensity).toInt()
        ))
        barFill = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = 4f * dpDensity
                setColor(0xFFFFC107.toInt()) // gold
            }
        }
        barWrapper.addView(barFill, LayoutParams(
            (6 * dpDensity).toInt(), (10 * dpDensity).toInt(), Gravity.BOTTOM
        ))
        column.addView(barWrapper, LinearLayout.LayoutParams(
            (6 * dpDensity).toInt(), (140 * dpDensity).toInt()
        ))

        percentLabel = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        column.addView(percentLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (8 * dpDensity).toInt() })

        addView(column, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            leftMargin = (24 * dpDensity).toInt()
        })
        visibility = GONE
    }

    fun show(mode: Mode, value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        // Pin column to left or right of screen depending on mode
        val lp = column.layoutParams as LayoutParams
        if (mode == Mode.BRIGHTNESS) {
            lp.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            lp.leftMargin = (24 * dpDensity).toInt()
            lp.rightMargin = 0
            iconView.setImageResource(android.R.drawable.btn_star_big_on) // sun-ish placeholder
        } else {
            lp.gravity = Gravity.CENTER_VERTICAL or Gravity.END
            lp.leftMargin = 0
            lp.rightMargin = (24 * dpDensity).toInt()
            iconView.setImageResource(android.R.drawable.ic_lock_silent_mode_off) // speaker placeholder
        }
        column.layoutParams = lp

        // Update fill height
        val maxFillPx = (140 * dpDensity).toInt()
        val fillLp = barFill.layoutParams as LayoutParams
        fillLp.height = (maxFillPx * clamped).toInt().coerceAtLeast(1)
        fillLp.gravity = Gravity.BOTTOM
        barFill.layoutParams = fillLp

        percentLabel.text = "${(clamped * 100).toInt()}%"

        animate().cancel()
        visibility = VISIBLE
        alpha = 1f
        handler?.removeCallbacksAndMessages(null)
        postDelayed({
            animate().alpha(0f).setDuration(250).withEndAction {
                visibility = GONE
            }.start()
        }, 800)
    }

    fun dismiss() {
        animate().cancel()
        handler?.removeCallbacksAndMessages(null)
        visibility = GONE
    }
}
