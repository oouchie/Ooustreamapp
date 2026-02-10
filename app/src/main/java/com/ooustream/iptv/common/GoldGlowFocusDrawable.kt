package com.ooustream.iptv.common

import android.graphics.*
import android.graphics.drawable.Drawable

class GoldGlowFocusDrawable : Drawable() {

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f.dp
        color = Color.parseColor("#FFC107")
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f.dp
        color = Color.parseColor("#4DFFC107")
        maskFilter = BlurMaskFilter(8f.dp, BlurMaskFilter.Blur.OUTER)
    }

    private val cornerRadius = 12f.dp

    override fun draw(canvas: Canvas) {
        val rect = RectF(bounds)
        val inset = 4f.dp
        rect.inset(inset, inset)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, glowPaint)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, strokePaint)
    }

    override fun setAlpha(alpha: Int) {
        strokePaint.alpha = alpha
        glowPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        strokePaint.colorFilter = colorFilter
        glowPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
