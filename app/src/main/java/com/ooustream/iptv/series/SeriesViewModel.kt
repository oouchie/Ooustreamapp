package com.ooustream.iptv.series

import androidx.lifecycle.viewModelScope
import com.ooustream.iptv.common.BaseViewModel
import com.ooustream.iptv.data.local.entity.FavoriteEntity
import com.ooustream.iptv.data.model.Category
import com.ooustream.iptv.data.model.Series
import com.ooustream.iptv.data.repository.ContentCacheRepository
import com.ooustream.iptv.data.repository.ContentRepository
import com.ooustream.iptv.data.repository.FavoriteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SeriesViewModel @Inject constructor(
    private val contentRepository: ContentRepository,
    private val contentCacheRepository: ContentCacheRepository,
    private val favoriteRepository: FavoriteRepository
) : BaseViewModel() {

    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories.asStateFlow()

    private val _seriesList = MutableStateFlow<List<Series>>(emptyList())
    val seriesList: StateFlow<List<Series>> = _seriesList.asStateFlow()

    private val _selectedCategoryId = MutableStateFlow<String?>(null)
    val selectedCategoryId: StateFlow<String?> = _selectedCategoryId.asStateFlow()

    val favorites = favoriteRepository.getFavoritesByType("series")

    fun loadCategories() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                contentCacheRepository.getCategories("series").collect { categories ->
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
                when (categoryId) {
                    FAVORITES_ID -> {
                        val favs = favoriteRepository.getFavoritesListByType("series")
                        _seriesList.value = favs.map { fav ->
                            Series(
                                num = null,
                                name = fav.name,
                                seriesId = fav.streamId,
                                cover = fav.icon,
                                plot = null,
                                cast = null,
                                director = null,
                                genre = null,
                                releaseDate = null,
                                lastModified = null,
                                rating = null,
                                rating5based = null,
                                backdropPath = null,
                                youtubeTrailer = null,
                                episodeRunTime = null,
                                categoryId = fav.categoryId
                            )
                        }
                    }
                    RECENTLY_ADDED_ID -> {
                        val all = contentRepository.getSeries()
                        _seriesList.value = all.sortedByDescending { it.lastModified?.toLongOrNull() ?: 0L }
                    }
                    else -> {
                        _seriesList.value = contentRepository.getSeries(categoryId)
                    }
                }
            } catch (e: Exception) {
                _error.emit(e.message ?: "Failed to load series")
            }
        }
    }

    fun toggleFavorite(series: Series) {
        viewModelScope.launch {
            val id = "series_${series.seriesId}"
            if (favoriteRepository.isFavorite(id)) {
                favoriteRepository.removeFavorite(id)
                _toastEvent.emit("Removed from Favorites")
            } else {
                favoriteRepository.addFavorite(
                    FavoriteEntity(
                        id = id,
                        streamId = series.seriesId,
                        type = "series",
                        name = series.name,
                        icon = series.cover,
                        categoryId = series.categoryId,
                        extra = null
                    )
                )
                _toastEvent.emit("Added to Favorites")
            }
        }
    }

    companion object {
        const val FAVORITES_ID = "__favorites__"
        const val RECENTLY_ADDED_ID = "__recently_added__"
    }
}
