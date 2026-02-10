package com.ooustream.iptv.player

import android.content.Context
import android.os.CountDownTimer
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.ooustream.iptv.R

/**
 * Binge-mode countdown overlay that appears near the end of a series episode.
 *
 * Shows a bottom-right card with a countdown to auto-play the next episode.
 * The user can press "Watch Next" to skip the countdown or "Cancel" to stay
 * on the current episode. When the countdown reaches zero, [onPlayNext] fires
 * automatically.
 */
class BingeCountdownOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val countdownText: TextView
    private val nextTitle: TextView
    private val playNow: TextView
    private val cancelBtn: TextView
    private var timer: CountDownTimer? = null

    /** Called when the user confirms or the countdown reaches zero. */
    var onPlayNext: (() -> Unit)? = null

    /** Called when the user cancels auto-play. */
    var onCancel: (() -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.overlay_binge_countdown, this, true)
        countdownText = findViewById(R.id.binge_countdown_text)
        nextTitle = findViewById(R.id.binge_next_title)
        playNow = findViewById(R.id.binge_play_now)
        cancelBtn = findViewById(R.id.binge_cancel)
        visibility = GONE

        playNow.setOnClickListener {
            timer?.cancel()
            visibility = GONE
            onPlayNext?.invoke()
        }
        cancelBtn.setOnClickListener {
            timer?.cancel()
            visibility = GONE
            onCancel?.invoke()
        }
    }

    /**
     * Display the overlay with the given [nextEpisodeTitle] and start a
     * [countdownSeconds]-second countdown. When the countdown expires the
     * overlay auto-triggers [onPlayNext].
     */
    fun show(nextEpisodeTitle: String, countdownSeconds: Int = 10) {
        nextTitle.text = nextEpisodeTitle
        visibility = VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(300).start()
        playNow.requestFocus()

        timer?.cancel()
        timer = object : CountDownTimer(countdownSeconds * 1000L, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secs = (millisUntilFinished / 1000).toInt() + 1
                countdownText.text = context.getString(R.string.next_episode_in, secs)
            }
            override fun onFinish() {
                visibility = GONE
                onPlayNext?.invoke()
            }
        }.start()

        countdownText.text = context.getString(R.string.next_episode_in, countdownSeconds)
    }

    /** Cancel the countdown and hide the overlay immediately. */
    fun dismiss() {
        timer?.cancel()
        visibility = GONE
    }

    /** Whether the overlay is currently visible on screen. */
    val isShowing: Boolean get() = visibility == VISIBLE
}
