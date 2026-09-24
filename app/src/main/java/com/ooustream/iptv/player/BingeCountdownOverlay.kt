package com.ooustream.iptv.player

import android.animation.ValueAnimator
import android.content.Context
import android.os.CountDownTimer
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import coil.load
import coil.request.CachePolicy
import com.ooustream.iptv.R

/**
 * End-of-episode Watch Next card, shown ~15s before a series episode ends.
 *
 * Shows the next episode's still, number and title. The countdown is a gold fill draining out of
 * the Play button; when it empties [onPlayNext] fires. Cancel (or remote BACK, routed through
 * [cancel] by the playback fragment) keeps the viewer on the current episode.
 */
class BingeCountdownOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val header: TextView
    private val thumb: ImageView
    private val number: TextView
    private val nextTitle: TextView
    private val playNow: View
    private val drain: View
    private val cancelBtn: TextView
    private var timer: CountDownTimer? = null
    private var drainAnim: ValueAnimator? = null

    /** Called when the user confirms or the countdown runs out. */
    var onPlayNext: (() -> Unit)? = null

    /** Called when the user cancels auto-play. */
    var onCancel: (() -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.overlay_binge_countdown, this, true)
        header = findViewById(R.id.binge_countdown_text)
        thumb = findViewById(R.id.binge_thumb)
        number = findViewById(R.id.binge_number)
        nextTitle = findViewById(R.id.binge_next_title)
        playNow = findViewById(R.id.binge_play_now)
        drain = findViewById(R.id.binge_drain)
        cancelBtn = findViewById(R.id.binge_cancel)
        visibility = GONE
        thumb.clipToOutline = true
        drain.pivotX = 0f

        playNow.setOnClickListener {
            stopCountdown()
            visibility = GONE
            onPlayNext?.invoke()
        }
        cancelBtn.setOnClickListener { cancel() }

        // Focus = gold ring (sd_card_btn selector) + a small lift. Before 2026-09-24 neither button
        // had a focused state, so D-pad RIGHT moved to Cancel invisibly and the card read as dead.
        val lift = OnFocusChangeListener { v, hasFocus ->
            val s = if (hasFocus) 1.04f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(150).start()
            if (v === cancelBtn) cancelBtn.setTextColor(
                context.getColor(if (hasFocus) R.color.sd_text else R.color.sd_text_secondary)
            )
        }
        playNow.onFocusChangeListener = lift
        cancelBtn.onFocusChangeListener = lift
    }

    /**
     * Show the card for the next episode and start a [countdownSeconds] countdown.
     * [newSeason] is the season number when the next episode crosses into a new season, else 0.
     */
    fun show(
        episodeNum: Int,
        episodeTitle: String,
        imageUrl: String?,
        newSeason: Int = 0,
        countdownSeconds: Int = 10
    ) {
        header.text = if (newSeason > 0) context.getString(R.string.binge_new_season, newSeason)
        else context.getString(R.string.binge_next_episode)
        number.text = episodeNum.toString()
        nextTitle.text = episodeTitle
        nextTitle.visibility = if (episodeTitle.isBlank()) GONE else VISIBLE
        if (imageUrl.isNullOrBlank()) {
            thumb.visibility = GONE
        } else {
            thumb.visibility = VISIBLE
            thumb.load(imageUrl) {
                crossfade(true)
                memoryCachePolicy(CachePolicy.ENABLED)
                listener(onError = { _, _ -> thumb.visibility = GONE })
            }
        }

        visibility = VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(300).start()
        playNow.requestFocus()

        stopCountdown()
        // The CountDownTimer is the real clock. The drain animation is visual only: ValueAnimator
        // honours the system animator-duration scale, so with animations disabled it would end
        // instantly — it must never be what triggers auto-play.
        timer = object : CountDownTimer(countdownSeconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {}
            override fun onFinish() {
                if (visibility != VISIBLE) return
                stopCountdown()
                visibility = GONE
                onPlayNext?.invoke()
            }
        }.start()
        drain.scaleX = 1f
        drainAnim = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = countdownSeconds * 1000L
            interpolator = LinearInterpolator()
            addUpdateListener { drain.scaleX = it.animatedValue as Float }
            start()
        }
    }

    /** Cancel auto-play (Cancel button or remote BACK). No-op when not showing. */
    fun cancel() {
        if (!isShowing) return
        stopCountdown()
        visibility = GONE
        onCancel?.invoke()
    }

    /** Stop the countdown and hide the card without firing either callback. */
    fun dismiss() {
        stopCountdown()
        visibility = GONE
    }

    private fun stopCountdown() {
        timer?.cancel()
        timer = null
        drainAnim?.cancel()
        drainAnim = null
    }

    override fun onDetachedFromWindow() {
        stopCountdown()
        super.onDetachedFromWindow()
    }

    /** Whether the overlay is currently visible on screen. */
    val isShowing: Boolean get() = visibility == VISIBLE
}
