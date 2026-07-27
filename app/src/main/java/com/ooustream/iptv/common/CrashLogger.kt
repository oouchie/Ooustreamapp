package com.ooustream.iptv.common

import android.content.Context
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

    fun install(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                saveCrash(context, throwable)
            } catch (_: Exception) {
                // Don't crash the crash handler
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
        val entry = "═══ CRASH $timestamp ═══\n$breadcrumb${trace}\n"

        val file = File(context.filesDir, CRASH_FILE)
        val existing = if (file.exists()) file.readText() else ""

        // Keep only last MAX_CRASHES entries
        val entries = existing.split("═══ CRASH ").filter { it.isNotBlank() }
        val trimmed = entries.takeLast(MAX_CRASHES - 1)
        val newContent = trimmed.joinToString("═══ CRASH ") { it } + entry

        file.writeText(newContent)
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
