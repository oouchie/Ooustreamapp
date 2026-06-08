package com.ooustream.iptv.search

import androidx.lifecycle.viewModelScope
import com.ooustream.iptv.common.BaseViewModel
import com.ooustream.iptv.data.local.dao.SearchHistoryDao
import com.ooustream.iptv.data.local.entity.SearchHistoryEntity
import com.ooustream.iptv.data.local.entity.SearchIndexEntity
import com.ooustream.iptv.data.repository.ContentRepository
import com.ooustream.iptv.data.repository.SearchIndexRepository
import com.ooustream.iptv.data.repository.FavoriteRepository
import com.ooustream.iptv.data.repository.SearchResults
import com.ooustream.iptv.parental.ContentFilterManager
import com.ooustream.iptv.data.local.entity.FavoriteEntity
import com.ooustream.iptv.common.PosterItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.ooustream.iptv.data.model.VodStream
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val contentRepository: ContentRepository,
    private val searchHistoryDao: SearchHistoryDao,
    private val searchIndexRepository: SearchIndexRepository,
    private val favoriteRepository: FavoriteRepository,
    private val contentFilterManager: ContentFilterManager
) : BaseViewModel() {

    private val _searchResults = MutableStateFlow<SearchResults?>(null)
    val searchResults: StateFlow<SearchResults?> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _activeFilter = MutableStateFlow("All")
    val activeFilter: StateFlow<String> = _activeFilter.asStateFlow()

    private val _trendingContent = MutableStateFlow<List<VodStream>>(emptyList())
    val trendingContent: StateFlow<List<VodStream>> = _trendingContent.asStateFlow()

    val recentSearches: StateFlow<List<SearchHistoryEntity>> = searchHistoryDao.getRecent()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private var searchJob: Job? = null

    init {
        // Trigger index rebuild on ViewModel creation (background)
        viewModelScope.launch {
            try {
                searchIndexRepository.rebuildIndex()
            } catch (_: Exception) { }
        }
        // Load trending content
        loadTrending()
    }

    fun setActiveFilter(filter: String) {
        _activeFilter.value = filter
    }

    private fun loadTrending() {
        viewModelScope.launch {
            try {
                val vod = contentRepository.getVodStreams()
                val filtered = contentFilterManager.filterContent("vod", vod) { it.categoryId }
                val sorted = filtered.sortedByDescending { it.added?.toLongOrNull() ?: 0L }
                _trendingContent.value = sorted.take(15)
            } catch (_: Exception) { }
        }
    }

    fun search(query: String) {
        searchJob?.cancel()

        if (query.isBlank()) {
            _searchResults.value = null
            _isSearching.value = false
            return
        }

        searchJob = viewModelScope.launch {
            delay(300L)
            _isSearching.value = true
            try {
                // Try API search first (returns full objects with images)
                val results = contentRepository.search(query)
                _searchResults.value = filterSearchResults(results)
                searchHistoryDao.insert(SearchHistoryEntity(query = query.trim()))
            } catch (_: Exception) {
                // Offline fallback: use FTS local search (no images, but functional)
                try {
                    if (searchIndexRepository.isIndexBuilt()) {
                        val ftsResults = searchIndexRepository.search(query)
                        if (ftsResults.isNotEmpty()) {
                            _searchResults.value = mapFtsToSearchResults(ftsResults)
                            searchHistoryDao.insert(SearchHistoryEntity(query = query.trim()))
                        }
                    }
                } catch (e: Exception) {
                    _error.emit(e.message ?: "Search failed")
                }
            } finally {
                _isSearching.value = false
            }
        }
    }

    /**
     * Map FTS SearchIndexEntity results back into the SearchResults format.
     * Used as offline fallback — images may be null.
     */
    private fun mapFtsToSearchResults(entries: List<SearchIndexEntity>): SearchResults {
        val live = entries.filter { it.type == "live" }.map { entry ->
            com.ooustream.iptv.data.model.LiveStream(
                num = null,
                name = entry.name,
                streamType = "live",
                streamId = entry.streamId,
                streamIcon = null,
                epgChannelId = entry.extra,
                added = null,
                categoryId = null,
                customSid = null,
                tvArchive = null,
                directSource = null,
                tvArchiveDuration = null
            )
        }

        val vod = entries.filter { it.type == "vod" }.map { entry ->
            com.ooustream.iptv.data.model.VodStream(
                num = null,
                name = entry.name,
                streamType = "movie",
                streamId = entry.streamId,
                streamIcon = null,
                rating = entry.rating,
                rating5based = null,
                added = null,
                categoryId = null,
                containerExtension = entry.extra,
                customSid = null,
                directSource = null
            )
        }

        val series = entries.filter { it.type == "series" }.map { entry ->
            com.ooustream.iptv.data.model.Series(
                num = null,
                name = entry.name,
                seriesId = entry.streamId,
                cover = null,
                plot = null,
                cast = null,
                director = null,
                genre = entry.extra,
                releaseDate = null,
                lastModified = null,
                rating = entry.rating,
                rating5based = null,
                backdropPath = null,
                youtubeTrailer = null,
                episodeRunTime = null,
                categoryId = null
            )
        }

        return SearchResults(live = live, vod = vod, series = series)
    }

    private fun filterSearchResults(results: SearchResults): SearchResults {
        return SearchResults(
            live = contentFilterManager.filterContent("live", results.live) { it.categoryId },
            vod = contentFilterManager.filterContent("vod", results.vod) { it.categoryId },
            series = contentFilterManager.filterContent("series", results.series) { it.categoryId },
            castMatches = results.castMatches
        )
    }

    fun buildLiveStreamUrl(streamId: Int): String {
        return contentRepository.buildLiveStreamUrl(streamId)
    }

    fun buildVodStreamUrl(streamId: Int, ext: String): String {
        return contentRepository.buildVodStreamUrl(streamId, ext)
    }

    fun toggleFavorite(item: PosterItem) {
        viewModelScope.launch {
            val id = "${item.type}_${item.id}"
            if (favoriteRepository.isFavorite(id)) {
                favoriteRepository.removeFavorite(id)
                _toastEvent.emit("Removed from Favorites")
            } else {
                favoriteRepository.addFavorite(
                    FavoriteEntity(
                        id = id,
                        streamId = item.id,
                        type = item.type,
                        name = item.title,
                        icon = item.imageUrl,
                        categoryId = null,
                        extra = item.extension
                    )
                )
                _toastEvent.emit("Added to Favorites")
            }
        }
    }
}
