package com.ooustream.iptv.common

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
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
 *
 * Supports time-of-day theming: orb colors shift based on the hour to create
 * a living atmosphere (warm mornings, cool afternoons, deep evenings, dark nights).
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
        val baseColor: Int,
        val alpha: Int, // 0-255
        var currentColor: Int = baseColor,
        var cx: Float = 0f,
        var cy: Float = 0f,
        var radius: Float = 0f,
        val dxFactor: Float = 1f,
        val dyFactor: Float = 1f,
        val radiusFactor: Float = 1f
    )

    private val orbs = listOf(
        Orb(Color.parseColor("#0A1628"), alpha = 140, dxFactor = 0.3f, dyFactor = 0.2f, radiusFactor = 0.7f),
        Orb(Color.parseColor("#12081F"), alpha = 110, dxFactor = -0.2f, dyFactor = 0.35f, radiusFactor = 0.6f),
        Orb(Color.parseColor("#081A1A"), alpha = 100, dxFactor = 0.25f, dyFactor = -0.15f, radiusFactor = 0.65f)
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
    private var colorAnimator: ValueAnimator? = null
    private var lastTimeBucket: Int = -1

    // Time-of-day color palettes (all very dark for cinematic feel)
    private object TimeTheme {
        // Morning (6-11): warm sunrise
        val MORNING = intArrayOf(
            Color.parseColor("#2D2010"), // warm amber
            Color.parseColor("#2D1420"), // dusty rose
            Color.parseColor("#2D2400")  // burnt gold
        )
        // Afternoon (12-17): cool default
        val AFTERNOON = intArrayOf(
            Color.parseColor("#142848"), // blue
            Color.parseColor("#1E1038"), // purple
            Color.parseColor("#103030")  // teal
        )
        // Evening (18-23): deep cinematic
        val EVENING = intArrayOf(
            Color.parseColor("#1E1038"), // deep purple
            Color.parseColor("#141040"), // indigo
            Color.parseColor("#2D1010")  // dark crimson
        )
        // Night (0-5): near-black minimal
        val NIGHT = intArrayOf(
            Color.parseColor("#0C1020"), // dark navy
            Color.parseColor("#101010"), // near-black
            Color.parseColor("#0A0A10")  // void
        )

        fun forHour(hour: Int): IntArray = when (hour) {
            in 6..11 -> MORNING
            in 12..17 -> AFTERNOON
            in 18..23 -> EVENING
            else -> NIGHT
        }

        fun bucketForHour(hour: Int): Int = when (hour) {
            in 6..11 -> 0
            in 12..17 -> 1
            in 18..23 -> 2
            else -> 3
        }
    }

    /**
     * Shift aurora orb colors based on time of day.
     * Only animates if the time bucket has changed.
     */
    fun setTimeOfDayTheme(hour: Int) {
        val bucket = TimeTheme.bucketForHour(hour)
        if (bucket == lastTimeBucket) return
        lastTimeBucket = bucket

        val palette = TimeTheme.forHour(hour)
        colorAnimator?.cancel()

        val startColors = intArrayOf(orbs[0].currentColor, orbs[1].currentColor, orbs[2].currentColor)
        colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000L
            val evaluator = ArgbEvaluator()
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                for (i in orbs.indices) {
                    orbs[i].currentColor = evaluator.evaluate(fraction, startColors[i], palette[i]) as Int
                }
                // No need to invalidate — the main animation cycle already does it every frame
            }
            start()
        }
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        // Initialize currentColor from baseColor
        orbs.forEach { it.currentColor = it.baseColor }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimation()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        colorAnimator?.cancel()
        colorAnimator = null
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
                    Color.argb(orb.alpha, Color.red(orb.currentColor), Color.green(orb.currentColor), Color.blue(orb.currentColor)),
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
