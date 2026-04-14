package com.ooustream.iptv.parental

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ParentalControlManager @Inject constructor(
    @ApplicationContext context: Context
) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val _isEnabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _isLocked = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    /**
     * Whether a PIN has been configured. When no PIN exists,
     * the user should be sent to Setup flow instead of Locked.
     */
    fun hasPinSet(): Boolean {
        return prefs.getString(KEY_PIN_HASH, null) != null
    }

    /**
     * Hash and store the PIN, then enable parental controls and lock.
     */
    fun setPin(pin: String) {
        val hash = hashPin(pin)
        prefs.edit()
            .putString(KEY_PIN_HASH, hash)
            .putBoolean(KEY_ENABLED, true)
            .apply()
        _isEnabled.value = true
        _isLocked.value = true
        resetFailedAttempts()
    }

    /**
     * Verify a PIN against the stored hash. Tracks failed attempts.
     * Returns true on match, false otherwise.
     */
    fun verifyPin(pin: String): Boolean {
        val storedHash = prefs.getString(KEY_PIN_HASH, null) ?: return false

        val matches = if (storedHash.contains(":")) {
            // New PBKDF2 format: "saltHex:hashHex"
            verifyPbkdf2Pin(pin, storedHash)
        } else {
            // Legacy SHA-256 format (no colon) — verify and auto-migrate
            val legacyHash = legacyHashPin(pin)
            if (legacyHash == storedHash) {
                // Auto-migrate to PBKDF2 on successful verification
                val newHash = hashPin(pin)
                prefs.edit().putString(KEY_PIN_HASH, newHash).apply()
                true
            } else {
                false
            }
        }

        return if (matches) {
            resetFailedAttempts()
            true
        } else {
            recordFailedAttempt()
            false
        }
    }

    /**
     * Change the PIN. Verifies the old PIN first.
     * Returns true on success, false if old PIN is wrong.
     */
    fun changePin(oldPin: String, newPin: String): Boolean {
        if (!verifyPin(oldPin)) return false
        val hash = hashPin(newPin)
        prefs.edit()
            .putString(KEY_PIN_HASH, hash)
            .apply()
        return true
    }

    /**
     * Unlock parental controls by verifying the PIN.
     * Returns true if unlocked successfully, false otherwise.
     */
    fun unlock(pin: String): Boolean {
        if (verifyPin(pin)) {
            _isLocked.value = false
            return true
        }
        return false
    }

    // --- Temporary Unlock (in-memory only, expires on app restart) ---

    private var temporaryUnlockExpiry: Long = 0L

    /**
     * Grant a temporary unlock session. All blocked content becomes visible
     * for [durationMinutes] minutes without disabling parental controls.
     */
    fun temporaryUnlock(durationMinutes: Int = 30) {
        temporaryUnlockExpiry = System.currentTimeMillis() + durationMinutes * 60 * 1000L
    }

    /**
     * Whether a temporary unlock session is active.
     * Returns false after the session expires or after app restart.
     */
    fun isTemporarilyUnlocked(): Boolean {
        return System.currentTimeMillis() < temporaryUnlockExpiry
    }

    /**
     * Clear any active temporary unlock session.
     */
    fun clearTemporaryUnlock() {
        temporaryUnlockExpiry = 0L
    }

    /**
     * Lock parental controls. Content will be blocked until unlocked.
     */
    fun lock() {
        _isLocked.value = true
    }

    /**
     * Enable or disable parental controls entirely.
     * Disabling also removes the lock state.
     */
    fun setEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        _isEnabled.value = enabled
        if (!enabled) {
            _isLocked.value = false
        } else {
            _isLocked.value = true
        }
    }

    /**
     * Number of consecutive failed PIN attempts since last reset.
     */
    fun getFailedAttempts(): Int {
        return prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
    }

    /**
     * True if 3 or more failed attempts occurred within the last 30 seconds.
     */
    fun isLockedOut(): Boolean {
        val attempts = getFailedAttempts()
        if (attempts < MAX_FAILED_ATTEMPTS) return false

        val lastFailedTime = prefs.getLong(KEY_LAST_FAILED_TIME, 0L)
        val elapsed = System.currentTimeMillis() - lastFailedTime
        return elapsed < LOCKOUT_DURATION_MS
    }

    /**
     * Returns the number of seconds remaining in the lockout period,
     * or 0 if not locked out.
     */
    fun getLockoutRemainingSeconds(): Int {
        if (!isLockedOut()) return 0
        val lastFailedTime = prefs.getLong(KEY_LAST_FAILED_TIME, 0L)
        val elapsed = System.currentTimeMillis() - lastFailedTime
        val remainingMs = LOCKOUT_DURATION_MS - elapsed
        return ((remainingMs + 999) / 1000).toInt().coerceAtLeast(0)
    }

    /**
     * Clear failed attempt tracking.
     */
    fun resetFailedAttempts() {
        prefs.edit()
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LAST_FAILED_TIME, 0L)
            .apply()
    }

    private fun recordFailedAttempt() {
        val currentAttempts = getFailedAttempts() + 1
        prefs.edit()
            .putInt(KEY_FAILED_ATTEMPTS, currentAttempts)
            .putLong(KEY_LAST_FAILED_TIME, System.currentTimeMillis())
            .apply()
    }

    private fun hashPin(pin: String): String {
        val salt = ByteArray(16)
        java.security.SecureRandom().nextBytes(salt)
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded
        val saltHex = salt.joinToString("") { "%02x".format(it) }
        val hashHex = hash.joinToString("") { "%02x".format(it) }
        return "$saltHex:$hashHex"
    }

    private fun verifyPbkdf2Pin(pin: String, storedHash: String): Boolean {
        val parts = storedHash.split(":")
        if (parts.size != 2) return false
        val salt = parts[0].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded
        val hashHex = hash.joinToString("") { "%02x".format(it) }
        return hashHex == parts[1]
    }

    /** Legacy SHA-256 hash for migration from old format. */
    private fun legacyHashPin(pin: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(pin.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val PREFS_NAME = "ooustream_parental_prefs"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_LAST_FAILED_TIME = "last_failed_time"
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 60_000L // 60 seconds
        private const val PBKDF2_ITERATIONS = 100_000
    }
}
