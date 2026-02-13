package com.ooustream.iptv.player

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import com.ooustream.iptv.data.model.ContentType

/**
 * Manages the custom player controls overlay visibility and auto-hide timer.
 *
 * Show triggers: any D-pad press, play/pause, channel switch.
 * Hide triggers: auto-hide timer, Back press, explicit hide().
 * Timer paused when seek bar is focused (user is scrubbing).
 */
class PlayerControlsManager(
    private val controlsOverlay: View,
    private val contentType: ContentType,
) {
    private val autoHideDelayMs: Long = 15_000L

    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hide() }

    var isVisible: Boolean = false
        private set

    var onVisibilityChanged: ((Boolean) -> Unit)? = null

    fun show() {
        if (isVisible) {
            resetAutoHideTimer()
            return
        }

        isVisible = true
        controlsOverlay.visibility = View.VISIBLE
        controlsOverlay.alpha = 0f
        controlsOverlay.animate()
            .alpha(1f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()

        onVisibilityChanged?.invoke(true)
        resetAutoHideTimer()
    }

    fun hide() {
        if (!isVisible) return

        handler.removeCallbacks(hideRunnable)
        controlsOverlay.animate()
            .alpha(0f)
            .setDuration(300)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                controlsOverlay.visibility = View.GONE
                isVisible = false
                onVisibilityChanged?.invoke(false)
            }
            .start()
    }

    fun toggle() {
        if (isVisible) hide() else show()
    }

    fun resetAutoHideTimer() {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, autoHideDelayMs)
    }

    fun pauseAutoHide() {
        handler.removeCallbacks(hideRunnable)
    }

    fun resumeAutoHide() {
        resetAutoHideTimer()
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
    }
}
