package com.ooustream.iptv.common

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import android.view.animation.LinearInterpolator

/**
 * Renders a slow-drifting aurora atmospheric background using radial gradients.
 * Three colour orbs float across the canvas in a gentle loop, creating an ambient
 * cinema feel without demanding GPU resources (no shaders / RenderScript).
 */
class AuroraBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Deep base colour matching aurora_bg_deep
    private val basePaint = Paint().apply {
        color = Color.parseColor("#FF050508")
        style = Paint.Style.FILL
    }

    // Three colour orbs that drift slowly
    private data class Orb(
        val color: Int,
        val alpha: Int, // 0-255
        var cx: Float = 0f,
        var cy: Float = 0f,
        var radius: Float = 0f,
        val dxFactor: Float = 1f,
        val dyFactor: Float = 1f,
        val radiusFactor: Float = 1f
    )

    private val orbs = listOf(
        Orb(Color.parseColor("#0A1628"), alpha = 80, dxFactor = 0.3f, dyFactor = 0.2f, radiusFactor = 0.6f),
        Orb(Color.parseColor("#12081F"), alpha = 60, dxFactor = -0.2f, dyFactor = 0.35f, radiusFactor = 0.5f),
        Orb(Color.parseColor("#081A1A"), alpha = 50, dxFactor = 0.25f, dyFactor = -0.15f, radiusFactor = 0.55f)
    )

    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Ambient colour extracted from hero artwork (10% opacity)
    private var ambientColor: Int = Color.TRANSPARENT

    fun setAmbientColor(color: Int) {
        ambientColor = ColorUtils.setAlphaComponent(color, 25)
        invalidate()
    }

    private var progress = 0f
    private var animator: ValueAnimator? = null

    init {
        // Use software layer since radial gradients can be heavy on some TV GPUs
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimation()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private fun startAnimation() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 30_000L // 30 second cycle
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // Draw deep base
        canvas.drawRect(0f, 0f, w, h, basePaint)

        // Animate each orb position based on progress using smooth sin/cos curves
        for ((i, orb) in orbs.withIndex()) {
            val phase = i * (Math.PI * 2.0 / orbs.size)
            val angle = progress * Math.PI * 2.0 + phase

            orb.cx = w * (0.3f + 0.4f * Math.sin(angle * orb.dxFactor).toFloat())
            orb.cy = h * (0.3f + 0.4f * Math.cos(angle * orb.dyFactor).toFloat())
            orb.radius = (Math.min(w, h) * orb.radiusFactor)

            val gradient = RadialGradient(
                orb.cx, orb.cy, orb.radius,
                intArrayOf(
                    Color.argb(orb.alpha, Color.red(orb.color), Color.green(orb.color), Color.blue(orb.color)),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )

            orbPaint.shader = gradient
            canvas.drawCircle(orb.cx, orb.cy, orb.radius, orbPaint)
        }

        // Ambient glow from hero artwork colour
        if (ambientColor != Color.TRANSPARENT) {
            val glowGradient = RadialGradient(
                w / 2f, 0f, h * 0.4f,
                ambientColor, Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            orbPaint.shader = glowGradient
            canvas.drawCircle(w / 2f, 0f, h * 0.4f, orbPaint)
        }
    }
}
