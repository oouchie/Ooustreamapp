package com.ooustream.iptv.common

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

class FocusBracketDrawable(
    private val bracketColor: Int = Color.parseColor("#FFC107"),
    private val glowColor: Int = Color.parseColor("#5500B4D8"),
    private val bracketWidth: Float = 4f.dp,
    private val bracketLength: Float = 24f.dp,
    private val bracketOffset: Float = 6f.dp,
    private val withGlow: Boolean = true
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = bracketColor
        style = Paint.Style.STROKE
        strokeWidth = bracketWidth
        strokeCap = Paint.Cap.SQUARE
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = glowColor
        style = Paint.Style.STROKE
        strokeWidth = 8f.dp
        strokeCap = Paint.Cap.SQUARE
        maskFilter = BlurMaskFilter(12f.dp, BlurMaskFilter.Blur.NORMAL)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val left = b.left.toFloat() - bracketOffset
        val top = b.top.toFloat() - bracketOffset
        val right = b.right.toFloat() + bracketOffset
        val bottom = b.bottom.toFloat() + bracketOffset
        val len = bracketLength

        // Draw glow brackets first (underneath) for a premium layered effect
        if (withGlow) {
            drawBrackets(canvas, left, top, right, bottom, len, glowPaint)
        }

        // Draw sharp brackets on top
        drawBrackets(canvas, left, top, right, bottom, len, paint)
    }

    private fun drawBrackets(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        len: Float,
        p: Paint
    ) {
        // Top-left corner
        canvas.drawLine(left, top, left + len, top, p)
        canvas.drawLine(left, top, left, top + len, p)

        // Top-right corner
        canvas.drawLine(right - len, top, right, top, p)
        canvas.drawLine(right, top, right, top + len, p)

        // Bottom-left corner
        canvas.drawLine(left, bottom, left + len, bottom, p)
        canvas.drawLine(left, bottom - len, left, bottom, p)

        // Bottom-right corner
        canvas.drawLine(right - len, bottom, right, bottom, p)
        canvas.drawLine(right, bottom - len, right, bottom, p)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        if (withGlow) glowPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        if (withGlow) glowPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
