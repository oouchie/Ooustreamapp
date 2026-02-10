package com.ooustream.iptv.player

import androidx.lifecycle.viewModelScope
import com.ooustream.iptv.common.BaseViewModel
import com.ooustream.iptv.data.local.entity.WatchProgressEntity
import com.ooustream.iptv.data.model.ContentType
import com.ooustream.iptv.data.model.LiveStream
import com.ooustream.iptv.data.repository.ContentRepository
import com.ooustream.iptv.data.repository.WatchAnalyticsRepository
import com.ooustream.iptv.data.repository.WatchProgressRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val contentRepository: ContentRepository,
    private val watchProgressRepository: WatchProgressRepository,
    private val watchAnalyticsRepository: WatchAnalyticsRepository
) : BaseViewModel() {

    var hasResumed = false
    var streamUrl: String = ""
    var contentType: ContentType = ContentType.LIVE
    var streamId: String = ""
    var streamName: String = ""
    var streamIcon: String = ""

    // Series binge-watch context
    var seriesId: Int = 0
    var seasonNum: Int = 0
    var episodeNum: Int = 0

    // Channel list for live TV switching
    private val _channels = MutableStateFlow<List<LiveStream>>(emptyList())
    val channels: StateFlow<List<LiveStream>> = _channels.asStateFlow()

    private val _currentChannelIndex = MutableStateFlow(0)
    val currentChannelIndex: StateFlow<Int> = _currentChannelIndex.asStateFlow()

    fun setChannels(list: List<LiveStream>, currentIndex: Int) {
        _channels.value = list
        _currentChannelIndex.value = currentIndex
    }

    fun switchChannel(direction: Int): LiveStream? {
        val list = _channels.value
        if (list.isEmpty()) return null
        val newIndex = (_currentChannelIndex.value + direction).coerceIn(0, list.lastIndex)
        if (newIndex == _currentChannelIndex.value) return null
        _currentChannelIndex.value = newIndex
        return list[newIndex]
    }

    fun getResumePosition(callback: (Long) -> Unit) {
        viewModelScope.launch {
            val progress = watchProgressRepository.getProgress(streamId)
            if (progress != null && progress.progressPercent in 0.05f..0.95f) {
                callback(progress.position)
            }
        }
    }

    fun saveProgress(position: Long, duration: Long, percent: Float) {
        if (contentType == ContentType.LIVE) return
        viewModelScope.launch {
            watchProgressRepository.saveProgress(
                WatchProgressEntity(
                    streamId = streamId,
                    type = if (contentType == ContentType.VOD) "vod" else "series",
                    name = streamName,
                    icon = streamIcon.ifBlank { null },
                    position = position,
                    duration = duration,
                    progressPercent = percent,
                    extra = streamUrl.ifBlank { null }
                )
            )
        }
    }

    fun recordPlayStart(categoryId: String? = null) {
        val type = when (contentType) {
            ContentType.LIVE -> "live"
            ContentType.VOD -> "vod"
            ContentType.SERIES -> "series"
        }
        val id = streamId.toIntOrNull() ?: return
        viewModelScope.launch {
            watchAnalyticsRepository.recordPlay(id, categoryId, type)
        }
    }

    fun buildLiveUrl(stream: LiveStream): String {
        return contentRepository.buildLiveStreamUrl(stream.streamId)
    }

    /**
     * Resolve the next episode for binge-watch.
     * Fetches series info from API, finds the current episode position,
     * then returns the next episode (same season or first of next season).
     */
    suspend fun resolveNextEpisode(): NextEpisodeResult? {
        if (seriesId == 0) return null
        return try {
            val info = contentRepository.getSeriesInfo(seriesId)
            val episodesMap = info.episodes ?: return null

            // Sort season keys numerically
            val sortedSeasons = episodesMap.keys.sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }

            // Find current season key
            val currentSeasonKey = sortedSeasons.find { key ->
                val eps = episodesMap[key] ?: emptyList()
                eps.any { it.episodeNum == episodeNum && (it.season == seasonNum || seasonNum == 0) }
            } ?: sortedSeasons.find { it.toIntOrNull() == seasonNum }
            ?: return null

            val currentEpisodes = episodesMap[currentSeasonKey] ?: return null
            val currentIdx = currentEpisodes.indexOfFirst { it.episodeNum == episodeNum }
            if (currentIdx < 0) return null

            // Try next episode in same season
            if (currentIdx + 1 < currentEpisodes.size) {
                val next = currentEpisodes[currentIdx + 1]
                return buildNextResult(next, info.info?.name ?: streamName)
            }

            // Try first episode of next season
            val currentSeasonIdx = sortedSeasons.indexOf(currentSeasonKey)
            if (currentSeasonIdx + 1 < sortedSeasons.size) {
                val nextSeasonKey = sortedSeasons[currentSeasonIdx + 1]
                val nextEpisodes = episodesMap[nextSeasonKey]
                if (!nextEpisodes.isNullOrEmpty()) {
                    return buildNextResult(nextEpisodes.first(), info.info?.name ?: streamName)
                }
            }

            null // No more episodes
        } catch (e: Exception) {
            null
        }
    }

    private fun buildNextResult(episode: com.ooustream.iptv.data.model.Episode, seriesName: String): NextEpisodeResult {
        val ext = episode.containerExtension ?: "mp4"
        val id = episode.id?.toIntOrNull() ?: 0
        val url = contentRepository.buildSeriesStreamUrl(id, ext)
        val epTitle = episode.title ?: "E${episode.episodeNum}"
        val name = "$seriesName - $epTitle"
        return NextEpisodeResult(
            url = url,
            episodeId = episode.id ?: "",
            name = name,
            season = episode.season ?: 0,
            episodeNum = episode.episodeNum
        )
    }
}

data class NextEpisodeResult(
    val url: String,
    val episodeId: String,
    val name: String,
    val season: Int,
    val episodeNum: Int
)
