package com.ooustream.iptv.series

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.ooustream.iptv.common.BaseViewModel
import com.ooustream.iptv.data.local.entity.WatchProgressEntity
import com.ooustream.iptv.data.model.Episode
import com.ooustream.iptv.data.model.Season
import com.ooustream.iptv.data.model.SeriesInfo
import com.ooustream.iptv.data.model.StreamUrlBuilder
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

/**
 * What the series screen's one primary button does. Derived purely from the episode list and the
 * series' watch_progress rows — no extra queries.
 */
data class ResumeTarget(
    val episode: Episode,
    val kind: Kind,
    /** Minutes left, only meaningful for [Kind.RESUME]. */
    val minutesLeft: Int = 0
) {
    enum class Kind { RESUME, NEXT, START, REWATCH }
}

@HiltViewModel
class SeriesDetailViewModel @Inject constructor(
    private val contentRepository: ContentRepository,
    private val watchProgressRepository: WatchProgressRepository,
    private val episodeNameResolver: com.ooustream.iptv.data.repository.EpisodeNameResolver
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

    /** Episode id → real episode name from TMDB (filled in after load; may stay empty). */
    private val _episodeNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val episodeNames: StateFlow<Map<String, String>> = _episodeNames.asStateFlow()

    private val _resumeTarget = MutableStateFlow<ResumeTarget?>(null)
    val resumeTarget: StateFlow<ResumeTarget?> = _resumeTarget.asStateFlow()

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
                loadEpisodeNames(info)

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

    /** Seasons are fetched one at a time and published as each lands, first season first. */
    private fun loadEpisodeNames(info: SeriesInfo) {
        val rawName = info.info?.name?.trim().orEmpty()
        val yearInName = Regex("\\((19|20)\\d{2}\\)\\s*$").find(rawName)?.value?.filter(Char::isDigit)?.toIntOrNull()
        val name = rawName.replace(Regex("\\s*\\((19|20)\\d{2}\\)\\s*$"), "").trim()
        val year = info.info?.releaseDate?.take(4)?.toIntOrNull() ?: yearInName
        val bySeason = info.episodes.orEmpty()
        if (name.isBlank() || bySeason.isEmpty()) return
        viewModelScope.launch {
            val keys = bySeason.keys.sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
            for (key in keys) {
                val eps = bySeason[key].orEmpty()
                val seasonNum = eps.firstOrNull()?.season?.takeIf { it > 0 } ?: key.toIntOrNull() ?: continue
                val names = episodeNameResolver.seasonNames(name, year, seasonNum)
                if (names.isEmpty()) continue
                val add = eps.mapNotNull { e -> names[e.episodeNum]?.let { n -> e.id?.let { it to n } } }
                if (add.isNotEmpty()) _episodeNames.value = _episodeNames.value + add
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

    /**
     * Stream URL for an episode, or null when the provider's listing is unusable (missing or
     * non-numeric id). Extension validation happens at the choke point in [StreamUrlBuilder].
     *
     * Returning null instead of the old `?: 0` fallback means a broken listing fails with an honest
     * message rather than requesting stream `0` and surfacing the panel's reply as a server error.
     */
    fun buildEpisodeUrl(episode: Episode): String? {
        val id = StreamUrlBuilder.episodeStreamId(episode.id) ?: run {
            Log.w(TAG, "Unplayable episode listing: id=${episode.id} title=${episode.title}")
            return null
        }
        return contentRepository.buildSeriesStreamUrl(id, StreamUrlBuilder.sanitizeExt(episode.containerExtension))
    }

    private suspend fun loadWatchProgress(seriesId: Int) {
        try {
            val progressList = watchProgressRepository.getSeriesEpisodes(seriesId)
            _episodeWatchProgress.value = progressList.associateBy { it.streamId }
            _resumeTarget.value = computeResumeTarget(_seriesInfo.value, progressList)
            Log.i(TAG, "Loaded watch progress: ${progressList.size} episodes tracked")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load watch progress", e)
        }
    }

    /**
     * Most recently touched episode decides: unfinished → resume it; finished → the episode after
     * it (crossing into the next season); finished the last one → watch again from the start;
     * never watched → the first episode. A progress row whose id isn't in the current listing
     * (provider moved/removed it) is ignored rather than guessed at.
     */
    private fun computeResumeTarget(info: SeriesInfo?, progress: List<WatchProgressEntity>): ResumeTarget? {
        val flat = info?.episodes
            ?.entries
            ?.sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }
            ?.flatMap { (_, eps) -> eps.sortedBy { it.episodeNum } }
            .orEmpty()
        if (flat.isEmpty()) return null
        val latest = progress.filter { !it.dismissed || it.completed }.maxByOrNull { it.lastWatched }
        val idx = latest?.let { p -> flat.indexOfFirst { it.id == p.streamId } } ?: -1
        if (latest == null || idx < 0) return ResumeTarget(flat.first(), ResumeTarget.Kind.START)
        // Up Next placeholder written when the previous episode finished (position 0, duration 1,
        // 6%) — same test ContinueWatchingPresenter uses. It means "play this next", not "resume".
        if (!latest.completed && latest.position == 0L) return ResumeTarget(flat[idx], ResumeTarget.Kind.NEXT)
        if (!latest.completed && latest.progressPercent > 0.02f) {
            val leftMs = (latest.duration - latest.position).coerceAtLeast(0)
            val mins = ((leftMs + 59_999) / 60_000).toInt().coerceAtLeast(1)
            return ResumeTarget(flat[idx], ResumeTarget.Kind.RESUME, mins)
        }
        if (!latest.completed) return ResumeTarget(flat[idx], ResumeTarget.Kind.START)
        return flat.getOrNull(idx + 1)?.let { ResumeTarget(it, ResumeTarget.Kind.NEXT) }
            ?: ResumeTarget(flat.first(), ResumeTarget.Kind.REWATCH)
    }

    /** Called from Fragment onResume to refresh after returning from player */
    fun refreshWatchProgress() {
        if (loadedSeriesId == 0) return
        viewModelScope.launch {
            loadWatchProgress(loadedSeriesId)
        }
    }
}
