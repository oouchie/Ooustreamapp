package com.ooustream.iptv.common

import android.os.SystemClock

/**
 * Breadcrumb trail through `OoustreamPlaybackFragment.onViewCreated`.
 *
 * WHY THIS EXISTS — customer `Nawfatla1` (AFTSS, mt8695, 900MB) reported five
 * `NullPointerException at OoustreamPlaybackFragment.onViewCreated` crashes spanning v4.2.3 through
 * v4.2.8. The obfuscated frame could not be resolved to a real expression even with an authentic
 * rebuilt R8 mapping: `onViewCreated` is ~1400 lines and contains ~10 BYTE-IDENTICAL
 * `(view as? ViewGroup)?.addView(x, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))` statements,
 * which R8 merges — so the decoded line pointed at a `LayoutParams` constructor that cannot throw.
 * We knew the method and could not narrow it further from the stack alone.
 *
 * Two effects, both deliberate:
 *  1. [step] records where init got to, and [CrashLogger] appends it to the saved crash entry — so
 *     the NEXT customer report names the failing section outright.
 *  2. Each call site passes a DISTINCT string constant, which makes those previously-identical
 *     `addView` blocks structurally different. R8 can no longer merge them, so line attribution in
 *     future stacks is accurate too. That is a fix for the diagnosis problem, not just a workaround.
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
