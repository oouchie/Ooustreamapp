package com.ooustream.iptv.search

import androidx.lifecycle.viewModelScope
import com.ooustream.iptv.common.BaseViewModel
import com.ooustream.iptv.data.local.dao.SearchHistoryDao
import com.ooustream.iptv.data.local.entity.SearchHistoryEntity
import com.ooustream.iptv.data.local.entity.SearchIndexEntity
import com.ooustream.iptv.data.repository.ContentRepository
import com.ooustream.iptv.data.repository.SearchIndexRepository
import com.ooustream.iptv.data.repository.SearchResults
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val contentRepository: ContentRepository,
    private val searchHistoryDao: SearchHistoryDao,
    private val searchIndexRepository: SearchIndexRepository
) : BaseViewModel() {

    private val _searchResults = MutableStateFlow<SearchResults?>(null)
    val searchResults: StateFlow<SearchResults?> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

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
                // Try FTS local search first
                if (searchIndexRepository.isIndexBuilt()) {
                    val ftsResults = searchIndexRepository.search(query)
                    if (ftsResults.isNotEmpty()) {
                        _searchResults.value = mapFtsToSearchResults(ftsResults)
                        searchHistoryDao.insert(SearchHistoryEntity(query = query.trim()))
                        return@launch
                    }
                }

                // Fallback to API search if index is empty or no results
                val results = contentRepository.search(query)
                _searchResults.value = results
                searchHistoryDao.insert(SearchHistoryEntity(query = query.trim()))
            } catch (e: Exception) {
                _error.emit(e.message ?: "Search failed")
            } finally {
                _isSearching.value = false
            }
        }
    }

    /**
     * Map FTS SearchIndexEntity results back into the SearchResults format
     * used by the UI. Since FTS entries are lightweight (no icons/covers),
     * we create minimal model objects with just the data we have.
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

    fun buildLiveStreamUrl(streamId: Int): String {
        return contentRepository.buildLiveStreamUrl(streamId)
    }

    fun buildVodStreamUrl(streamId: Int, ext: String): String {
        return contentRepository.buildVodStreamUrl(streamId, ext)
    }
}
