package com.ooustream.iptv.search

import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import com.ooustream.iptv.R

/**
 * Manages animated gold glow pulse on the search bar when focused.
 * On focus: sets gold-stroke drawable, starts alpha pulse animation.
 * On unfocus: cancels animation, restores default bg_search_input drawable.
 */
class SearchBarFocusAnimator(
    private val searchBar: View,
    private val searchIcon: ImageView
) {
    private var glowAnimator: ValueAnimator? = null
    private val goldDrawable = GradientDrawable().apply {
        setColor(0xFF0E0E16.toInt())
        cornerRadius = 12f * searchBar.context.resources.displayMetrics.density
        setStroke(
            (1.5f * searchBar.context.resources.displayMetrics.density).toInt(),
            0xFFFFC107.toInt()
        )
    }

    fun onFocusGained() {
        searchBar.background = goldDrawable
        searchIcon.setColorFilter(0xFFFFC107.toInt())

        glowAnimator?.cancel()
        glowAnimator = ValueAnimator.ofInt(128, 255).apply {
            duration = 1500
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val alpha = anim.animatedValue as Int
                goldDrawable.setStroke(
                    (1.5f * searchBar.context.resources.displayMetrics.density).toInt(),
                    (alpha shl 24) or 0x00FFC107
                )
            }
            start()
        }
    }

    fun onFocusLost() {
        glowAnimator?.cancel()
        glowAnimator = null
        searchBar.setBackgroundResource(R.drawable.bg_search_input)
        searchIcon.setColorFilter(0xFF6B7280.toInt())
    }

    fun release() {
        glowAnimator?.cancel()
        glowAnimator = null
    }
}
