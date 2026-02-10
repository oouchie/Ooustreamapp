package com.ooustream.iptv.common

import android.content.Context
import android.os.CountDownTimer
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.ooustream.iptv.R

/**
 * Translucent bottom bar that displays contextual remote-control hints
 * (e.g. "UP/DOWN: Channels - OK: Controls - BACK: Exit").
 *
 * Show with [showHints]; the overlay auto-dismisses after the given timeout.
 * Call [dismiss] to hide manually.
 */
class RemoteHintOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val container: LinearLayout
    private val hintsText: TextView
    private var dismissTimer: CountDownTimer? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.overlay_remote_hints, this, true)
        container = findViewById(R.id.hints_container)
        hintsText = findViewById(R.id.hints_text)
    }

    /**
     * Display [text] in the hint bar, automatically hiding after [autoDismissMs].
     * Calling again while visible resets the timer and updates the text.
     */
    fun showHints(text: String, autoDismissMs: Long = 3000) {
        hintsText.text = text
        container.visibility = VISIBLE
        container.alpha = 0f
        container.animate().alpha(1f).setDuration(ANIM_DURATION).start()

        dismissTimer?.cancel()
        dismissTimer = object : CountDownTimer(autoDismissMs, autoDismissMs) {
            override fun onTick(millisUntilFinished: Long) { /* no-op */ }
            override fun onFinish() {
                container.animate().alpha(0f).setDuration(ANIM_DURATION).withEndAction {
                    container.visibility = GONE
                }.start()
            }
        }.start()
    }

    /** Immediately dismiss the hint bar. */
    fun dismiss() {
        dismissTimer?.cancel()
        container.animate().alpha(0f).setDuration(ANIM_DURATION).withEndAction {
            container.visibility = GONE
        }.start()
    }

    companion object {
        private const val ANIM_DURATION = 200L
    }
}
