package com.ooustream.iptv.settings

import androidx.lifecycle.viewModelScope
import com.ooustream.iptv.common.BaseViewModel
import com.ooustream.iptv.data.local.OoustreamDatabase
import com.ooustream.iptv.data.repository.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val credentialStore: CredentialStore,
    private val database: OoustreamDatabase
) : BaseViewModel() {

    sealed class SettingsEvent {
        object LoggedOut : SettingsEvent()
        object CacheCleared : SettingsEvent()
    }

    private val _event = MutableSharedFlow<SettingsEvent>()
    val event: SharedFlow<SettingsEvent> = _event.asSharedFlow()

    fun getUsername(): String {
        return credentialStore.load()?.username ?: "Unknown"
    }

    fun getServerUrl(): String {
        return credentialStore.load()?.serverUrl ?: "Unknown"
    }

    fun logout() {
        viewModelScope.launch {
            credentialStore.clear()
            _event.emit(SettingsEvent.LoggedOut)
        }
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                database.clearAllTables()
                _event.emit(SettingsEvent.CacheCleared)
            } catch (e: Exception) {
                _error.emit("Failed to clear cache: ${e.message}")
            }
        }
    }
}
