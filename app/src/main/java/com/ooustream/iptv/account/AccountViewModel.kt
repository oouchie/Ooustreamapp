package com.ooustream.iptv.account

import androidx.lifecycle.viewModelScope
import com.ooustream.iptv.common.BaseViewModel
import com.ooustream.iptv.data.model.AuthResponse
import com.ooustream.iptv.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : BaseViewModel() {

    private val _accountInfo = MutableStateFlow<AuthResponse?>(null)
    val accountInfo: StateFlow<AuthResponse?> = _accountInfo.asStateFlow()

    fun loadAccountInfo() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = authRepository.getAccountInfo()
                result.onSuccess { info ->
                    _accountInfo.value = info
                }.onFailure { e ->
                    _error.emit(e.message ?: "Failed to load account info")
                }
            } catch (e: Exception) {
                _error.emit(e.message ?: "Failed to load account info")
            } finally {
                _isLoading.value = false
            }
        }
    }
}
