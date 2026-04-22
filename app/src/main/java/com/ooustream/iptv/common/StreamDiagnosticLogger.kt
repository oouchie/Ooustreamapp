package com.ooustream.iptv.common

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.util.Log
import com.ooustream.iptv.BuildConfig
import com.ooustream.iptv.data.repository.CredentialStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rolling file logger for stream diagnostics. Captures structured playback events,
 * network state, errors, and device info. The "black box flight recorder" for Ooustream.
 *
 * Logs rotate every 30 minutes or 5MB. Max 3 files retained (90 min history).
 * Customer-facing: exported via "Send Debug Log" in Settings.
 */
@Singleton
class StreamDiagnosticLogger @Inject constructor(
    @ApplicationContext private val context: Context,
    private val credentialStore: CredentialStore
) {
    private val logDir = File(context.filesDir, "diagnostics")
    private var currentLogFile: File? = null
    private var writer: BufferedWriter? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US)

    companion object {
        private const val TAG = "DiagLogger"
        private const val MAX_AGE_MS = 30 * 60 * 1000L   // 30 minutes
        private const val MAX_FILE_SIZE = 5 * 1024 * 1024L // 5MB
        private const val MAX_LOG_FILES = 3
    }

    init {
        logDir.mkdirs()
        rotateIfNeeded()
    }

    // ═══════════════════════════════════════════
    // PUBLIC API — Stream Events
    // ═══════════════════════════════════════════

    fun logStreamStart(channelName: String, url: String, codec: String?,
                       resolution: String?, protocol: String?) {
        write("STREAM", "START",
            "channel=$channelName, codec=$codec, " +
            "resolution=$resolution, protocol=$protocol, " +
            "url=${maskUrl(url)}")
    }

    fun logStreamError(errorCode: Int, errorMessage: String, channelName: String?) {
        write("STREAM", "ERROR",
            "code=$errorCode, msg=$errorMessage, channel=$channelName")
    }

    fun logStreamFreeze(durationMs: Long, channelName: String?,
                        bufferMs: Long, bandwidthBps: Long) {
        write("STREAM", "FREEZE",
            "frozen=${durationMs}ms, channel=$channelName, " +
            "buffer=${bufferMs}ms, bandwidth=${bandwidthBps / 1000}kbps")
    }

    fun logStreamRecovery(method: String, success: Boolean, durationMs: Long) {
        write("STREAM", "RECOVERY",
            "method=$method, success=$success, took=${durationMs}ms")
    }

    // ═══════════════════════════════════════════
    // PUBLIC API — Player Events
    // ═══════════════════════════════════════════

    fun logPlayerState(state: String, channelName: String?) {
        write("PLAYER", "STATE", "state=$state, channel=$channelName")
    }

    fun logPlayerError(exception: Throwable, channelName: String?) {
        write("PLAYER", "ERROR",
            "channel=$channelName, error=${exception.message}, " +
            "type=${exception.javaClass.simpleName}")

        // Walk the cause chain. ExoPlayer wraps the real IO failure inside
        // ExoPlaybackException -> HttpDataSource$... -> java.net.* — the layers
        // below the top are the ones that actually tell us what went wrong.
        var cause: Throwable? = exception.cause
        var depth = 1
        var root: Throwable = exception
        while (cause != null && depth <= 5) {
            write("PLAYER", "CAUSE",
                "depth=$depth, type=${cause.javaClass.simpleName}, " +
                "msg=${cause.message}")

            // Special-case HTTP errors — surface responseCode in its own line
            // so customers and us can grep for STREAM/HTTP.
            val cls = cause.javaClass.name
            if (cls == "androidx.media3.datasource.HttpDataSource\$InvalidResponseCodeException") {
                extractHttpErrorDetails(cause, channelName)
            }

            root = cause
            cause = cause.cause
            depth++
        }

        // Stack frames from the ROOT cause are the useful ones — the top-level
        // ExoPlaybackException frames are just internal dispatch.
        root.stackTrace.take(5).forEach { frame ->
            write("PLAYER", "STACK", "  at $frame")
        }
    }

    private fun extractHttpErrorDetails(httpError: Throwable, channelName: String?) {
        // Reflection to avoid compile-time dependency shape on a specific media3 version.
        try {
            val codeField = httpError.javaClass.getField("responseCode")
            val msgField = runCatching { httpError.javaClass.getField("responseMessage") }.getOrNull()
            val dataSpecField = runCatching { httpError.javaClass.getField("dataSpec") }.getOrNull()

            val code = codeField.getInt(httpError)
            val msg = msgField?.get(httpError) as? String ?: ""
            val dataSpec = dataSpecField?.get(httpError)
            val host = dataSpec?.let {
                runCatching {
                    val uri = it.javaClass.getField("uri").get(it) as? android.net.Uri
                    uri?.host
                }.getOrNull()
            } ?: "unknown"

            write("STREAM", "HTTP",
                "status=$code, statusMsg=$msg, host=$host, channel=$channelName")
        } catch (t: Throwable) {
            // Reflection failed — log the fallback so we still know this was an HTTP error.
            write("STREAM", "HTTP",
                "status=unknown, reflection_failed=${t.javaClass.simpleName}, " +
                "channel=$channelName")
        }
    }

    // ═══════════════════════════════════════════
    // PUBLIC API — Health & Monitoring
    // ═══════════════════════════════════════════

    fun logBufferHealth(bufferMs: Long, bandwidthBps: Long,
                        droppedFrames: Int, renderedFps: Float) {
        write("BUFFER", "HEALTH",
            "buffer=${bufferMs}ms, bw=${bandwidthBps / 1000}kbps, " +
            "dropped=$droppedFrames, fps=${"%.1f".format(renderedFps)}")
    }

    fun logBlackScreen(channelName: String?, decoderInfo: String?,
                       surfaceValid: Boolean, hasVideoTrack: Boolean,
                       audioPlaying: Boolean, bufferMs: Long) {
        write("VIDEO", "BLACK_SCREEN",
            "channel=$channelName, decoder=$decoderInfo, " +
            "surfaceValid=$surfaceValid, hasVideo=$hasVideoTrack, " +
            "audioPlaying=$audioPlaying, buffer=${bufferMs}ms")
    }

    fun logBlackScreenRecovered(durationMs: Long, channelName: String?) {
        write("VIDEO", "BLACK_RECOVERED",
            "was_black_for=${durationMs}ms, channel=$channelName")
    }

    fun logMemory(usedMb: Int, totalMb: Int, freeMb: Int) {
        write("SYSTEM", "MEMORY",
            "used=${usedMb}MB, total=${totalMb}MB, free=${freeMb}MB")
    }

    // ═══════════════════════════════════════════
    // PUBLIC API — Network Events
    // ═══════════════════════════════════════════

    fun logNetworkChange(type: String, connected: Boolean, wifiSignal: Int?) {
        write("NETWORK", "CHANGE",
            "type=$type, connected=$connected, " +
            "wifiSignal=${wifiSignal ?: "N/A"}dBm")
    }

    fun logNetworkCapabilities(downMbps: Int, upMbps: Int, isWifi: Boolean) {
        write("NETWORK", "CAPABILITIES",
            "down=${downMbps}Mbps, up=${upMbps}Mbps, wifi=$isWifi")
    }

    // ═══════════════════════════════════════════
    // PUBLIC API — Decoder & Tracks
    // ═══════════════════════════════════════════

    fun logDecoderInfo(codec: String, isHardware: Boolean, decoderName: String) {
        write("DECODER", "INFO",
            "codec=$codec, hw=$isHardware, name=$decoderName")
    }

    // ═══════════════════════════════════════════
    // PUBLIC API — Generic Events
    // ═══════════════════════════════════════════

    fun logAppEvent(event: String, details: String = "") {
        write("APP", "EVENT", "event=$event $details".trim())
    }

    fun logApiCall(endpoint: String, responseCode: Int, durationMs: Long) {
        write("API", "CALL",
            "endpoint=$endpoint, status=$responseCode, took=${durationMs}ms")
    }

    fun logApiError(endpoint: String, error: String) {
        write("API", "ERROR", "endpoint=$endpoint, error=$error")
    }

    // ═══════════════════════════════════════════
    // EXPORT — Package logs for customer to send
    // ═══════════════════════════════════════════

    fun getLogFiles(): List<File> {
        return logDir.listFiles()
            ?.filter { it.extension == "log" }
            ?.sortedByDescending { it.lastModified() }
            ?.toList()
            ?: emptyList()
    }

    fun exportCombinedLog(): File {
        // Flush current writer before export
        synchronized(this) {
            try { writer?.flush() } catch (_: Exception) {}
        }

        val exportFile = File(context.cacheDir, "ooustream_debug_log.txt")
        exportFile.bufferedWriter().use { out ->
            out.write(buildDeviceSummary())
            out.write("\n\n")

            // Include crash log if available
            val crashLog = CrashLogger.getLastCrash(context)
            if (crashLog != null) {
                out.write("═══ RECENT CRASH LOG ═══\n")
                out.write(crashLog)
                out.write("\n\n")
            }

            // All diagnostic log files (newest first)
            getLogFiles().forEach { logFile ->
                out.write("═══ ${logFile.name} ═══\n")
                try {
                    logFile.bufferedReader().use { reader ->
                        reader.copyTo(out)
                    }
                } catch (_: Exception) {}
                out.write("\n\n")
            }
        }
        return exportFile
    }

    fun clearLogs() {
        synchronized(this) {
            closeWriter()
            logDir.listFiles()?.forEach { it.delete() }
            rotateIfNeeded()
        }
    }

    // ═══════════════════════════════════════════
    // INTERNAL — File management
    // ═══════════════════════════════════════════

    @Synchronized
    private fun write(category: String, level: String, message: String) {
        rotateIfNeeded()
        try {
            val timestamp = dateFormat.format(Date())
            val line = "$timestamp [$category/$level] $message\n"
            writer?.write(line)
            writer?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log", e)
        }
    }

    private fun rotateIfNeeded() {
        val file = currentLogFile
        if (file == null || !file.exists() ||
            file.length() > MAX_FILE_SIZE ||
            System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS
        ) {
            closeWriter()
            cleanOldLogs()
            createNewLog()
        }
    }

    private fun createNewLog() {
        val filename = "ooustream_${fileDateFormat.format(Date())}.log"
        currentLogFile = File(logDir, filename)
        writer = BufferedWriter(FileWriter(currentLogFile, true))
        writeHeader()
    }

    private fun writeHeader() {
        val header = buildString {
            appendLine("═══════════════════════════════════════════════════")
            appendLine("OOUSTREAM DIAGNOSTIC LOG")
            appendLine("═══════════════════════════════════════════════════")
            appendLine("Timestamp: ${Date()}")
            appendLine("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Device: ${Build.BRAND} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Hardware: ${Build.HARDWARE}")
            appendLine("RAM: ${getDeviceRamMb()}MB")
            appendLine("Is TV: ${isTV()}")
            appendLine("User: ${getUsername()}")
            appendLine("Device ID: ${getDeviceId()}")
            appendLine("═══════════════════════════════════════════════════")
            appendLine()
        }
        writer?.write(header)
        writer?.flush()
    }

    private fun cleanOldLogs() {
        logDir.listFiles()
            ?.filter { it.extension == "log" }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_LOG_FILES)
            ?.forEach { it.delete() }
    }

    private fun closeWriter() {
        try { writer?.close() } catch (_: Exception) {}
        writer = null
    }

    private fun maskUrl(url: String): String {
        return try {
            val uri = Uri.parse(url)
            "${uri.scheme}://${uri.host}:${uri.port}/***"
        } catch (_: Exception) { "***" }
    }

    private fun buildDeviceSummary(): String {
        return buildString {
            appendLine("╔═══════════════════════════════════════════════╗")
            appendLine("║     OOUSTREAM DEBUG LOG — CUSTOMER REPORT     ║")
            appendLine("╠═══════════════════════════════════════════════╣")
            appendLine("║ Date: ${Date()}")
            appendLine("║ App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("║ Device: ${Build.BRAND} ${Build.MODEL}")
            appendLine("║ Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("║ Hardware: ${Build.HARDWARE}")
            appendLine("║ RAM: ${getDeviceRamMb()}MB total")
            appendLine("║ RAM Free: ${getAvailableRamMb()}MB available")
            appendLine("║ Storage Free: ${getAvailableStorageMb()}MB")
            appendLine("║ Is TV: ${isTV()}")
            appendLine("║ User: ${getUsername()}")
            appendLine("║ Device ID: ${getDeviceId()}")
            appendLine("╚═══════════════════════════════════════════════╝")
        }
    }

    private fun getDeviceRamMb(): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return (info.totalMem / 1024 / 1024).toInt()
    }

    private fun getAvailableRamMb(): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return (info.availMem / 1024 / 1024).toInt()
    }

    private fun getAvailableStorageMb(): Int {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            (stat.availableBlocksLong * stat.blockSizeLong / 1024 / 1024).toInt()
        } catch (_: Exception) { -1 }
    }

    private fun isTV(): Boolean {
        return context.packageManager.hasSystemFeature("android.software.leanback")
    }

    private fun getUsername(): String {
        return try {
            credentialStore.load()?.username ?: "not_logged_in"
        } catch (_: Exception) { "unknown" }
    }

    private fun getDeviceId(): String {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?: "unknown"
        } catch (_: Exception) { "unknown" }
    }
}
