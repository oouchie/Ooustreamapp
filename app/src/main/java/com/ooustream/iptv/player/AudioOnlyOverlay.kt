package com.ooustream.iptv.player

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.ooustream.iptv.R

class AudioOnlyOverlay(context: Context) : FrameLayout(context) {

    private val streamNameText: TextView
    private val equalizerBars: LinearLayout
    private val barAnimators = mutableListOf<ValueAnimator>()

    init {
        LayoutInflater.from(context).inflate(R.layout.overlay_audio_only, this, true)
        streamNameText = findViewById(R.id.audio_stream_name)
        equalizerBars = findViewById(R.id.equalizer_bars)
        visibility = GONE
        setupEqualizerBars()
    }

    private fun setupEqualizerBars() {
        val barCount = 5
        val density = resources.displayMetrics.density
        val barWidth = (12 * density).toInt()
        val barGap = (4 * density).toInt()
        val initialHeight = (16 * density).toInt()

        for (i in 0 until barCount) {
            val bar = View(context).apply {
                background = GradientDrawable().apply {
                    setColor(0xFF00B4D8.toInt())
                    cornerRadius = barWidth / 2f
                }
                layoutParams = LinearLayout.LayoutParams(barWidth, initialHeight).apply {
                    if (i > 0) marginStart = barGap
                }
            }
            equalizerBars.addView(bar)
        }
    }

    fun show(streamName: String) {
        streamNameText.text = streamName
        visibility = VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(300).start()
        startEqualizerAnimation()
    }

    fun dismiss() {
        stopEqualizerAnimation()
        animate().alpha(0f).setDuration(300).withEndAction {
            visibility = GONE
        }.start()
    }

    private fun startEqualizerAnimation() {
        stopEqualizerAnimation()
        val density = resources.displayMetrics.density
        val maxHeight = (48 * density).toInt()
        val minHeight = (8 * density).toInt()

        for (i in 0 until equalizerBars.childCount) {
            val bar = equalizerBars.getChildAt(i)
            val targetHeight = (maxHeight * (0.3f + Math.random().toFloat() * 0.7f)).toInt()

            // Use ValueAnimator for LayoutParams.height since it is not a standard
            // animatable property -- requestLayout via layoutParams setter on each frame
            val animator = ValueAnimator.ofInt(minHeight, targetHeight).apply {
                duration = (300 + (Math.random() * 400).toLong())
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                startDelay = (i * 100).toLong()
                addUpdateListener { anim ->
                    val value = anim.animatedValue as Int
                    val params = bar.layoutParams
                    params.height = value
                    bar.layoutParams = params
                }
                start()
            }
            barAnimators.add(animator)
        }
    }

    private fun stopEqualizerAnimation() {
        barAnimators.forEach { it.cancel() }
        barAnimators.clear()
    }

    val isShowing: Boolean get() = visibility == VISIBLE
}
