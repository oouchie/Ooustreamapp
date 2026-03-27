package com.ooustream.iptv.data

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.ooustream.iptv.data.repository.AuthRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserPlanManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository
) {
    private val _maxConnections = MutableStateFlow(1)
    val maxConnections: StateFlow<Int> = _maxConnections

    private val _isPro = MutableStateFlow(false)
    val isPro: StateFlow<Boolean> = _isPro

    /**
     * Re-authenticates to get fresh user_info and updates plan state.
     * Pro plan = maxConnections >= 4.
     */
    suspend fun refreshPlan() {
        val result = authRepository.getAccountInfo()
        Log.w("OOUSTREAM_PLAN", "refreshPlan result=${result.isSuccess} failure=${result.exceptionOrNull()?.message}")
        result.getOrNull()?.let { response ->
            @Suppress("SENSELESS_COMPARISON") // Gson can set non-nullable to null
            val userInfo = response.userInfo
            if (userInfo == null) {
                Log.w("OOUSTREAM_PLAN", "refreshPlan: userInfo null (malformed API response)")
                return@let
            }
            val mc = userInfo.maxConnections
            val connections = mc?.toIntOrNull() ?: 1
            Log.w("OOUSTREAM_PLAN", "refreshPlan maxConnections raw='$mc' parsed=$connections isPro=${connections >= 4}")
            _maxConnections.value = connections
            _isPro.value = connections >= 4
        }
    }

    /**
     * Update plan state from an already-fetched AuthResponse (avoids extra API call
     * when login/auto-login already has the data).
     */
    fun updateFromMaxConnections(maxConnections: Int) {
        _maxConnections.value = maxConnections
        _isPro.value = maxConnections >= 4
    }

    /**
     * Checks if device has enough RAM for MultiView (1.5GB+).
     * Returns false on 1GB Fire TV Sticks, true on 4K Sticks and Shield.
     */
    fun isDeviceCapable(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val totalGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
        return totalGb >= 1.4 // 1.5GB threshold with small margin
    }

    /**
     * Whether MultiView should be shown (device capable AND feature available).
     */
    fun isMultiViewAvailable(): Boolean = isDeviceCapable()
}
