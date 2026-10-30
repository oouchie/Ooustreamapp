package com.ooustream.iptv.player

/**
 * Verdict for the mt8695 hardware-first 4K trial (see [com.ooustream.iptv.common.HardwareFirst4k]).
 *
 * The hardware decoder is being asked for a size it doesn't declare, so the only honest test is
 * whether it keeps up. The playback fragment feeds one [Sample] per poll; this turns them into a
 * verdict. No Android types on purpose: this is the part with the judgement in it, and it runs on
 * the JVM.
 *
 * Only time when the DECODER owes frames counts against it:
 *  - READY and playing with the clock moving (a frozen clock is an audio problem, not video);
 *  - BUFFERING before the decoder's first frame, with data queued for it;
 *  - BUFFERING after that with the loader stopped. The low-memory load control stops loading only
 *    at its byte cap, so a full buffer that isn't draining means a decoder that isn't consuming
 *    (when the video decoder hangs, audio plays out and the video queue holds the whole cap).
 * Starvation, pauses and seeks are not held against it.
 *
 * Fails after ~5s of owed time: no new frame for [NO_PICTURE_MS] ([Verdict.NO_FIRST_FRAME] if
 * none ever came out, [Verdict.FROZEN] if the picture stopped), or [FAIL_STREAK] windows in a row
 * (or [FAIL_TOTAL] in all) below [minFps] while playing. Passes after [PASS_WINDOWS] smooth windows.
 */
class HardwareTrialJudge(contentFps: Float) {

    enum class Verdict { PASSED, NO_FIRST_FRAME, FROZEN, TOO_SLOW }

    class Sample(
        val nowMs: Long,
        val renderedFrames: Int,
        val positionMs: Long,
        val isReady: Boolean,
        val isBuffering: Boolean,
        val isPlaying: Boolean,
        val playWhenReady: Boolean,
        val isLoading: Boolean,
        val bufferedAheadMs: Long,
    )

    /** A window is smooth at 3/4 of the title's frame rate or better, and never below 12fps. */
    val minFps: Float =
        maxOf(MIN_FPS_FLOOR, SMOOTH_FRACTION * (if (contentFps > 0f) contentFps else ASSUMED_FPS))

    private var last: Sample? = null
    private var everRendered = false
    private var noFrameOwedMs = 0L
    private var windowMs = 0L
    private var windowFrames = 0
    private var badStreak = 0
    private var goodWindows = 0
    private var badWindows = 0
    private var lastWindowFps = -1f

    fun onSample(s: Sample): Verdict? {
        val prev = last
        last = s
        if (s.renderedFrames > 0) everRendered = true
        if (prev == null) return null
        val dt = s.nowMs - prev.nowMs
        val dFrames = s.renderedFrames - prev.renderedFrames
        if (dt <= 0 || dFrames < 0) {
            // Counters went backwards: the renderer was re-enabled with a new decoder, which gets
            // its own time to put out a first frame.
            noFrameOwedMs = 0
            windowMs = 0
            windowFrames = 0
            return null
        }

        // No picture: the decoder has what it needs and isn't putting frames out.
        if (dFrames > 0) {
            noFrameOwedMs = 0
        } else if (waitingOnDecoder(prev, s, dt)) {
            noFrameOwedMs += dt
            if (noFrameOwedMs >= NO_PICTURE_MS) {
                return if (everRendered) Verdict.FROZEN else Verdict.NO_FIRST_FRAME
            }
        }

        // Smoothness: measured only while playing.
        if (!playing(prev, s, dt)) {
            windowMs = 0
            windowFrames = 0
            return null
        }
        windowMs += dt
        windowFrames += dFrames
        if (windowMs < WINDOW_MS) return null

        val fps = windowFrames * 1000f / windowMs
        lastWindowFps = fps
        windowMs = 0
        windowFrames = 0
        if (fps < minFps) {
            badStreak++
            badWindows++
        } else {
            badStreak = 0
            goodWindows++
        }
        return when {
            badStreak >= FAIL_STREAK || badWindows >= FAIL_TOTAL -> Verdict.TOO_SLOW
            goodWindows >= PASS_WINDOWS -> Verdict.PASSED
            else -> null
        }
    }

    /** One line for the diagnostic log. */
    fun summary(): String =
        "minFps=%.1f, lastFps=%.1f, good=%d, bad=%d, noFrameMs=%d"
            .format(minFps, lastWindowFps, goodWindows, badWindows, noFrameOwedMs)

    private fun waitingOnDecoder(prev: Sample, s: Sample, dt: Long): Boolean {
        if (!s.playWhenReady || isSeek(prev, s, dt)) return false
        if (s.isReady) return clockMoving(prev, s, dt)
        if (!s.isBuffering) return false
        // Counters restart at 0 for each decoder, so 0 = this decoder hasn't put out a frame yet.
        // Before connecting the loader may not be running either, so require data actually queued.
        return if (s.renderedFrames == 0) {
            s.bufferedAheadMs >= DATA_PRESENT_MS &&
                (!s.isLoading || s.bufferedAheadMs >= DATA_PLENTY_MS)
        } else {
            !s.isLoading
        }
    }

    private fun playing(prev: Sample, s: Sample, dt: Long): Boolean =
        s.playWhenReady && s.isReady && !isSeek(prev, s, dt) && clockMoving(prev, s, dt)

    private fun clockMoving(prev: Sample, s: Sample, dt: Long): Boolean =
        s.isPlaying && s.positionMs - prev.positionMs >= dt / 4

    private fun isSeek(prev: Sample, s: Sample, dt: Long): Boolean {
        val dPos = s.positionMs - prev.positionMs
        return dPos < 0 || dPos > dt + SEEK_JUMP_MS
    }

    companion object {
        const val WINDOW_MS = 1_500L
        const val FAIL_STREAK = 3
        const val FAIL_TOTAL = 4
        const val PASS_WINDOWS = 6
        const val NO_PICTURE_MS = 5_000L
        private const val MIN_FPS_FLOOR = 12f
        private const val SMOOTH_FRACTION = 0.75f
        private const val ASSUMED_FPS = 24f
        private const val SEEK_JUMP_MS = 2_000L
        // Queued for a decoder that has yet to put out a frame: at least this much with the loader
        // idle (the 5.2.2 20MB cap holds only ~2-3s of 4K), or this much even while still loading.
        private const val DATA_PRESENT_MS = 1_000L
        private const val DATA_PLENTY_MS = 4_000L
    }
}
