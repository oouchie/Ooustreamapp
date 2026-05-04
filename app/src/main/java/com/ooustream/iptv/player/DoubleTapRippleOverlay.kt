package com.ooustream.iptv.player

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Netflix-style double-tap seek ripple. When the user double-taps the left or right
 * half of the player, a circular ripple expands centered on the tap point with a
 * "« 10" or "10 »" label. Fades out over ~500ms.
 *
 * Single overlay covers the full player surface — caller calls [showAt] with
 * the tap X/Y in this view's coordinate space, plus the seek delta in seconds.
 */
class DoubleTapRippleOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private var tapX = 0f
    private var tapY = 0f
    private var ripplePhase = 0f // 0f → 1f
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x33FFFFFF // 20% white
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 2f, resources.displayMetrics
        )
        color = 0x66FFFFFF // 40% white ring
    }
    private val label: TextView

    init {
        setWillNotDraw(false)
        label = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setShadowLayer(6f, 0f, 1f, 0xCC000000.toInt())
        }
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })
        visibility = GONE
    }

    /** Animate the ripple at [x],[y] with [seekSeconds] (positive = forward). */
    fun showAt(x: Float, y: Float, seekSeconds: Int) {
        tapX = x
        tapY = y
        label.text = if (seekSeconds >= 0) "$seekSeconds  »»" else "««  ${-seekSeconds}"

        // Position label near tap point, vertically centered with a slight offset
        val lp = label.layoutParams as LayoutParams
        lp.gravity = Gravity.NO_GRAVITY
        // Center label horizontally on tap point. Measure first.
        label.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        lp.leftMargin = (x - label.measuredWidth / 2f).toInt().coerceIn(0, width - label.measuredWidth)
        lp.topMargin = (y - label.measuredHeight / 2f).toInt().coerceIn(0, height - label.measuredHeight)
        label.layoutParams = lp

        ripplePhase = 0f
        visibility = VISIBLE
        alpha = 1f
        invalidate()

        animate().cancel()
        val ripple = ObjectAnimator.ofFloat(this, "ripplePhase", 0f, 1f).apply {
            duration = 450
        }
        val fade = ObjectAnimator.ofFloat(this, "alpha", 1f, 0f).apply {
            startDelay = 200
            duration = 250
        }
        AnimatorSet().apply {
            playTogether(ripple, fade)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    visibility = GONE
                }
            })
            start()
        }
    }

    fun setRipplePhase(value: Float) {
        ripplePhase = value
        invalidate()
    }

    fun getRipplePhase(): Float = ripplePhase

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (visibility != VISIBLE) return
        val maxRadius = resources.displayMetrics.density * 140f
        val r = maxRadius * ripplePhase
        val fillAlpha = ((1f - ripplePhase) * 80).toInt().coerceIn(0, 255)
        ripplePaint.color = (fillAlpha shl 24) or 0x00FFFFFF
        canvas.drawCircle(tapX, tapY, r, ripplePaint)
        ringPaint.alpha = ((1f - ripplePhase) * 200).toInt().coerceIn(0, 255)
        canvas.drawCircle(tapX, tapY, r, ringPaint)
    }
}
