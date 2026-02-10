package com.ooustream.iptv.account

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Custom arc gauge view that visualizes active connections vs max connections.
 * Draws a 270-degree arc (open at the bottom) with a foreground sweep
 * proportional to usage. Color changes based on utilization:
 * - Cyan (<60%): healthy
 * - Yellow (60-89%): moderate
 * - Red (90%+): near capacity
 */
class ConnectionGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var activeConnections = 0
    private var maxConnections = 1

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1A2332.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
    }

    private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_CYAN
        style = Paint.Style.STROKE
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
    }

    private val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 40f
        isFakeBoldText = true
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9CA3AF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 16f
    }

    private val arcRect = RectF()

    fun setConnections(active: Int, max: Int) {
        activeConnections = active
        maxConnections = max.coerceAtLeast(1)
        // Color based on usage ratio
        val ratio = active.toFloat() / maxConnections
        fgPaint.color = when {
            ratio >= 0.9f -> COLOR_RED
            ratio >= 0.6f -> COLOR_YELLOW
            else -> COLOR_CYAN
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Prefer square measurement
        val size = minOf(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec)
        )
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val padding = bgPaint.strokeWidth + 4f
        val radius = minOf(cx, cy) - padding

        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // Background arc (270 degrees, open at bottom)
        canvas.drawArc(arcRect, 135f, 270f, false, bgPaint)

        // Foreground arc proportional to usage
        if (activeConnections > 0) {
            val sweepAngle = 270f * (activeConnections.toFloat() / maxConnections)
            canvas.drawArc(arcRect, 135f, sweepAngle, false, fgPaint)
        }

        // Center count text
        canvas.drawText(
            "$activeConnections/$maxConnections",
            cx,
            cy + countPaint.textSize / 3f,
            countPaint
        )

        // Label below count
        canvas.drawText(
            "active",
            cx,
            cy + countPaint.textSize / 3f + labelPaint.textSize + 8f,
            labelPaint
        )
    }

    companion object {
        private const val COLOR_CYAN = 0xFF00B4D8.toInt()
        private const val COLOR_YELLOW = 0xFFF59E0B.toInt()
        private const val COLOR_RED = 0xFFEF4444.toInt()
    }
}
