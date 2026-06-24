package com.ooustream.iptv.epg.guide

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import com.ooustream.iptv.epg.EpgSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Canvas-drawn program lane for one guide row. No child views — 8 visible rows × ~8 cells
 * would otherwise mean 60+ inflated views and nested-scroll sync; a lane draws its cells
 * from the shared [GuideTimelineController] window in one pass (60fps on a 1GB stick).
 * Horizontal focus is virtual: the lane in the View-focused row draws a gold ring around
 * the cell containing controller.focusAnchorMs.
 */
class GuideProgramLaneView(context: Context) : View(context) {

    var controller: GuideTimelineController? = null
        set(value) {
            field?.removeListener(invalidateListener)
            field = value
            value?.addListener(invalidateListener)
            invalidate()
        }
    var programs: List<GuideProgram> = emptyList()
        set(value) {
            field = value
            // Precompute the cell time-range labels ONCE per data change — formatting in onDraw
            // would allocate a Date + String per cell every frame (GC churn / jank on 1GB sticks).
            rangeLabels = value.map { "${timeFmt.format(Date(it.startMs))} - ${timeFmt.format(Date(it.endMs))}" }
            invalidate()
        }
    private var rangeLabels: List<String> = emptyList()
    var rowFocused = false
        set(value) { if (field != value) { field = value; invalidate() } }
    /** Genre accent (ARGB) — drives the left stripe + faint cell wash. */
    var accentColor: Int = GuideGenrePalette.NEUTRAL
        set(value) {
            if (field == value) return
            field = value
            accentStripePaint.color = value
            accentWashPaint.color = (value and 0x00FFFFFF) or 0x1A000000
            invalidate()
        }

    private val invalidateListener: () -> Unit = { invalidate() }

    private val density = context.resources.displayMetrics.density
    private val cellGapPx = 1.5f * density
    private val cornerPx = 4f * density
    private val textPadPx = 10f * density

    private val cellBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF18181E.toInt() }
    private val cellBgCurrent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF24242E.toInt() }
    private val focusFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1AFFC107 }
    private val focusBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0xFFFFC107.toInt()
    }
    private val nowLine = Paint().apply {
        color = 0xFFFFC107.toInt()
        strokeWidth = 1.5f * density
    }

    private val titleReal = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        textSize = 13f * density
    }
    private val titlePattern = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF90CAF9.toInt()
        textSize = 13f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
    }
    private val titleRule = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x78FFFFFF
        textSize = 13f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
    }
    private val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FFFFFF.toInt()
        textSize = 10.5f * density
    }

    // Genre + progress
    private val accentStripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
    private val accentWashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (accentColor and 0x00FFFFFF) or 0x1A000000
    }
    private val progressTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val progressFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC107.toInt() }
    private val stripeWidthPx = 3f * density
    private val progressHeightPx = 3f * density
    private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())

    private val rect = RectF()

    /** The program currently under the virtual focus anchor (null when row has none). */
    fun focusedProgram(): GuideProgram? {
        val ctrl = controller ?: return null
        return programs.firstOrNull { it.contains(ctrl.focusAnchorMs) }
            ?: programs.firstOrNull { it.startMs >= ctrl.focusAnchorMs }
            ?: programs.lastOrNull()
    }

    /** Program at a tapped x position (phone touch). */
    fun programAtX(x: Float): GuideProgram? {
        val ctrl = controller ?: return null
        val t = ctrl.xToTime(x, width)
        return programs.firstOrNull { it.contains(t) }
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
        if (width <= 0) return
        val winStart = ctrl.windowStartMs
        val winEnd = ctrl.windowEndMs
        val focused = if (rowFocused) focusedProgram() else null
        val twoLine = height >= 56f * density   // room for title + time range

        for (i in programs.indices) {
            val p = programs[i]
            if (p.endMs <= winStart || p.startMs >= winEnd) continue
            val left = ctrl.timeToX(p.startMs, width).coerceAtLeast(0f)
            val right = ctrl.timeToX(p.endMs, width).coerceAtMost(width.toFloat())
            if (right - left < 2f) continue
            rect.set(left + cellGapPx, cellGapPx, right - cellGapPx, height - cellGapPx)

            val isCurrent = p.contains(ctrl.nowMs)
            canvas.drawRoundRect(rect, cornerPx, cornerPx, if (isCurrent) cellBgCurrent else cellBg)
            // Genre wash + solid left accent stripe (at-a-glance color coding)
            canvas.drawRoundRect(rect, cornerPx, cornerPx, accentWashPaint)
            if (rect.width() > stripeWidthPx * 2) {
                canvas.drawRect(rect.left, rect.top, rect.left + stripeWidthPx, rect.bottom, accentStripePaint)
            }
            if (p === focused) {
                canvas.drawRoundRect(rect, cornerPx, cornerPx, focusFill)
                canvas.drawRoundRect(rect, cornerPx, cornerPx, focusBorder)
            }

            val paint = when (p.source) {
                EpgSource.REAL -> titleReal
                EpgSource.PATTERN_CACHE -> titlePattern
                EpgSource.RULE_INFERRED -> titleRule
            }
            val padL = rect.left + textPadPx + stripeWidthPx
            val maxW = rect.right - textPadPx - padL
            if (maxW > 20f) {
                if (twoLine) {
                    val titleY = height * 0.40f - (paint.descent() + paint.ascent()) / 2f
                    val title = TextUtils.ellipsize(p.title, paint, maxW, TextUtils.TruncateAt.END)
                    canvas.drawText(title, 0, title.length, padL, titleY, paint)

                    val range = rangeLabels.getOrNull(i) ?: ""
                    val timeY = height * 0.74f - (timePaint.descent() + timePaint.ascent()) / 2f
                    val rangeText = TextUtils.ellipsize(range, timePaint, maxW, TextUtils.TruncateAt.END)
                    canvas.drawText(rangeText, 0, rangeText.length, padL, timeY, timePaint)
                } else {
                    val y = height / 2f - (paint.descent() + paint.ascent()) / 2f
                    val title = TextUtils.ellipsize(p.title, paint, maxW, TextUtils.TruncateAt.END)
                    canvas.drawText(title, 0, title.length, padL, y, paint)
                }
            }

            // Live progress along the bottom of the currently-airing cell (every row)
            if (isCurrent && p.endMs > p.startMs) {
                val frac = ((ctrl.nowMs - p.startMs).toFloat() / (p.endMs - p.startMs)).coerceIn(0f, 1f)
                val barTop = rect.bottom - progressHeightPx
                canvas.drawRect(rect.left, barTop, rect.right, rect.bottom, progressTrackPaint)
                canvas.drawRect(rect.left, barTop, rect.left + rect.width() * frac, rect.bottom, progressFillPaint)
            }
        }

        // NOW line across the lane
        if (ctrl.nowMs in winStart..winEnd) {
            val x = ctrl.timeToX(ctrl.nowMs, width)
            canvas.drawLine(x, 0f, x, height.toFloat(), nowLine)
        }
    }
}
