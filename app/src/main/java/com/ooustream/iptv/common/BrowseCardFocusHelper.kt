package com.ooustream.iptv.common

import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.leanback.widget.HorizontalGridView
import com.ooustream.iptv.R
import kotlin.math.abs

/**
 * Creates a spotlight effect on HorizontalGridView rows by dimming
 * non-focused cards and highlighting the row header label.
 */
object BrowseCardFocusHelper {

    private const val ALPHA_FOCUSED = 1.0f
    private const val ALPHA_NEAR = 0.65f
    private const val ALPHA_FAR = 0.50f
    private const val ANIM_DURATION = 250L
    private const val RESET_DURATION = 200L
    private const val DEBOUNCE_MS = 100L

    private val handler = Handler(Looper.getMainLooper())
    private val pendingDimming = HashMap<Int, Runnable>()

    /**
     * Apply neighbor dimming to a HorizontalGridView.
     * Cards near the focused position dim to 65%, distant cards to 50%.
     * Debounced to prevent flashing during fast D-pad scrolling.
     */
    fun applyNeighborDimming(gridView: HorizontalGridView, focusedPosition: Int) {
        val key = System.identityHashCode(gridView)
        pendingDimming[key]?.let { handler.removeCallbacks(it) }

        val runnable = Runnable {
            for (i in 0 until gridView.childCount) {
                val child = gridView.getChildAt(i) ?: continue
                val pos = gridView.getChildAdapterPosition(child)
                if (pos < 0) continue

                val distance = abs(pos - focusedPosition)
                val targetAlpha = when {
                    distance == 0 -> ALPHA_FOCUSED
                    distance <= 2 -> ALPHA_NEAR
                    else -> ALPHA_FAR
                }
                child.animate()
                    .alpha(targetAlpha)
                    .setDuration(ANIM_DURATION)
                    .start()
            }
        }

        pendingDimming[key] = runnable
        handler.postDelayed(runnable, DEBOUNCE_MS)
    }

    /**
     * Reset all children of a HorizontalGridView to full alpha.
     */
    fun clearDimming(gridView: HorizontalGridView) {
        val key = System.identityHashCode(gridView)
        pendingDimming[key]?.let { handler.removeCallbacks(it) }
        pendingDimming.remove(key)

        for (i in 0 until gridView.childCount) {
            val child = gridView.getChildAt(i) ?: continue
            child.animate()
                .alpha(1.0f)
                .setDuration(RESET_DURATION)
                .start()
        }
    }

    /**
     * Highlight a row header label when any card in the row is focused.
     * Turns the label gold on focus, restores original color on unfocus.
     */
    fun highlightRowHeader(label: TextView, focused: Boolean) {
        val targetColor = if (focused) 0xFFFFC107.toInt() else label.context.getColor(R.color.text_primary)
        label.setTextColor(targetColor)
    }
}
