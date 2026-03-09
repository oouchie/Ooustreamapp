package com.ooustream.iptv.settings

import androidx.lifecycle.viewModelScope
import com.ooustream.iptv.common.BaseViewModel
import com.ooustream.iptv.data.local.OoustreamDatabase
import com.ooustream.iptv.data.local.dao.SeriesTrackingDao
import com.ooustream.iptv.data.local.dao.WatchProgressDao
import com.ooustream.iptv.data.repository.ContentCacheRepository
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
    private val database: OoustreamDatabase,
    private val contentCacheRepository: ContentCacheRepository,
    private val watchProgressDao: WatchProgressDao,
    private val seriesTrackingDao: SeriesTrackingDao
) : BaseViewModel() {

    sealed class SettingsEvent {
        object LoggedOut : SettingsEvent()
        object CacheCleared : SettingsEvent()
        object PlaylistRefreshed : SettingsEvent()
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

    fun clearWatchHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                watchProgressDao.clearAll()
                seriesTrackingDao.clearAll()
            } catch (e: Exception) {
                _error.emit("Failed to clear watch history: ${e.message}")
            }
        }
    }

    fun refreshPlaylist() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                contentCacheRepository.clearContentCache()
                contentCacheRepository.getCategories("live").collect {}
                contentCacheRepository.getCategories("vod").collect {}
                contentCacheRepository.getCategories("series").collect {}
                _event.emit(SettingsEvent.PlaylistRefreshed)
            } catch (e: Exception) {
                _error.emit("Playlist update failed: ${e.message}")
            }
        }
    }
}
