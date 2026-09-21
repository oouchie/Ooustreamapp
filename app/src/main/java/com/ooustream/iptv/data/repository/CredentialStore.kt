package com.ooustream.iptv.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ooustream.iptv.R
import com.ooustream.iptv.data.model.XtreamCredentials

class CredentialStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "ooustream_credentials",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /**
     * The panel host this build talks to, from `R.string.default_server_url`.
     *
     * The login screen never asks for a server — it passes this same value — so the host is a
     * property of the BUILD, not of the account. That is what makes [load]'s rewrite safe.
     */
    val canonicalServerUrl: String = context.getString(R.string.default_server_url).trimEnd('/')

    fun save(credentials: XtreamCredentials) {
        prefs.edit()
            .putString(KEY_SERVER_URL, credentials.serverUrl)
            .putString(KEY_USERNAME, credentials.username)
            .putString(KEY_PASSWORD, credentials.password)
            .apply()
    }

    /**
     * Loads the saved login, silently moving it onto [canonicalServerUrl] if it was saved
     * against a different host.
     *
     * This is the ONLY choke point for the stored server URL — auto-login, account refresh,
     * every stream URL built by ContentRepository/StreamUrlBuilder, the speed test and the
     * settings screen all read through here. Without this rewrite, changing
     * `default_server_url` would only affect people who log in for the first time afterwards,
     * and every existing install would keep hammering the retired host forever (the provider
     * cutover of 2026-09-21).
     *
     * The rewrite is persisted, not just returned, so the correction survives a restart even if
     * the app is killed before the next successful login.
     */
    fun load(): XtreamCredentials? {
        val serverUrl = prefs.getString(KEY_SERVER_URL, null) ?: return null
        val username = prefs.getString(KEY_USERNAME, null) ?: return null
        val password = prefs.getString(KEY_PASSWORD, null) ?: return null

        if (serverUrl.trimEnd('/') != canonicalServerUrl) {
            val migrated = XtreamCredentials(canonicalServerUrl, username, password)
            save(migrated)
            return migrated
        }
        return XtreamCredentials(serverUrl, username, password)
    }

    /** The host a saved login was last stored against, without applying the [load] rewrite. */
    fun rawSavedServerUrl(): String? = prefs.getString(KEY_SERVER_URL, null)

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
    }
}
