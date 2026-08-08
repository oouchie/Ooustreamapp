package com.ooustream.iptv.common

import android.content.Context
import com.ooustream.iptv.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Catches uncaught exceptions and saves the stack trace to a file.
 * The default handler still runs so the app crashes normally.
 * Crash logs can be viewed from Settings > Crash Logs.
 */
object CrashLogger {

    private const val CRASH_FILE = "crash_log.txt"
    private const val MAX_CRASHES = 5

    /**
     * Entry separator. Must be used both to split existing entries apart AND to re-prefix each
     * one when rebuilding the file — joining with it as a *separator* silently eats the header
     * off the first entry, which is what produced the headerless `2026-02-21 21:47:37 ═══`
     * lines seen in customer exports.
     */
    private const val DELIM = "═══ CRASH "

    fun install(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                saveCrash(context, throwable)
            } catch (_: Throwable) {
                // Don't crash the crash handler. Throwable, not Exception: the live risk on a
                // 900MB stick is OutOfMemoryError, and letting that escape here would skip the
                // default handler below — losing the Crashlytics report as well as this one.
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun saveCrash(context: Context, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val trace = redactSensitiveData(sw.toString())
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        // Playback-init breadcrumb. A stack frame inside the ~1400-line
        // OoustreamPlaybackFragment.onViewCreated cannot be resolved to an expression from the
        // obfuscated line alone — the v4.2.8 NPE decoded to a line that provably cannot throw — so
        // record which section init had reached instead. `step=complete` means the fault was NOT in
        // view init and the frame was misattributed.
        val breadcrumb = try {
            "Playback init: ${PlaybackInitTrace.snapshot()}\n"
        } catch (_: Throwable) {
            ""
        }
        // Stamp the build into every entry. crash_log.txt is a rolling file that SURVIVES app
        // updates, so an entry carries no version unless we write one — and the report header
        // only shows what is installed at export time. That mismatch has twice sent triage
        // chasing a current build for a crash that actually ran months earlier on an old one.
        val build = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        val entry = "$DELIM$timestamp — v$build ═══\n$breadcrumb${trace}\n"

        val file = File(context.filesDir, CRASH_FILE)
        val existing = if (file.exists()) file.readText() else ""

        // Keep only the last MAX_CRASHES entries. Re-prefix every retained entry with DELIM
        // rather than using it as a join separator, which dropped the first entry's header.
        // This also repairs files written by the previous, buggy version.
        val entries = existing.split(DELIM).filter { it.isNotBlank() }
        val trimmed = entries.takeLast(MAX_CRASHES - 1)
        val newContent = trimmed.joinToString("") { DELIM + it } + entry

        // Write via a temp file + rename so a process death mid-write cannot leave a truncated
        // crash log — the one artifact we most need intact after a crash.
        val temp = File(context.filesDir, "$CRASH_FILE.tmp")
        if (runCatching { temp.writeText(newContent); temp.renameTo(file) }.getOrNull() != true) {
            temp.delete()
            file.writeText(newContent)
        }
    }

    fun getLastCrash(context: Context): String? {
        val file = File(context.filesDir, CRASH_FILE)
        if (!file.exists()) return null
        val content = file.readText().trim()
        return content.ifBlank { null }
    }

    fun hasCrashLog(context: Context): Boolean {
        return getLastCrash(context) != null
    }

    fun clearCrashLog(context: Context) {
        File(context.filesDir, CRASH_FILE).delete()
    }

    /** Redact credentials and stream URLs from stack traces. */
    private fun redactSensitiveData(trace: String): String {
        return trace
            .replace(Regex("""(https?://[^\s]*?)/live/[^\s/]+/[^\s/]+/"""), "$1/live/***/***/" )
            .replace(Regex("""(https?://[^\s]*?)/movie/[^\s/]+/[^\s/]+/"""), "$1/movie/***/***/" )
            .replace(Regex("""(https?://[^\s]*?)/series/[^\s/]+/[^\s/]+/"""), "$1/series/***/***/" )
            .replace(Regex("""password=[^\s&]+"""), "password=***")
            .replace(Regex("""username=[^\s&]+"""), "username=***")
    }
}
