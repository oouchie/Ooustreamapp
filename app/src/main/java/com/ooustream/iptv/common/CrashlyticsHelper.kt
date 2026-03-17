package com.ooustream.iptv.common

import android.net.Uri
import android.os.Build
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.ooustream.iptv.BuildConfig

/**
 * Wrapper for Firebase Crashlytics custom keys and non-fatal error logging.
 */
object CrashlyticsHelper {

    /**
     * Set user/device context on Crashlytics after login.
     * Every crash report will include this info.
     */
    fun setUserContext(username: String, serverUrl: String) {
        val crashlytics = FirebaseCrashlytics.getInstance()
        crashlytics.setUserId(username.hashCode().toString())
        crashlytics.setCustomKey("device_model", Build.MODEL)
        crashlytics.setCustomKey("device_brand", Build.BRAND)
        crashlytics.setCustomKey("android_version", Build.VERSION.SDK_INT)
        crashlytics.setCustomKey("app_version", BuildConfig.VERSION_NAME)
        crashlytics.setCustomKey("is_tv", isTV())
        crashlytics.setCustomKey("ram_mb", getDeviceRamMb())
        crashlytics.setCustomKey("server_domain",
            try { Uri.parse(serverUrl).host ?: "unknown" } catch (_: Exception) { "unknown" })
    }

    /**
     * Log a non-fatal error to Crashlytics (appears in Firebase Console).
     */
    fun logNonFatal(tag: String, message: String, error: Throwable? = null) {
        val crashlytics = FirebaseCrashlytics.getInstance()
        crashlytics.setCustomKey("error_tag", tag)
        crashlytics.setCustomKey("error_message", message)
        crashlytics.recordException(error ?: RuntimeException("[$tag] $message"))
    }

    /**
     * Log a breadcrumb message (visible in crash timeline).
     */
    fun log(message: String) {
        FirebaseCrashlytics.getInstance().log(message)
    }

    private fun isTV(): Boolean {
        return Build.MODEL.contains("AFT", ignoreCase = true) ||
                Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
    }

    private fun getDeviceRamMb(): Int {
        return try {
            val runtime = Runtime.getRuntime()
            (runtime.maxMemory() / 1024 / 1024).toInt()
        } catch (_: Exception) { -1 }
    }
}
