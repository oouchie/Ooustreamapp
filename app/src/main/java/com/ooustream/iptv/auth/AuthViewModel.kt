package com.ooustream.iptv.auth

import androidx.lifecycle.viewModelScope
import com.ooustream.iptv.common.BaseViewModel
import com.ooustream.iptv.data.model.AuthResponse
import com.ooustream.iptv.data.repository.AuthRepository
import com.ooustream.iptv.data.UserPlanManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.util.Log
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class AuthState {
    object Idle : AuthState()
    object Loading : AuthState()
    data class Success(val response: AuthResponse) : AuthState()
    data class Error(val message: String) : AuthState()
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val userPlanManager: UserPlanManager
) : BaseViewModel() {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    init {
        attemptAutoLogin()
    }

    private fun attemptAutoLogin() {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            val result = authRepository.autoLogin()
            if (result != null) {
                result.fold(
                    onSuccess = {
                        val mc = it.userInfo.maxConnections
                        Log.w("OOUSTREAM_PLAN", "autoLogin maxConnections raw='$mc' parsed=${mc?.toIntOrNull()} isPro=${(mc?.toIntOrNull() ?: 1) >= 4}")
                        userPlanManager.updateFromMaxConnections(
                            mc?.toIntOrNull() ?: 1
                        )
                        _authState.value = AuthState.Success(it)
                    },
                    onFailure = { _authState.value = AuthState.Idle }
                )
            } else {
                _authState.value = AuthState.Idle
            }
        }
    }

    fun login(serverUrl: String, username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            _authState.value = AuthState.Error("Username and password are required")
            return
        }
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            val result = authRepository.login(serverUrl, username, password)
            result.fold(
                onSuccess = {
                    userPlanManager.updateFromMaxConnections(
                        it.userInfo.maxConnections?.toIntOrNull() ?: 1
                    )
                    // Set Crashlytics context for crash reports
                    com.ooustream.iptv.common.CrashlyticsHelper.setUserContext(username, serverUrl)
                    _authState.value = AuthState.Success(it)
                },
                onFailure = { _authState.value = AuthState.Error(it.message ?: "Login failed") }
            )
        }
    }

    fun logout() {
        authRepository.logout()
        _authState.value = AuthState.Idle
    }
}
