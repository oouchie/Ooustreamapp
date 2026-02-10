package com.ooustream.iptv.common

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator

object FocusAnimator {

    private const val SCALE_POSTER = 1.1f
    private const val SCALE_CHANNEL = 1.06f
    private const val SCALE_SECTION = 1.1f
    private const val SCALE_CW = 1.1f
    private const val DURATION = 200L

    private val overshoot = OvershootInterpolator(1.2f)

    fun animatePosterFocus(view: View, hasFocus: Boolean) {
        val scale = if (hasFocus) SCALE_POSTER else 1f
        view.animate()
            .scaleX(scale).scaleY(scale)
            .setDuration(DURATION)
            .setInterpolator(if (hasFocus) overshoot else null)
            .start()

        if (hasFocus) {
            val drawable = FocusBracketDrawable()
            drawable.setBounds(0, 0, view.width, view.height)
            view.overlay.add(drawable)
            dimSiblings(view, true)
        } else {
            view.overlay.clear()
            dimSiblings(view, false)
        }
    }

    fun animateChannelFocus(view: View, hasFocus: Boolean) {
        val scale = if (hasFocus) SCALE_CHANNEL else 1f
        view.animate()
            .scaleX(scale).scaleY(scale)
            .setDuration(DURATION)
            .start()

        if (hasFocus) {
            val drawable = FocusBracketDrawable(
                bracketColor = Color.parseColor("#00B4D8"),
                glowColor = Color.parseColor("#3300B4D8")
            )
            drawable.setBounds(0, 0, view.width, view.height)
            view.overlay.add(drawable)
        } else {
            view.overlay.clear()
        }
    }

    fun animateSectionFocus(view: View, hasFocus: Boolean) {
        val scale = if (hasFocus) SCALE_SECTION else 1f
        view.animate()
            .scaleX(scale).scaleY(scale)
            .setDuration(DURATION)
            .setInterpolator(if (hasFocus) overshoot else null)
            .start()

        if (hasFocus) {
            val drawable = FocusBracketDrawable()
            drawable.setBounds(0, 0, view.width, view.height)
            view.overlay.add(drawable)
        } else {
            view.overlay.clear()
        }
    }

    fun animateContinueWatchingFocus(view: View, hasFocus: Boolean) {
        val scale = if (hasFocus) SCALE_CW else 1f
        view.animate()
            .scaleX(scale).scaleY(scale)
            .setDuration(DURATION)
            .setInterpolator(if (hasFocus) overshoot else null)
            .start()

        if (hasFocus) {
            val drawable = FocusBracketDrawable()
            drawable.setBounds(0, 0, view.width, view.height)
            view.overlay.add(drawable)
        } else {
            view.overlay.clear()
        }
    }

    private fun dimSiblings(view: View, dim: Boolean) {
        val parent = view.parent as? ViewGroup ?: return
        val targetAlpha = if (dim) 0.6f else 1f
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child != view) {
                child.animate().alpha(targetAlpha).setDuration(DURATION).start()
            }
        }
    }
}
