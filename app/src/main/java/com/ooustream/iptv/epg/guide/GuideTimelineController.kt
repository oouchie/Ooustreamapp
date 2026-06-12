package com.ooustream.iptv.epg.guide

/**
 * Single source of truth for the guide's horizontal axis. Every lane and the time header
 * read [windowStartMs]/[windowDurationMs] so one scroll offset keeps all rows in lockstep,
 * and the focused cell is tracked virtually via [focusAnchorMs] (View focus stays on the
 * vertical rows; horizontal "focus" is just a time anchor — classic TV-guide behaviour
 * where UP/DOWN lands on the time-aligned cell in the next row).
 */
class GuideTimelineController {

    /** Visible window width: 2 hours. */
    val windowDurationMs = 2 * 60 * 60_000L

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
}
