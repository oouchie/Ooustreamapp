package com.ooustream.iptv.speedtest

import androidx.lifecycle.viewModelScope
import com.ooustream.iptv.common.BaseViewModel
import com.ooustream.iptv.data.repository.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class TestState {
    object Idle : TestState()
    data class Testing(val phase: String) : TestState()
    data class Complete(val result: SpeedResult) : TestState()
    data class Error(val message: String) : TestState()
}

@HiltViewModel
class SpeedTestViewModel @Inject constructor(
    private val speedTestService: SpeedTestService,
    private val credentialStore: CredentialStore
) : BaseViewModel() {

    private val _testState = MutableStateFlow<TestState>(TestState.Idle)
    val testState: StateFlow<TestState> = _testState.asStateFlow()

    fun startTest() {
        viewModelScope.launch {
            try {
                _isLoading.value = true

                val credentials = credentialStore.load()
                    ?: throw IllegalStateException("No server credentials found. Please log in first.")

                // Phase 1: Ping
                _testState.value = TestState.Testing("Measuring ping...")
                val pingMs = speedTestService.runPingTest(credentials.serverUrl)

                // Phase 2: Download
                _testState.value = TestState.Testing("Measuring download speed...")
                val downloadMbps = speedTestService.runDownloadTest(
                    credentials.serverUrl,
                    credentials.username,
                    credentials.password
                )

                // Calculate rating
                val rating = when {
                    downloadMbps > 50f -> SpeedRating.EXCELLENT
                    downloadMbps > 20f -> SpeedRating.GOOD
                    downloadMbps > 5f  -> SpeedRating.FAIR
                    else               -> SpeedRating.POOR
                }

                val result = SpeedResult(
                    pingMs = pingMs,
                    downloadMbps = downloadMbps,
                    rating = rating
                )

                _testState.value = TestState.Complete(result)
                _toastEvent.emit("Speed test complete")
            } catch (e: Exception) {
                val message = e.message ?: "Speed test failed"
                _testState.value = TestState.Error(message)
                _error.emit(message)
            } finally {
                _isLoading.value = false
            }
        }
    }
}
