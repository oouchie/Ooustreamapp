package com.ooustream.iptv.settings

import android.content.Context

/**
 * Lightweight SharedPreferences accessor for network-layer toggles.
 * Lives outside any Hilt scope so NetworkModule can read it while constructing
 * the singleton OkHttpClient.
 */
object NetworkSettings {
    private const val PREFS = "ooustream_network_prefs"
    private const val KEY_ALLOW_SELF_SIGNED = "allow_self_signed_certs"

    fun allowSelfSignedCerts(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ALLOW_SELF_SIGNED, false)

    fun setAllowSelfSignedCerts(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ALLOW_SELF_SIGNED, enabled)
            .apply()
    }
}
