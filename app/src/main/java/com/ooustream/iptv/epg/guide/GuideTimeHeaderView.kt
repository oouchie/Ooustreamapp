package com.ooustream.iptv.epg.guide

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Time ruler above the grid: 30-minute slot labels + the gold NOW marker, sharing the
 * lane mapping via the same [GuideTimelineController]. [leadingOffsetPx] reserves the
 * channel-column width so labels line up with the lanes below.
 */
class GuideTimeHeaderView(context: Context) : View(context) {

    var controller: GuideTimelineController? = null
        set(value) {
            field?.removeListener(invalidateListener)
            field = value
            value?.addListener(invalidateListener)
            invalidate()
        }
    var leadingOffsetPx = 0

    private val invalidateListener: () -> Unit = { invalidate() }
    private val density = context.resources.displayMetrics.density
    private val slotMs = 30 * 60_000L
    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        textSize = 12f * density
    }
    private val tickPaint = Paint().apply {
        color = 0x33FFFFFF
        strokeWidth = 1f * density
    }
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFC107.toInt()
        strokeWidth = 1.5f * density
        textSize = 11f * density
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        controller?.removeListener(invalidateListener)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        controller?.addListener(invalidateListener)
    }

    override fun onDraw(canvas: Canvas) {
        val ctrl = controller ?: return
        val laneWidth = width - leadingOffsetPx
        if (laneWidth <= 0) return

        // First slot boundary at/after the window start
        var slot = (ctrl.windowStartMs / slotMs) * slotMs
        if (slot < ctrl.windowStartMs) slot += slotMs
        val baselineY = height / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2f
        while (slot < ctrl.windowEndMs) {
            val x = leadingOffsetPx + ctrl.timeToX(slot, laneWidth)
            canvas.drawLine(x, height * 0.55f, x, height.toFloat(), tickPaint)
            canvas.drawText(timeFormat.format(Date(slot)), x + 6f * density, baselineY, labelPaint)
            slot += slotMs
        }

        // NOW marker
        if (ctrl.nowMs in ctrl.windowStartMs..ctrl.windowEndMs) {
            val x = leadingOffsetPx + ctrl.timeToX(ctrl.nowMs, laneWidth)
            canvas.drawLine(x, 0f, x, height.toFloat(), nowPaint)
        }
    }
}
