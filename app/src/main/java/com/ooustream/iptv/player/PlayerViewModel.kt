package com.ooustream.iptv.player

import androidx.lifecycle.viewModelScope
import com.ooustream.iptv.common.BaseViewModel
import com.ooustream.iptv.data.local.dao.SeriesTrackingDao
import com.ooustream.iptv.data.local.entity.SeriesTrackingEntity
import com.ooustream.iptv.data.local.entity.WatchProgressEntity
import com.ooustream.iptv.data.model.ContentType
import com.ooustream.iptv.data.model.LiveStream
import com.ooustream.iptv.data.repository.ContentRepository
import com.ooustream.iptv.data.repository.WatchAnalyticsRepository
import com.ooustream.iptv.data.repository.WatchProgressRepository
import com.ooustream.iptv.recommendation.RecommendationEngine
import com.ooustream.iptv.recommendation.RecommendedItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val contentRepository: ContentRepository,
    private val watchProgressRepository: WatchProgressRepository,
    private val watchAnalyticsRepository: WatchAnalyticsRepository,
    private val recommendationEngine: RecommendationEngine,
    private val seriesTrackingDao: SeriesTrackingDao
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
    // Bare series name ("The Closer (2005)") for building next-episode display titles. streamName
    // is the full display title and must NEVER be used as a series-name source — that's what used
    // to compound titles across binge advances.
    var seriesName: String = ""

    // Session cache of get_series_info so binge boundaries don't re-hit the network. Without this a
    // transient network blip made resolveNextEpisode() throw → null → a false "No more episodes" /
    // series-complete mid-binge. Cached per seriesId for the life of the player session.
    private var cachedSeriesInfo: com.ooustream.iptv.data.model.SeriesInfo? = null
    private var cachedSeriesInfoId: Int = 0

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
        if (list.size < 2) return null
        val raw = _currentChannelIndex.value + direction
        val newIndex = ((raw % list.size) + list.size) % list.size  // wrap around
        _currentChannelIndex.value = newIndex
        return list[newIndex]
    }

    fun getResumePosition(callback: (Long) -> Unit) {
        viewModelScope.launch {
            val progress = watchProgressRepository.getProgress(streamId)
            if (progress != null && !progress.completed && progress.progressPercent > 0.05f) {
                callback(progress.position)
            }
        }
    }

    /** Suspend version — returns saved resume position directly (0 if none or completed).
     *  Used by libVLC swap to fetch the DB position when ExoPlayer hasn't had time to seek. */
    suspend fun getResumePositionSync(): Long {
        if (contentType == ContentType.LIVE) return 0L
        val progress = watchProgressRepository.getProgress(streamId)
        return if (progress != null && !progress.completed && progress.progressPercent > 0.05f) {
            progress.position
        } else 0L
    }

    fun saveProgress(position: Long, duration: Long, percent: Float) {
        if (contentType == ContentType.LIVE) return
        // Snapshot identity SYNCHRONOUSLY at call time. The gapless binge advance swaps the
        // identity fields right after finalizing the previous episode — a coroutine that reads
        // the fields lazily would attribute the old episode's 100% save to the new one.
        val entity = WatchProgressEntity(
            streamId = streamId,
            type = if (contentType == ContentType.VOD) "vod" else "series",
            name = streamName,
            icon = streamIcon.ifBlank { null },
            position = position,
            duration = duration,
            progressPercent = percent,
            extra = streamUrl.ifBlank { null },
            seriesId = if (contentType == ContentType.SERIES && seriesId > 0) seriesId else null,
            seasonNum = if (contentType == ContentType.SERIES) seasonNum else null,
            episodeNum = if (contentType == ContentType.SERIES) episodeNum else null,
            completed = percent >= 0.95f
        )
        val trackSeries = contentType == ContentType.SERIES && seriesId > 0 && seasonNum > 0
        val snapSeriesId = seriesId
        val snapSeason = seasonNum
        val snapEpisode = episodeNum
        val snapEpisodeId = streamId.toIntOrNull() ?: 0
        val snapTitle = streamName.substringBefore(" - ").trim()
        val snapPoster = streamIcon.ifBlank { null }
        viewModelScope.launch {
            withContext(NonCancellable) {
                watchProgressRepository.saveProgress(entity)
                // Upsert series tracking for new episode detection
                if (trackSeries) {
                    upsertSeriesTracking(snapSeriesId, snapSeason, snapEpisode, snapEpisodeId, snapTitle, snapPoster)
                }
            }
        }
    }

    private suspend fun upsertSeriesTracking(
        seriesId: Int, seasonNum: Int, episodeNum: Int,
        episodeId: Int, seriesTitle: String, posterUrl: String?
    ) {
        val existing = seriesTrackingDao.getTracking(seriesId)
        val now = System.currentTimeMillis()
        if (existing != null) {
            // Only update if this episode is further than last watched
            if (seasonNum > existing.lastWatchedSeason ||
                (seasonNum == existing.lastWatchedSeason && episodeNum > existing.lastWatchedEpisode)
            ) {
                seriesTrackingDao.upsert(
                    existing.copy(
                        lastWatchedSeason = seasonNum,
                        lastWatchedEpisode = episodeNum,
                        lastWatchedEpisodeId = episodeId,
                        lastWatchedAt = now
                    )
                )
            } else {
                // Still update timestamp
                seriesTrackingDao.upsert(existing.copy(lastWatchedAt = now))
            }
        } else {
            seriesTrackingDao.upsert(
                SeriesTrackingEntity(
                    seriesId = seriesId,
                    seriesTitle = seriesTitle,
                    posterUrl = posterUrl,
                    lastWatchedSeason = seasonNum,
                    lastWatchedEpisode = episodeNum,
                    lastWatchedEpisodeId = episodeId,
                    lastWatchedAt = now
                )
            )
        }
    }

    fun markCompleted() {
        if (contentType == ContentType.LIVE) return
        val id = streamId   // snapshot — see saveProgress comment
        viewModelScope.launch {
            withContext(NonCancellable) {
                watchProgressRepository.markCompleted(id)
            }
        }
    }

    /**
     * Mark current series episode as completed and insert the next episode
     * as an "Up Next" entry in watch_progress so it appears in Continue Watching.
     */
    suspend fun advanceSeriesOnCompletion() {
        if (contentType != ContentType.SERIES || seriesId == 0) return
        watchProgressRepository.markCompleted(streamId)
        val next = resolveNextEpisode() ?: return
        insertUpNextRow(next)
    }

    /** Insert an "Up Next" watch_progress row for [next] so it appears in Continue Watching. */
    suspend fun insertUpNextRow(next: NextEpisodeResult) {
        if (contentType != ContentType.SERIES || seriesId == 0) return
        watchProgressRepository.saveProgress(
            WatchProgressEntity(
                streamId = next.episodeId,
                type = "series",
                name = next.name,
                icon = streamIcon.ifBlank { null },
                position = 0,
                duration = 1,
                progressPercent = 0.06f,
                extra = next.url,
                seriesId = seriesId,
                seasonNum = next.season,
                episodeNum = next.episodeNum,
                completed = false
            )
        )
    }

    /** Fire-and-forget [insertUpNextRow] — survives view teardown (NonCancellable, viewModelScope). */
    fun queueUpNextRow(next: NextEpisodeResult) {
        viewModelScope.launch {
            withContext(NonCancellable) { insertUpNextRow(next) }
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

    /**
     * Authoritative container extension for the current VOD title, from get_vod_info.
     * Several play surfaces default to "mp4" when their listing item lost the extension —
     * but panels only serve a file at its EXACT extension (customer report: Kung Fu Panda
     * is .m2ts; .mp4/.mkv requests got HTTP 551). Null on failure / non-VOD.
     */
    suspend fun fetchRealVodExtension(): String? {
        if (contentType != ContentType.VOD) return null
        val id = streamId.toIntOrNull() ?: return null
        return try {
            contentRepository.getVodInfo(id).movieData?.containerExtension?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    fun buildLiveUrl(stream: LiveStream): String {
        return contentRepository.buildLiveStreamUrl(stream.streamId)
    }

    fun buildVodStreamUrl(streamId: Int, ext: String): String {
        return contentRepository.buildVodStreamUrl(streamId, ext)
    }

    suspend fun getWatchNextSuggestions(): List<RecommendedItem> {
        return try {
            recommendationEngine.getRecommendations(20)
                .filter { it.type == "vod" }
                .filter { it.streamId.toString() != streamId }
                .take(6)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Resolve the next episode for binge-watch.
     * Fetches series info from API, finds the current episode position,
     * then returns the next episode (same season or first of next season).
     */
    /** Session-cached series info; only the FIRST boundary hits the network, the rest reuse it. */
    private suspend fun seriesInfoCached(): com.ooustream.iptv.data.model.SeriesInfo? {
        cachedSeriesInfo?.let { if (cachedSeriesInfoId == seriesId) return it }
        return try {
            val info = contentRepository.getSeriesInfo(seriesId)
            cachedSeriesInfo = info
            cachedSeriesInfoId = seriesId
            info
        } catch (_: Exception) {
            null // transient failure — caller treats this as "couldn't look up", not "exhausted"
        }
    }

    suspend fun resolveNextEpisode(): NextEpisodeResult? {
        if (seriesId == 0) return null
        return try {
            val info = seriesInfoCached() ?: return null
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
                return buildNextResult(next, resolvedSeriesName(info.info?.name))
            }

            // Try first episode of next season
            val currentSeasonIdx = sortedSeasons.indexOf(currentSeasonKey)
            if (currentSeasonIdx + 1 < sortedSeasons.size) {
                val nextSeasonKey = sortedSeasons[currentSeasonIdx + 1]
                val nextEpisodes = episodesMap[nextSeasonKey]
                if (!nextEpisodes.isNullOrEmpty()) {
                    return buildNextResult(nextEpisodes.first(), resolvedSeriesName(info.info?.name))
                }
            }

            null // No more episodes
        } catch (e: Exception) {
            null
        }
    }

    /** Series name for display titles: API name → seriesName arg → first segment of streamName.
     *  Never the full streamName — that compounds display titles across binge advances. */
    private fun resolvedSeriesName(apiName: String?): String =
        apiName?.takeIf { it.isNotBlank() }
            ?: seriesName.ifBlank { streamName.substringBefore(" - ").substringBefore(" – ") }

    /**
     * Null when the next episode's listing carries no usable stream id — the caller treats that the
     * same as "no more episodes", which is honest: a listing we cannot build a URL for is not
     * playable. Previously this coerced a bad id to `0` and handed back a URL that was not only
     * unplayable but got PERSISTED into `watch_progress.extra` by [insertUpNextRow], so one bad
     * listing seeded a permanently broken Continue Watching row.
     */
    private fun buildNextResult(episode: com.ooustream.iptv.data.model.Episode, seriesName: String): NextEpisodeResult? {
        val id = com.ooustream.iptv.data.model.StreamUrlBuilder.episodeStreamId(episode.id) ?: return null
        val url = contentRepository.buildSeriesStreamUrl(
            id,
            com.ooustream.iptv.data.model.StreamUrlBuilder.sanitizeExt(episode.containerExtension)
        )
        val name = com.ooustream.iptv.common.MediaTitleFormatter.episodeTitle(
            seriesName, episode.title, episode.episodeNum, seasonNum = episode.season ?: 0
        )
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
