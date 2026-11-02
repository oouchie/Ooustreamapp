package com.ooustream.iptv.settings

import android.content.Context
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import coil.imageLoader
import com.ooustream.iptv.common.BaseViewModel
import com.ooustream.iptv.data.local.OoustreamDatabase
import com.ooustream.iptv.data.local.dao.SeriesTrackingDao
import com.ooustream.iptv.data.local.dao.WatchProgressDao
import com.ooustream.iptv.data.repository.ContentCacheRepository
import com.ooustream.iptv.data.repository.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    private val seriesTrackingDao: SeriesTrackingDao,
    private val catalogCache: com.ooustream.iptv.data.repository.CatalogCache,
    @ApplicationContext private val context: Context,
    private val imageLoader: ImageLoader
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

    /**
     * Clears what the app downloads again by itself: the channel, movie and series lists, the
     * guide and saved pictures. Favorites, watch history and parental controls stay (v5.2.4).
     * It used to run clearAllTables(), which also wiped those, parental category blocks
     * included, so a customer told to clear the cache lost them.
     */
    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                contentCacheRepository.clearContentCache()
                catalogCache.invalidate()
                database.epgCacheDao().clearAll()
                // Pictures sit in two loaders: Coil's app-wide one and the injected one.
                listOf(context.imageLoader, imageLoader).distinct().forEach {
                    it.memoryCache?.clear()
                    it.diskCache?.clear()
                }
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
                // Live TV history too — it feeds the "Recently Watched" rail on Live TV and
                // Home's "For You — Live Now". Leaving these behind makes this action a
                // visible lie now that the channel log is surfaced as a list.
                database.channelWatchLogDao().clearAll()
                database.channelScoreDao().clearAll()
            } catch (e: Exception) {
                _error.emit("Failed to clear watch history: ${e.message}")
            }
        }
    }

    fun refreshPlaylist() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                contentCacheRepository.clearContentCache()
                catalogCache.invalidate()
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
