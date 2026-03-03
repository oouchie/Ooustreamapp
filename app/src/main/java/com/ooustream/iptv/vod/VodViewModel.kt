package com.ooustream.iptv.vod

import androidx.lifecycle.viewModelScope
import com.ooustream.iptv.common.BaseViewModel
import com.ooustream.iptv.data.model.Category
import com.ooustream.iptv.data.local.entity.FavoriteEntity
import com.ooustream.iptv.data.model.VodStream
import com.ooustream.iptv.data.local.entity.WatchProgressEntity
import com.ooustream.iptv.data.repository.ContentCacheRepository
import com.ooustream.iptv.data.repository.ContentRepository
import com.ooustream.iptv.data.repository.FavoriteRepository
import com.ooustream.iptv.data.repository.WatchProgressRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

@HiltViewModel
class VodViewModel @Inject constructor(
    private val contentRepository: ContentRepository,
    private val favoriteRepository: FavoriteRepository,
    private val contentCacheRepository: ContentCacheRepository,
    private val watchProgressRepository: WatchProgressRepository
) : BaseViewModel() {

    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories.asStateFlow()

    private val _movies = MutableStateFlow<List<VodStream>>(emptyList())
    val movies: StateFlow<List<VodStream>> = _movies.asStateFlow()

    private val _selectedCategoryId = MutableStateFlow<String?>(null)
    val selectedCategoryId: StateFlow<String?> = _selectedCategoryId.asStateFlow()

    val favorites = favoriteRepository.getFavoritesByType("vod")

    private val _watchProgressMap = MutableStateFlow<Map<String, WatchProgressEntity>>(emptyMap())
    val watchProgressMap: StateFlow<Map<String, WatchProgressEntity>> = _watchProgressMap.asStateFlow()

    /** Saved positions for focus restoration on back navigation */
    var savedGridPosition: Int = -1
    var savedCategoryPosition: Int = -1

    fun loadCategories() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                contentCacheRepository.getCategories("vod").collect { categories ->
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
                        val favs = favoriteRepository.getFavoritesListByType("vod")
                        _movies.value = favs.map { fav ->
                            VodStream(
                                num = null,
                                name = fav.name,
                                streamType = "movie",
                                streamId = fav.streamId,
                                streamIcon = fav.icon,
                                rating = null,
                                rating5based = null,
                                added = null,
                                categoryId = fav.categoryId,
                                containerExtension = fav.extra,
                                customSid = null,
                                directSource = null
                            )
                        }
                    }
                    RECENTLY_ADDED_ID -> {
                        val all = contentRepository.getVodStreams()
                        _movies.value = all.sortedByDescending { it.added?.toLongOrNull() ?: 0L }
                    }
                    NEW_RELEASES_ID -> {
                        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
                        val cutoffYear = currentYear - 1 // Movies from last year and this year
                        val all = contentRepository.getVodStreams()
                        _movies.value = all
                            .filter { movie ->
                                val year = parseYear(movie.name)
                                year != null && year >= cutoffYear
                            }
                            .sortedByDescending { parseYear(it.name) ?: 0 }
                    }
                    else -> {
                        _movies.value = contentRepository.getVodStreams(categoryId)
                    }
                }
            } catch (e: Exception) {
                _error.emit(e.message ?: "Failed to load movies")
            }
        }
    }

    fun toggleFavorite(movie: VodStream) {
        viewModelScope.launch {
            val id = "vod_${movie.streamId}"
            if (favoriteRepository.isFavorite(id)) {
                favoriteRepository.removeFavorite(id)
                _toastEvent.emit("Removed from Favorites")
            } else {
                favoriteRepository.addFavorite(
                    FavoriteEntity(
                        id = id,
                        streamId = movie.streamId,
                        type = "vod",
                        name = movie.name,
                        icon = movie.streamIcon,
                        categoryId = movie.categoryId,
                        extra = movie.containerExtension
                    )
                )
                _toastEvent.emit("Added to Favorites")
            }
        }
    }

    companion object {
        const val FAVORITES_ID = "__favorites__"
        const val RECENTLY_ADDED_ID = "__recently_added__"
        const val NEW_RELEASES_ID = "__new_releases__"

        private val YEAR_REGEX = Regex("""\b(20\d{2})\b""")

        /** Extract a 4-digit year from a movie title (e.g. "Movie Name (2025)") */
        fun parseYear(title: String): Int? {
            return YEAR_REGEX.findAll(title)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .filter { it in 2000..2099 }
                .lastOrNull()
        }
    }

    fun buildStreamUrl(movie: VodStream): String {
        return contentRepository.buildVodStreamUrl(movie.streamId, movie.containerExtension ?: "mp4")
    }

    /** Load watch progress for the currently displayed movie list */
    fun loadWatchProgress(movieIds: List<Int>) {
        if (movieIds.isEmpty()) return
        viewModelScope.launch {
            try {
                val streamIds = movieIds.map { it.toString() }
                val progressList = watchProgressRepository.getProgressForIds(streamIds)
                _watchProgressMap.value = progressList.associateBy { it.streamId }
            } catch (_: Exception) {
                // Non-critical — silently fail
            }
        }
    }
}
