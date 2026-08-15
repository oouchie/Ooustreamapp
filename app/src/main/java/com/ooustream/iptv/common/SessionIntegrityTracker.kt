package com.ooustream.iptv.common

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.ooustream.iptv.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Answers the one question [CrashLogger] structurally cannot: **why did the app disappear
 * last time?**
 *
 * `CrashLogger` hooks `Thread.setDefaultUncaughtExceptionHandler`, so it only ever sees an
 * uncaught JVM `Throwable`. The three things that actually kill this app in the field are all
 * invisible to it:
 *
 *  - **ANR** — there is no `Throwable`; the process is killed after the input timeout.
 *    Confirmed as the real cause of "the app keeps crashing" twice (v4.2.7, v4.2.10).
 *  - **Native crash** — the process dies inside native code (FFmpeg / MediaCodec) and no Java
 *    frame ever runs. We shipped a fix for one in v4.2.4.
 *  - **Low-memory kill** — `SIGKILL`, uncatchable by definition. Cause of the 4K binge
 *    "crashes" in v4.2.8.
 *
 * So a debug export could say "no crashes" while the customer was watching the app vanish
 * repeatedly. This class closes that gap with two independent mechanisms:
 *
 *  1. **A liveness marker that works on every API level.** A flag is set when the process
 *     starts and cleared only on a deliberate exit. If it is still set at the next launch, the
 *     previous process died without unwinding — and we log what it was doing at the time.
 *     This is the one that covers the actual problem fleet: AFTSS / AFTDCT31 are **API 28**.
 *  2. **[ApplicationExitInfo] on API 30+**, which is the system's own authoritative record of
 *     *why* a process died, including the ANR trace. Strictly better where available, but it
 *     does not exist below API 30 — hence mechanism 1.
 *
 * Foreground state is tracked separately on purpose: Android evicting a **backgrounded**
 * process is normal housekeeping, not a defect. Only a death while the app was on screen is
 * reported as [EVENT_UNCLEAN]; a background eviction is logged quietly as [EVENT_BACKGROUND]
 * so it can be told apart at triage time instead of inflating the crash count.
 */
object SessionIntegrityTracker {

    private const val PREFS = "session_integrity"

    private const val KEY_OPEN = "session_open"
    private const val KEY_FOREGROUND = "was_foreground"
    private const val KEY_SCREEN = "last_screen"
    private const val KEY_STARTED_AT = "started_at"
    private const val KEY_VERSION = "version"
    private const val KEY_LAST_EXIT_TS = "last_exit_ts"

    /**
     * Wall-clock of the last boot (`currentTimeMillis - elapsedRealtime`). SharedPreferences
     * survive a reboot, so without this a power cut or a stick reboot while the app is on screen
     * is indistinguishable from a real kill — and Fire TV sticks powered from a TV USB port lose
     * power constantly. That would be the single largest source of phantom crash reports.
     */
    private const val KEY_BOOT = "boot_epoch"

    private const val EVENT_UNCLEAN = "UNCLEAN_SHUTDOWN"
    private const val EVENT_BACKGROUND = "BACKGROUND_EVICTION"
    private const val EVENT_REBOOT = "SESSION_ENDED_BY_REBOOT"
    private const val EVENT_EXIT = "PROCESS_EXIT"

    /**
     * Exit reasons worth copying into the persistent crash log — the ones that mean something
     * went wrong. The everyday reasons (USER_REQUESTED, USER_STOPPED, EXIT_SELF, OTHER,
     * DEPENDENCY_DIED, and the routine reclaim ones) are left in the diagnostic log only, or
     * they would evict the entries we actually came to read. CRASH_JAVA is absent on purpose:
     * the uncaught handler already recorded it with a full stack trace.
     */
    private val PERSISTED_EXIT_REASONS = setOf(
        "ANR", "CRASH_NATIVE", "LOW_MEMORY", "EXCESSIVE_RESOURCES", "SIGNALED", "INIT_FAILURE"
    )

    /** Clocks drift a little across a boot; anything under this is the same boot. */
    private const val BOOT_EPOCH_TOLERANCE_MS = 10_000L

    /** Cap the ANR trace we copy into the report — these can be hundreds of KB. */
    private const val MAX_TRACE_LINES = 40

    /** How many historical exits to ask the system for. */
    private const val EXIT_HISTORY = 10

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Volatile private var prefs: SharedPreferences? = null

    /** Number of started activities — >0 means we are on screen. */
    @Volatile private var startedCount = 0

    /** Best-effort name of the screen currently in front of the user. */
    @Volatile private var currentScreen: String = "startup"

