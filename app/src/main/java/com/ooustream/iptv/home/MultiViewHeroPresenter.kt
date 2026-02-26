package com.ooustream.iptv.home

import android.animation.ObjectAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.leanback.widget.Presenter
import com.ooustream.iptv.R
import com.ooustream.iptv.common.DeviceUtils
import com.ooustream.iptv.common.DpadSoundManager
import com.ooustream.iptv.common.FocusBracketDrawable
import com.ooustream.iptv.common.GoldGlowFocusDrawable

/**
 * Marker object for MultiView hero card data.
 * No data needed — this is a static promotional card.
 */
object MultiViewHeroItem

/**
 * Leanback Presenter for the MultiView hero card shown in the Home screen featured row.
 * Displays a 2x2 mini grid preview with promotional text and PRO badge.
 */
class MultiViewHeroPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(
            R.layout.item_hero_multiview_card,
            parent,
            false
        )
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        view.clipToOutline = true

        // Cache pulsing dot views for animation
        val dot1 = view.findViewById<View>(R.id.dot_1)
        val dot2 = view.findViewById<View>(R.id.dot_2)
        val dot3 = view.findViewById<View>(R.id.dot_3)
        val dot4 = view.findViewById<View>(R.id.dot_4)

        // Start pulsing animations (staggered for visual interest)
        startPulsingAnimation(dot1, 0)
        startPulsingAnimation(dot2, 300)
        startPulsingAnimation(dot3, 600)
        startPulsingAnimation(dot4, 900)

        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val root = viewHolder.view as FrameLayout

        // Set focus change listener
        root.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                DpadSoundManager.getInstance()?.playMove()

                // Apply focused background
                v.background = ContextCompat.getDrawable(
                    v.context,
                    R.drawable.bg_hero_multiview_card_focused
                )

                // Add gold glow and corner brackets
                if (DeviceUtils.isTV(v.context)) {
                    v.overlay.add(GoldGlowFocusDrawable())
                    v.overlay.add(FocusBracketDrawable())
                }

                // Scale up with elevation
                v.animate()
                    .scaleX(1.03f)
                    .scaleY(1.03f)
                    .translationZ(8f)
                    .setDuration(200)
                    .start()
            } else {
                // Restore unfocused background
                v.background = ContextCompat.getDrawable(
                    v.context,
                    R.drawable.bg_hero_multiview_card
                )

                // Clear overlays
                v.overlay.clear()

                // Scale down
                v.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationZ(0f)
                    .setDuration(200)
                    .start()
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val root = viewHolder.view as FrameLayout
        root.setOnFocusChangeListener(null)

        // Cancel pulsing animations
        val dot1 = root.findViewById<View>(R.id.dot_1)
        val dot2 = root.findViewById<View>(R.id.dot_2)
        val dot3 = root.findViewById<View>(R.id.dot_3)
        val dot4 = root.findViewById<View>(R.id.dot_4)

        dot1?.clearAnimation()
        dot2?.clearAnimation()
        dot3?.clearAnimation()
        dot4?.clearAnimation()
    }

    /**
     * Start infinite alpha pulsing animation on a view (red dot indicator).
     * @param view The view to animate
     * @param startDelay Initial delay in milliseconds for staggered effect
     */
    private fun startPulsingAnimation(view: View, startDelay: Long) {
        val animator = ObjectAnimator.ofFloat(view, "alpha", 1f, 0.2f, 1f).apply {
            duration = 2000
            repeatCount = ObjectAnimator.INFINITE
            this.startDelay = startDelay
        }
        animator.start()
    }
}
