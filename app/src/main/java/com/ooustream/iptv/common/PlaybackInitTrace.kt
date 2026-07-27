package com.ooustream.iptv.common

import android.os.SystemClock

/**
 * Breadcrumb trail through `OoustreamPlaybackFragment.onViewCreated`.
 *
 * WHY THIS EXISTS — customer `Nawfatla1` (AFTSS, mt8695, 900MB) reported five
 * `NullPointerException at OoustreamPlaybackFragment.onViewCreated` crashes spanning v4.2.3 through
 * v4.2.8. The obfuscated frame could not be resolved to a real expression even after rebuilding an
 * authentic v4.2.8 R8 mapping from the shipped commit.
 *
 * The decode was INTERNALLY INCONSISTENT, which is the whole problem: `obf 1699:1703` resolved to
 * source line 600, i.e. `ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)` inside the SERIES-only
 * binge-overlay `addView` — a statement with no null dereference in it. The stack's TOP frame is
 * `onViewCreated` (no framework frame above it), so the throw came from our own bytecode, yet the
 * attributed line cannot throw. Line attribution inside this ~1400-line method therefore cannot be
 * trusted to name an expression, and guessing from it risks fixing the wrong thing.
 *
 * NOTE for future triage: an earlier version of this comment claimed R8 had merged the ~12
 * byte-identical `addView(x, LayoutParams(...))` statements. That was WRONG — checked against both
 * the v4.2.8 and current mappings, all 12 sites get their own distinct mapping entries. Do not
 * repeat that explanation.
 *
 * So [step] records where init actually got to, and [CrashLogger] prepends it to the saved crash
 * entry — the next customer report names the failing section outright instead of relying on a line
 * number we already know we cannot resolve.
 *
 * Cost is one volatile reference store per step — no allocation, no I/O. Safe on ULTRA_LOW devices.
 * Deliberately NOT written to the diagnostic log per-step: ~30 file writes on every playback start
 * would be real I/O churn on a 900MB stick, and the value is only needed when something crashes.
 */
object PlaybackInitTrace {

    @Volatile private var currentStep: String = "not-started"
    @Volatile private var contentType: String = "?"
    @Volatile private var beganAtMs: Long = 0L
    @Volatile private var runCount: Int = 0

    /** Call at the very top of onViewCreated. */
    fun begin(type: String) {
        contentType = type
        beganAtMs = SystemClock.elapsedRealtime()
        runCount++
        currentStep = "begin"
    }

    /** Mark entry into a named section. Pass a unique literal — see the class doc. */
    fun step(name: String) {
        currentStep = name
    }

    /**
     * Call at the end of onViewCreated. A crash reported afterwards will show `complete`, which is
     * itself the answer: the fault was NOT in view initialisation and the frame was misattributed.
     */
    fun complete() {
        currentStep = "complete"
    }

    /** One line for the crash log. */
    fun snapshot(): String {
        val elapsed = if (beganAtMs == 0L) -1 else SystemClock.elapsedRealtime() - beganAtMs
        return "step=$currentStep type=$contentType elapsedMs=$elapsed runs=$runCount"
    }
}
