package com.ooustream.iptv.player

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.ooustream.iptv.R

/**
 * Top-center card shown when playback keeps waiting on the stream (v5.2.4): what to try if it
 * keeps happening. Same contract as [AudioStatusOverlay]: never takes focus, never blocks the
 * picture for long, fades out on its own.
 */
class SlowConnectionTipOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    init {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xE6101010.toInt())
                cornerRadius = dp(12)
                setStroke(dp(1).toInt(), 0x33FFC107)
            }
            setPadding(dp(20).toInt(), dp(14).toInt(), dp(20).toInt(), dp(16).toInt())
        }
        card.addView(TextView(context).apply {
            text = context.getString(R.string.slow_connection_title)
            setTextColor(0xFFFFC107.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(TextView(context).apply {
            text = context.getString(R.string.slow_connection_steps)
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setLineSpacing(dp(3), 1f)
            setPadding(0, dp(6).toInt(), 0, 0)
        })
        addView(card, LayoutParams(dp(560).toInt(), LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(40).toInt()
        })
        visibility = GONE
        isFocusable = false
        isClickable = false
    }

    // Its own runnable, removed by reference. View.handler is the whole window's queue, so
    // handler.removeCallbacksAndMessages(null) wiped every other view's timers too (v5.2.4).
    private val fadeOut = Runnable {
        animate().alpha(0f).setDuration(400).withEndAction {
            visibility = GONE
        }.start()
    }

    fun show() {
        removeCallbacks(fadeOut)
        animate().cancel()
        visibility = VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(250).start()
        postDelayed(fadeOut, SHOW_MS)
    }

    fun dismiss() {
        removeCallbacks(fadeOut)
        animate().cancel()
        visibility = GONE
    }

    private fun dp(value: Int): Float = value * resources.displayMetrics.density

    private companion object {
        /** Long enough to read three short steps from the couch. */
        const val SHOW_MS = 14_000L
    }
}
