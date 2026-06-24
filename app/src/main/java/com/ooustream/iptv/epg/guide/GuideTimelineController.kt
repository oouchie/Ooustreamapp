package com.ooustream.iptv.epg.guide

/**
 * Single source of truth for the guide's horizontal axis. Every lane and the time header
 * read [windowStartMs]/[windowDurationMs] so one scroll offset keeps all rows in lockstep,
 * and the focused cell is tracked virtually via [focusAnchorMs] (View focus stays on the
 * vertical rows; horizontal "focus" is just a time anchor — classic TV-guide behaviour
 * where UP/DOWN lands on the time-aligned cell in the next row).
 */
class GuideTimelineController {

    /** Visible window width, driven by the Hours selector (default 2h). */
    var windowDurationMs = DEFAULT_WINDOW_MS
        private set

    var horizonStartMs = 0L
        private set
    var horizonEndMs = 0L
        private set

    var windowStartMs = 0L
        private set
    val windowEndMs: Long get() = windowStartMs + windowDurationMs

    var nowMs = System.currentTimeMillis()
        private set

    /** The time the focused cell is resolved against (kept across UP/DOWN row moves). */
    var focusAnchorMs = 0L

    private val listeners = mutableListOf<() -> Unit>()

    fun setHorizon(startMs: Long, endMs: Long) {
        horizonStartMs = startMs
        horizonEndMs = endMs
        windowStartMs = startMs
        focusAnchorMs = nowMs.coerceIn(startMs, endMs - 1)
        notifyChanged()
    }

    fun tickNow() {
        nowMs = System.currentTimeMillis()
        notifyChanged()
    }

    /** Scroll the shared window, clamped to the horizon. Returns true if it moved. */
    fun scrollTo(startMs: Long): Boolean {
        val clamped = startMs.coerceIn(
            horizonStartMs,
            (horizonEndMs - windowDurationMs).coerceAtLeast(horizonStartMs)
        )
        if (clamped == windowStartMs) return false
        windowStartMs = clamped
        notifyChanged()
        return true
    }

    fun pageBy(deltaMs: Long): Boolean {
        val moved = scrollTo(windowStartMs + deltaMs)
        if (moved) {
            focusAnchorMs = (focusAnchorMs + deltaMs).coerceIn(horizonStartMs, horizonEndMs - 1)
        }
        return moved
    }

    /**
     * Change the visible window width (Hours selector). Re-clamps the window start so the new
     * window still fits the horizon and keeps the focused cell on screen. No-op if unchanged.
     */
    fun setWindowDuration(durationMs: Long) {
        if (durationMs <= 0 || durationMs == windowDurationMs) return
        windowDurationMs = durationMs
        windowStartMs = windowStartMs.coerceIn(
            horizonStartMs,
            (horizonEndMs - windowDurationMs).coerceAtLeast(horizonStartMs)
        )
        ensureAnchorVisible()
        notifyChanged()
    }

    /** Keep the focused cell comfortably visible: scroll if its anchor leaves the middle 80%. */
    fun ensureAnchorVisible() {
        val margin = windowDurationMs / 10
        if (focusAnchorMs < windowStartMs + margin) {
            scrollTo(focusAnchorMs - margin)
        } else if (focusAnchorMs > windowEndMs - margin) {
            scrollTo(focusAnchorMs - windowDurationMs + margin * 2)
        }
    }

    fun timeToX(timeMs: Long, laneWidthPx: Int): Float =
        (timeMs - windowStartMs).toFloat() / windowDurationMs * laneWidthPx

    fun xToTime(x: Float, laneWidthPx: Int): Long =
        windowStartMs + (x / laneWidthPx * windowDurationMs).toLong()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    fun notifyChanged() { listeners.toList().forEach { it() } }

    companion object {
        const val DEFAULT_WINDOW_MS = 2 * 60 * 60_000L
        val WINDOW_OPTIONS_MS = longArrayOf(
            2 * 60 * 60_000L,   // 2h
            4 * 60 * 60_000L,   // 4h
            8 * 60 * 60_000L,   // 8h
        )
    }
}