    /**
     * Call once from `Application.onCreate`.
     *
     * The SharedPreferences read is synchronous and deliberate: it must happen before any
     * lifecycle callback can overwrite the previous session's marker. It is a single small
     * file and this runs once per process. Everything expensive — the diagnostic file writes
     * and the [ApplicationExitInfo] binder call — is pushed to a background thread, because
     * this executes on the main thread during cold start and that is exactly the budget we are
     * trying to protect.
     */
    fun install(app: Application, logger: StreamDiagnosticLogger) {
        // Diagnostics must never be the reason the app fails to start — so the guard wraps the
        // WHOLE body, not just the worker. Under the cold-start memory pressure this feature
        // exists to investigate, even Thread.start() can throw (OutOfMemoryError:
        // pthread_create failed), and an uncaught throw from Application.onCreate kills the
        // process before any UI exists — on every launch, with no in-app path to an update.
        runCatching {
            val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs = p

            // Snapshot the previous session BEFORE a lifecycle callback can overwrite it.
            val previousOpen = p.getBoolean(KEY_OPEN, false)
            val previousForeground = p.getBoolean(KEY_FOREGROUND, false)
            val previousScreen = p.getString(KEY_SCREEN, null)
            val previousStartedAt = p.getLong(KEY_STARTED_AT, 0L)
            val previousVersion = p.getString(KEY_VERSION, null)
            val previousBoot = p.getLong(KEY_BOOT, 0L)

            // NOTE: the session is armed in onActivityStarted, NOT here. Arming per-process
            // would be wrong twice over: (1) a headless WorkManager wake (the 15-min cast
            // backfill) runs Application.onCreate with no Activity ever created, so it would
            // manufacture a BACKGROUND_EVICTION for a "session" that never existed, and those
            // would swamp every export; (2) after a deliberate exit the process stays CACHED,
            // so a warm relaunch never re-runs Application.onCreate — the marker would stay
            // clear and the retry-after-a-bad-experience session, the one most likely to die,
            // would be the one session we could not see.
            app.registerActivityLifecycleCallbacks(lifecycleCallbacks)

            Thread({
                // Never compete with the UI thread during cold start — the exact window whose
                // ANRs this feature is meant to record.
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                try {
                    if (previousOpen) {
                        reportUnfinishedSession(
                            app, logger,
                            wasForeground = previousForeground,
                            screen = previousScreen,
                            startedAt = previousStartedAt,
                            version = previousVersion,
                            bootEpoch = previousBoot
                        )
                    }
                } catch (_: Throwable) {
                }
                try {
                    reportSystemExitReasons(app, logger, p)
                } catch (_: Throwable) {
                }
            }, "session-integrity").start()
        }
    }

    /** Wall-clock time of the last boot; stable within a boot, jumps across one. */
    private fun bootEpoch(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    /**
     * The previous process set the marker and never cleared it, so it did not exit through any
     * path we control. That is an ANR, a native crash, a low-memory kill, or a force-stop —
     * none of which reach [CrashLogger].
     */
    private fun reportUnfinishedSession(
        context: Context,
        logger: StreamDiagnosticLogger,
        wasForeground: Boolean,
        screen: String?,
        startedAt: Long,
        version: String?,
        bootEpoch: Long
    ) {
        val lived = if (startedAt > 0) {
            "${(System.currentTimeMillis() - startedAt) / 1000}s"
        } else {
            "unknown"
        }
        val details = buildString {
            append("screen=").append(screen ?: "unknown")
            append(", foreground=").append(wasForeground)
            append(", sessionLasted=").append(lived)
            append(", version=").append(version ?: "unknown")
            append(", ").append(memorySnapshot(context))
        }
        // The device rebooted (or lost power) between the two sessions, so the process did not
        // "die" in any sense we should report — the marker simply outlived its boot. Without
        // this check a customer yanking the power mid-movie looks byte-identical to a real
        // low-memory kill during playback, which would poison exactly the signal we came for.
        val rebooted = bootEpoch > 0 &&
            kotlin.math.abs(bootEpoch() - bootEpoch) > BOOT_EPOCH_TOLERANCE_MS
        val event = when {
            rebooted -> EVENT_REBOOT
            // A death while backgrounded is ordinary Android reclaim, not a defect — keep the
            // two apart so triage is not chasing normal behaviour.
            wasForeground -> EVENT_UNCLEAN
            else -> EVENT_BACKGROUND
        }
        logger.logAppEvent(event, details)
        // Mirror the real deaths into the PERSISTENT crash log. logAppEvent alone was not enough:
        // it writes to the rotating diagnostic log, and this record is emitted at the start of
        // the session AFTER the death — so a customer who reopens the app and keeps watching
        // rotates it away before exporting. Only EVENT_UNCLEAN qualifies; a reboot and a
        // backgrounded eviction are both normal and would bury the genuine entries.
        if (event == EVENT_UNCLEAN) {
            CrashLogger.recordEvent(context, EVENT_UNCLEAN, details)
        }
    }

    /**
     * The system's own record of why our previous processes died. Only exists on API 30+, and
     * our worst-affected devices (AFTSS, AFTDCT31) are API 28 — which is precisely why the
     * marker above exists as well. Deduped by timestamp so a report is not re-logged on every
     * launch forever.
     */
    private fun reportSystemExitReasons(
        context: Context,
        logger: StreamDiagnosticLogger,
        p: SharedPreferences
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return

        val lastSeen = p.getLong(KEY_LAST_EXIT_TS, 0L)
        val records = am.getHistoricalProcessExitReasons(context.packageName, 0, EXIT_HISTORY)
        if (records.isEmpty()) return

        var newest = lastSeen
        records.sortedBy { it.timestamp }.forEach { info ->
            if (info.timestamp <= lastSeen) return@forEach
            if (info.timestamp > newest) newest = info.timestamp

            val reason = reasonName(info.reason)
            val details = "reason=$reason, at=${stamp.format(Date(info.timestamp))}, " +
                "status=${info.status}, importance=${info.importance}, " +
                "pss=${info.pss / 1024}MB, rss=${info.rss / 1024}MB, " +
                "desc=${info.description ?: "-"}"
            logger.logAppEvent(EVENT_EXIT, details)
            // Same rotation problem as above — mirror to the persistent crash log, but only the
            // reasons that indicate a defect. CRASH_JAVA is deliberately excluded: the uncaught
            // handler already wrote that entry, with a stack trace, and duplicating it would
            // evict a real trace from the capped file. The ANR thread dump stays in the
            // diagnostic log; this entry is the one-line record that has to survive.
            if (reason in PERSISTED_EXIT_REASONS) {
                CrashLogger.recordEvent(context, "PROCESS_EXIT", details)
            }
            appendTraceIfPresent(info, logger)
        }

        if (newest > lastSeen) p.edit().putLong(KEY_LAST_EXIT_TS, newest).apply()
    }

    /**
     * For an ANR the system hands us the actual thread dump — the single most useful artifact
     * for this whole class of report. Capped, and only for the reasons that carry one.
     */
    private fun appendTraceIfPresent(info: ApplicationExitInfo, logger: StreamDiagnosticLogger) {
        if (info.reason != ApplicationExitInfo.REASON_ANR) return
        try {
            info.traceInputStream?.bufferedReader()?.use { reader ->
                // ONE write, not one per line. Every logAppEvent takes the logger's monitor and
                // flushes to disk, and the main thread contends for that same monitor — 40
                // separate flushes during cold start is exactly the kind of I/O storm this
                // feature is supposed to be diagnosing, not causing.
                val trace = reader.lineSequence()
                    .take(MAX_TRACE_LINES)
                    .joinToString("\n") { it.trimEnd() }
                if (trace.isNotBlank()) logger.logAppEvent("PROCESS_EXIT_TRACE", "\n$trace")
            }
        } catch (_: Throwable) {
            // Trace is a bonus; its absence must not lose the exit record above.
        }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH_JAVA"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCES"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "UNKNOWN($reason)"
    }

    private fun memorySnapshot(context: Context): String = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        "freeRam=${mi.availMem / 1024 / 1024}MB, lowMemory=${mi.lowMemory}"
    } catch (_: Throwable) {
        "freeRam=unknown"
    }

