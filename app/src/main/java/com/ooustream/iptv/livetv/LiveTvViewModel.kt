package com.ooustream.iptv.livetv

import androidx.lifecycle.viewModelScope
import com.ooustream.iptv.common.BaseViewModel
import com.ooustream.iptv.data.local.entity.FavoriteEntity
import com.ooustream.iptv.data.model.Category
import com.ooustream.iptv.data.model.EpgProgram
import com.ooustream.iptv.data.model.LiveStream
import com.ooustream.iptv.data.repository.ContentCacheRepository
import com.ooustream.iptv.data.repository.ContentRepository
import com.ooustream.iptv.data.repository.EpgCacheRepository
import com.ooustream.iptv.data.repository.FavoriteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LiveTvViewModel @Inject constructor(
    private val contentRepository: ContentRepository,
    private val favoriteRepository: FavoriteRepository,
    private val epgCacheRepository: EpgCacheRepository,
    private val contentCacheRepository: ContentCacheRepository
) : BaseViewModel() {

    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories.asStateFlow()

    private val _channels = MutableStateFlow<List<LiveStream>>(emptyList())
    val channels: StateFlow<List<LiveStream>> = _channels.asStateFlow()

    private val _epgPrograms = MutableStateFlow<List<EpgProgram>>(emptyList())
    val epgPrograms: StateFlow<List<EpgProgram>> = _epgPrograms.asStateFlow()

    private val _selectedCategoryId = MutableStateFlow<String?>(FAVORITES_ID)
    val selectedCategoryId: StateFlow<String?> = _selectedCategoryId.asStateFlow()

    val favorites = favoriteRepository.getAllFavorites()

    init {
        selectCategory(FAVORITES_ID)
    }

    /** Full preview state — saved before fullscreen, consumed on return to restart preview */
    var lastPreviewedChannel: LiveStream? = null
    var lastPreviewedUrl: String? = null
    var lastPreviewedIndex: Int = -1

    /** Saved positions for focus restoration on back navigation */
    var savedChannelPosition: Int = -1
    var savedCategoryPosition: Int = -1

    fun loadCategories() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                contentCacheRepository.getCategories("live").collect { categories ->
                    _categories.value = categories
                    if (_selectedCategoryId.value == null) {
                        categories.firstOrNull()?.let { selectCategory(it.categoryId) }
                    }
                    _isLoading.value = false
                }
            } catch (e: Exception) {
                _error.emit(e.message ?: "Failed to load categories")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun selectCategory(categoryId: String) {
        _selectedCategoryId.value = categoryId
        viewModelScope.launch {
            try {
                if (categoryId == FAVORITES_ID) {
                    val favs = favoriteRepository.getFavoritesListByType("live")
                    _channels.value = favs.map { fav ->
                            LiveStream(
                                num = null,
                                name = fav.name,
                                streamType = "live",
                                streamId = fav.streamId,
                                streamIcon = fav.icon,
                                epgChannelId = null,
                                added = null,
                                categoryId = fav.categoryId,
                                customSid = null,
                                tvArchive = 0,
                                directSource = null,
                                tvArchiveDuration = null
                            )
                        }
                } else {
                    _channels.value = contentRepository.getLiveStreams(categoryId)
                }
            } catch (e: Exception) {
                _error.emit(e.message ?: "Failed to load channels")
            }
        }
    }

    companion object {
        const val FAVORITES_ID = "__favorites__"
    }

    fun loadEpg(streamId: Int) {
        viewModelScope.launch {
            try {
                _epgPrograms.value = epgCacheRepository.getEpg(streamId)
            } catch (e: Exception) {
                _epgPrograms.value = emptyList()
            }
        }
    }

    fun toggleFavorite(channel: LiveStream) {
        viewModelScope.launch {
            val id = "live_${channel.streamId}"
            if (favoriteRepository.isFavorite(id)) {
                favoriteRepository.removeFavorite(id)
                _toastEvent.emit("Removed from Favorites")
            } else {
                favoriteRepository.addFavorite(
                    FavoriteEntity(
                        id = id,
                        streamId = channel.streamId,
                        type = "live",
                        name = channel.name,
                        icon = channel.streamIcon,
                        categoryId = channel.categoryId,
                        extra = null
                    )
                )
                _toastEvent.emit("Added to Favorites")
            }
        }
    }

    fun buildStreamUrl(streamId: Int): String = contentRepository.buildLiveStreamUrl(streamId)
}
