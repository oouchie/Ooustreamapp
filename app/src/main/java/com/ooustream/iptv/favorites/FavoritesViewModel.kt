package com.ooustream.iptv.favorites

import androidx.lifecycle.viewModelScope
import com.ooustream.iptv.common.BaseViewModel
import com.ooustream.iptv.data.local.entity.FavoriteEntity
import com.ooustream.iptv.data.repository.ContentRepository
import com.ooustream.iptv.data.repository.FavoriteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val favoriteRepository: FavoriteRepository,
    private val contentRepository: ContentRepository
) : BaseViewModel() {

    val allFavorites: Flow<List<FavoriteEntity>> = favoriteRepository.getAllFavorites()

    val liveFavorites: Flow<List<FavoriteEntity>> = favoriteRepository.getFavoritesByType("live")

    val vodFavorites: Flow<List<FavoriteEntity>> = favoriteRepository.getFavoritesByType("vod")

    val seriesFavorites: Flow<List<FavoriteEntity>> = favoriteRepository.getFavoritesByType("series")

    fun removeFavorite(id: String) {
        viewModelScope.launch {
            try {
                favoriteRepository.removeFavorite(id)
                _toastEvent.emit("Removed from Favorites")
            } catch (e: Exception) {
                _toastEvent.emit("Failed to remove favorite")
            }
        }
    }

    fun buildLiveUrl(streamId: Int): String = contentRepository.buildLiveStreamUrl(streamId)

    fun buildVodUrl(streamId: Int, ext: String): String = contentRepository.buildVodStreamUrl(streamId, ext)
}
