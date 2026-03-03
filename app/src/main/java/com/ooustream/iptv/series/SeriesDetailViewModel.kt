package com.ooustream.iptv.series

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.ooustream.iptv.common.BaseViewModel
import com.ooustream.iptv.data.local.entity.WatchProgressEntity
import com.ooustream.iptv.data.model.Episode
import com.ooustream.iptv.data.model.Season
import com.ooustream.iptv.data.model.SeriesInfo
import com.ooustream.iptv.data.repository.ContentRepository
import com.ooustream.iptv.data.repository.WatchProgressRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Represents a season tab with its display name and the key into the episodes map.
 */
data class SeasonTab(
    val key: String,       // The actual key in the episodes map (e.g., "1", "2003")
    val displayName: String // What to show in the UI (e.g., "Season 1", "2003")
)

@HiltViewModel
class SeriesDetailViewModel @Inject constructor(
    private val contentRepository: ContentRepository,
    private val watchProgressRepository: WatchProgressRepository
) : BaseViewModel() {

    companion object {
        private const val TAG = "SeriesDetailVM"
    }

    private val _seriesInfo = MutableStateFlow<SeriesInfo?>(null)
    val seriesInfo: StateFlow<SeriesInfo?> = _seriesInfo.asStateFlow()

    // Season tabs derived from episode map keys
    private val _seasonTabs = MutableStateFlow<List<SeasonTab>>(emptyList())
    val seasonTabs: StateFlow<List<SeasonTab>> = _seasonTabs.asStateFlow()

    private val _selectedSeasonKey = MutableStateFlow("")
    val selectedSeasonKey: StateFlow<String> = _selectedSeasonKey.asStateFlow()

    private val _episodes = MutableStateFlow<List<Episode>>(emptyList())
    val episodes: StateFlow<List<Episode>> = _episodes.asStateFlow()

    private val _episodeWatchProgress = MutableStateFlow<Map<String, WatchProgressEntity>>(emptyMap())
    val episodeWatchProgress: StateFlow<Map<String, WatchProgressEntity>> = _episodeWatchProgress.asStateFlow()

    private var loadedSeriesId: Int = 0

    fun loadSeriesInfo(seriesId: Int) {
        Log.i(TAG, "loadSeriesInfo called with seriesId=$seriesId")
        if (seriesId == 0) {
            Log.e(TAG, "seriesId is 0 — invalid, aborting")
            return
        }
        loadedSeriesId = seriesId
        viewModelScope.launch {
            _isLoading.value = true
            try {
                Log.i(TAG, "Calling API getSeriesInfo($seriesId)...")
                val info = contentRepository.getSeriesInfo(seriesId)
                Log.i(TAG, "API success: name=${info.info?.name}, seasons=${info.seasons?.size}, episodeMapKeys=${info.episodes?.keys}")

                _seriesInfo.value = info

                // Load watch progress for all episodes in this series
                loadWatchProgress(seriesId)

                // Build season tabs from the actual episode map keys
                val episodeKeys = info.episodes?.keys?.toList()?.sortedBy {
                    it.toIntOrNull() ?: Int.MAX_VALUE
                } ?: emptyList()
                Log.i(TAG, "Episode keys (sorted): $episodeKeys")

                if (episodeKeys.isEmpty()) {
                    Log.i(TAG, "No episode keys found! episodes map is ${if (info.episodes == null) "NULL" else "EMPTY"}")
                }

                val seasons = info.seasons ?: emptyList()
                val tabs = episodeKeys.mapIndexed { index, key ->
                    // Try to match a season name from the seasons list
                    val matchedSeason = seasons.find { it.seasonNumber.toString() == key }
                        ?: seasons.getOrNull(index)
                    val name = matchedSeason?.name ?: "Season $key"
                    SeasonTab(key = key, displayName = name)
                }
                Log.i(TAG, "Built ${tabs.size} season tabs: ${tabs.map { "${it.key}→${it.displayName}" }}")

                _seasonTabs.value = tabs

                // Auto-select first season
                tabs.firstOrNull()?.let {
                    Log.i(TAG, "Auto-selecting first season: key=${it.key}")
                    selectSeason(it.key)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load series info for seriesId=$seriesId", e)
                _error.emit(e.message ?: "Failed to load series info")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun selectSeason(key: String) {
        _selectedSeasonKey.value = key
        val info = _seriesInfo.value
        if (info == null) {
            Log.e(TAG, "selectSeason($key): _seriesInfo is null!")
            return
        }
        val eps = info.episodes?.get(key) ?: emptyList()
        Log.i(TAG, "selectSeason($key): found ${eps.size} episodes" +
            if (eps.isNotEmpty()) ", first: id=${eps[0].id} title=${eps[0].title}" else "")
        _episodes.value = eps
    }

    fun buildEpisodeUrl(episode: Episode): String {
        val ext = episode.containerExtension ?: "mp4"
        val id = episode.id?.toIntOrNull() ?: 0
        return contentRepository.buildSeriesStreamUrl(id, ext)
    }

    private suspend fun loadWatchProgress(seriesId: Int) {
        try {
            val progressList = watchProgressRepository.getSeriesEpisodes(seriesId)
            _episodeWatchProgress.value = progressList.associateBy { it.streamId }
            Log.i(TAG, "Loaded watch progress: ${progressList.size} episodes tracked")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load watch progress", e)
        }
    }

    /** Called from Fragment onResume to refresh after returning from player */
    fun refreshWatchProgress() {
        if (loadedSeriesId == 0) return
        viewModelScope.launch {
            loadWatchProgress(loadedSeriesId)
        }
    }
}