    // ═══════════════════════════════════════════
    // Lifecycle — one registration, no per-screen edits anywhere else
    // ═══════════════════════════════════════════

    private val fragmentCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            currentScreen = f.javaClass.simpleName
            prefs?.edit()?.putString(KEY_SCREEN, currentScreen)?.apply()
        }
    }

    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, saved: Bundle?) {
            // Registering here rather than editing every fragment keeps screen tracking to a
            // single place; `true` makes it recursive into child fragment managers.
            if (activity is FragmentActivity) {
                activity.supportFragmentManager
                    .registerFragmentLifecycleCallbacks(fragmentCallbacks, true)
            }
        }

        override fun onActivityStarted(activity: Activity) {
            startedCount++
            if (startedCount == 1) {
                // Arm the session HERE. This is the first moment a real user session provably
                // exists — a headless WorkManager process never reaches it — and it re-arms on
                // a warm relaunch into a cached process, where Application.onCreate does not
                // run again. KEY_SCREEN is reset because onFragmentResumed will set the real
                // name microseconds later; leaving the previous session's value would attribute
                // a death to the wrong screen.
                currentScreen = "startup"
                prefs?.edit()
                    ?.putBoolean(KEY_OPEN, true)
                    ?.putBoolean(KEY_FOREGROUND, true)
                    ?.putString(KEY_SCREEN, currentScreen)
                    ?.putLong(KEY_STARTED_AT, System.currentTimeMillis())
                    ?.putLong(KEY_BOOT, bootEpoch())
                    ?.putString(
                        KEY_VERSION,
                        "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                    )
                    ?.apply()
            }
        }

        override fun onActivityStopped(activity: Activity) {
            startedCount = (startedCount - 1).coerceAtLeast(0)
            if (startedCount == 0) {
                prefs?.edit()?.putBoolean(KEY_FOREGROUND, false)?.apply()
            }
        }

        override fun onActivityDestroyed(activity: Activity) {
            // The user actually left. Clear the marker so this does not read as a death.
            // commit() rather than apply(): the process may be gone moments from now, and a
            // lost write here manufactures a phantom UNCLEAN_SHUTDOWN in the next report.
            if (activity.isFinishing && startedCount == 0) {
                prefs?.edit()?.putBoolean(KEY_OPEN, false)?.commit()
            }
        }

        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    }
}
