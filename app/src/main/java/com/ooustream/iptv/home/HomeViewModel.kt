package com.ooustream.iptv.home

import android.graphics.Color
import com.ooustream.iptv.R
import com.ooustream.iptv.common.BaseViewModel
import com.ooustream.iptv.data.local.dao.FavoriteDao
import com.ooustream.iptv.data.local.dao.SeriesTrackingDao
import com.ooustream.iptv.data.local.entity.SeriesTrackingEntity
import com.ooustream.iptv.data.local.entity.WatchProgressEntity
import com.ooustream.iptv.data.model.Series
import com.ooustream.iptv.data.model.VodStream
import com.ooustream.iptv.data.repository.ContentRepository
import com.ooustream.iptv.data.repository.EpgCacheRepository
import com.ooustream.iptv.data.repository.PredictivePreFetcher
import com.ooustream.iptv.data.repository.WatchAnalyticsRepository
import com.ooustream.iptv.data.repository.WatchProgressRepository
import com.ooustream.iptv.parental.ContentFilterManager
import kotlin.math.ln
import com.ooustream.iptv.epg.ChannelContentType
import com.ooustream.iptv.epg.ChannelNameParser
import com.ooustream.iptv.epg.SmartEpgFiller
import com.ooustream.iptv.recommendation.BecauseYouWatchedRow
import com.ooustream.iptv.recommendation.ChannelRecommendationEngine
import com.ooustream.iptv.recommendation.RecommendationEngine
import com.ooustream.iptv.recommendation.RecommendedItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import javax.inject.Inject

data class FeaturedItem(
    val title: String,
    val backdropUrl: String?,
    val type: String,
    val streamId: String,
    val genre: String = "",
    val containerExtension: String = "",
    val rating: String = "",
    val year: String = "",
    val plot: String? = null
)

data class LiveSportsEvent(
    val channelId: Int,
    val channelName: String,
    val channelIcon: String?,
    val eventTitle: String,
    val streamUrl: String
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val watchProgressRepository: WatchProgressRepository,
    private val contentRepository: ContentRepository,
    private val predictivePreFetcher: PredictivePreFetcher,
    private val recommendationEngine: RecommendationEngine,
    private val channelRecommendationEngine: ChannelRecommendationEngine,
    private val smartEpgFiller: SmartEpgFiller,
    private val epgCacheRepository: EpgCacheRepository,
    private val seriesTrackingDao: SeriesTrackingDao,
    private val watchAnalyticsRepository: WatchAnalyticsRepository,
    private val favoriteDao: FavoriteDao,
    private val contentFilterManager: ContentFilterManager
) : BaseViewModel() {

    init {
        // Kick off predictive pre-fetching of EPG data for top channels (WiFi only)
        predictivePreFetcher.prefetchIfNeeded()

        // Load personalized recommendations in background.
        // Prefer grouped "Because You Watched" rows; fall back to flat For You row
        // if no watch history exists.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val grouped = recommendationEngine.getGroupedRecommendations()
                if (grouped.isNotEmpty()) {
                    _becauseYouWatchedRows.value = grouped
                } else {
                    _forYouContent.value = recommendationEngine.getRecommendations()
                }
            } catch (_: Exception) {
                try {
                    _forYouContent.value = recommendationEngine.getRecommendations()
                } catch (_: Exception) { }
            }
        }

        // Load "For You — Live Now" channel recommendations
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!channelRecommendationEngine.hasEnoughData()) return@launch

                // Recompute scores on Home load (fast — bounded data)
                channelRecommendationEngine.recomputeScores()
                val recommendations = channelRecommendationEngine.getRecommendations()

                // Enrich each recommendation with smart EPG
                val channels = recommendations.map { rec ->
                    val nowPlaying = smartEpgFiller.getSmartEpg(
                        realEpgTitle = null,
                        channelId = rec.channelId,
                        channelName = rec.channelName,
                        categoryName = rec.categoryName
                    )
                    ForYouChannel(
                        channelId = rec.channelId,
                        channelName = rec.channelName,
                        channelIcon = rec.channelIcon,
                        categoryName = rec.categoryName,
                        nowPlaying = nowPlaying,
                        contextHint = rec.contextHint
                    )
                }
                _forYouLiveNow.value = channels
            } catch (_: Exception) { }
        }

        // Load Live Sports / Events banner
        viewModelScope.launch(Dispatchers.IO) {
            loadLiveSportsEvent()
        }

        // Load Quick Tune channel strip
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Try personalized channels first (requires enough watch data)
                if (channelRecommendationEngine.hasEnoughData()) {
                    channelRecommendationEngine.recomputeScores()
                    val recs = channelRecommendationEngine.getRecommendations()
                    if (recs.isNotEmpty()) {
                        _channelStripItems.value = recs.take(20).map { rec ->
                            ChannelStripItem(
                                channelId = rec.channelId,
                                channelName = rec.channelName,
                                channelIcon = rec.channelIcon,
                                streamUrl = contentRepository.buildLiveStreamUrl(rec.channelId)
                            )
                        }
                        return@launch
                    }
                }
                // Fallback: favorite live channels
                val favLive = favoriteDao.getFavoritesListByType("live")
                if (favLive.isNotEmpty()) {
                    _channelStripItems.value = favLive.take(20).map { fav ->
                        ChannelStripItem(
                            channelId = fav.streamId,
                            channelName = fav.name,
                            channelIcon = fav.icon,
                            streamUrl = contentRepository.buildLiveStreamUrl(fav.streamId)
                        )
                    }
                } else {
                    // No favorites yet — show first 20 live channels
                    val rawStreams = contentRepository.getLiveStreams()
                    val streams = contentFilterManager.filterContent("live", rawStreams) { it.categoryId }
                    _channelStripItems.value = streams.take(20).map { stream ->
                        ChannelStripItem(
                            channelId = stream.streamId,
                            channelName = stream.name,
                            channelIcon = stream.streamIcon,
                            streamUrl = contentRepository.buildLiveStreamUrl(stream.streamId)
                        )
                    }
                }
            } catch (_: Exception) { }
        }
    }

    val continueWatching: Flow<List<WatchProgressEntity>> =
        watchProgressRepository.getContinueWatching()

    val newEpisodes: Flow<List<SeriesTrackingEntity>> =
        seriesTrackingDao.getSeriesWithNewEpisodes()

    val watchItAgain: Flow<List<WatchProgressEntity>> =
        watchProgressRepository.getCompletedContent()

    private val _featuredContent = MutableStateFlow<List<FeaturedItem>>(emptyList())
    val featuredContent: StateFlow<List<FeaturedItem>> = _featuredContent.asStateFlow()

    private val _forYouContent = MutableStateFlow<List<RecommendedItem>>(emptyList())
    val forYouContent: StateFlow<List<RecommendedItem>> = _forYouContent.asStateFlow()

    private val _becauseYouWatchedRows = MutableStateFlow<List<BecauseYouWatchedRow>>(emptyList())
    val becauseYouWatchedRows: StateFlow<List<BecauseYouWatchedRow>> = _becauseYouWatchedRows.asStateFlow()

    private val _forYouLiveNow = MutableStateFlow<List<ForYouChannel>>(emptyList())
    val forYouLiveNow: StateFlow<List<ForYouChannel>> = _forYouLiveNow.asStateFlow()

    private val _channelStripItems = MutableStateFlow<List<ChannelStripItem>>(emptyList())
    val channelStripItems: StateFlow<List<ChannelStripItem>> = _channelStripItems.asStateFlow()

    private val _liveSportsEvent = MutableStateFlow<LiveSportsEvent?>(null)
    val liveSportsEvent: StateFlow<LiveSportsEvent?> = _liveSportsEvent.asStateFlow()

    private val _trendingContent = MutableStateFlow<List<VodStream>>(emptyList())
    val trendingContent: StateFlow<List<VodStream>> = _trendingContent.asStateFlow()

    private val _trendingSeries = MutableStateFlow<List<Series>>(emptyList())
    val trendingSeries: StateFlow<List<Series>> = _trendingSeries.asStateFlow()

    /** Saved focus state for restoration on back navigation */
    var savedFocusRowId: Int = -1
    var savedFocusPosition: Int = -1

    val sections = listOf(
        SectionItem("live", "Live TV", R.drawable.ic_live_tv, Color.parseColor("#1976D2"),
            cta = "Browse Channels \u2192", gradientStart = Color.parseColor("#0D47A1"), gradientEnd = Color.parseColor("#1976D2")),
        SectionItem("movies", "Movies", R.drawable.ic_movies, Color.parseColor("#9C27B0"),
            cta = "Browse Movies \u2192", gradientStart = Color.parseColor("#4A148C"), gradientEnd = Color.parseColor("#9C27B0")),
        SectionItem("series", "Series", R.drawable.ic_series, Color.parseColor("#FB8C00"),
            cta = "Browse Series \u2192", gradientStart = Color.parseColor("#E65100"), gradientEnd = Color.parseColor("#FB8C00")),
        SectionItem("favorites", "Favorites", R.drawable.ic_favorites, Color.parseColor("#EF4444"),
            cta = "Your Collection \u2192", gradientStart = Color.parseColor("#B71C1C"), gradientEnd = Color.parseColor("#EF4444")),
        SectionItem("search", "Search", R.drawable.ic_search, Color.parseColor("#43A047"),
            cta = "Find Something \u2192", gradientStart = Color.parseColor("#1B5E20"), gradientEnd = Color.parseColor("#43A047")),
        SectionItem("settings", "Settings", R.drawable.ic_settings, Color.parseColor("#9CA3AF"),
            cta = "App Settings \u2192", gradientStart = Color.parseColor("#37474F"), gradientEnd = Color.parseColor("#607D8B"))
    )

    fun buildLiveStreamUrl(streamId: Int): String =
        contentRepository.buildLiveStreamUrl(streamId)

    fun buildVodStreamUrl(streamId: Int, ext: String): String =
        contentRepository.buildVodStreamUrl(streamId, ext)

    fun buildSeriesStreamUrl(streamId: Int, ext: String): String =
        contentRepository.buildSeriesStreamUrl(streamId, ext)

    /**
     * Pre-warm data caches for a section so navigation feels instant.
     * Called by [ScreenPreWarmer] after a 500 ms focus dwell on a section card.
     */
    fun preWarmSection(sectionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (sectionId) {
                    "live" -> contentRepository.getLiveCategories()
                    "movies" -> contentRepository.getVodCategories()
                    "series" -> contentRepository.getSeriesCategories()
                }
            } catch (_: Exception) { }
        }
    }

    suspend fun loadFeaturedContent() {
        try {
            val rawVodStreams = contentRepository.getVodStreams()
            val vodStreams = contentFilterManager.filterContent("vod", rawVodStreams) { it.categoryId }
            val sorted = vodStreams.sortedByDescending { it.added?.toLongOrNull() ?: 0L }
            val heroVods = sorted.take(6)

            // Show posters immediately so hero isn't empty while banners load
            _featuredContent.value = heroVods.map { vod ->
                FeaturedItem(
                    title = vod.name,
                    backdropUrl = vod.streamIcon,
                    type = "vod",
                    streamId = vod.streamId.toString(),
                    genre = vod.categoryId ?: "",
                    containerExtension = vod.containerExtension ?: "mp4"
                )
            }

            // Trending Movies: rating × recency, boosted by user's watched categories
            _trendingContent.value = scoreTrendingVod(vodStreams)

            // Fetch banner/backdrop images + trending series in parallel
            coroutineScope {
                // Trending Series: rating × recency, boosted by user preferences
                launch {
                    try {
                        val rawSeries = contentRepository.getSeries()
                        val seriesList = contentFilterManager.filterContent("series", rawSeries) { it.categoryId }
                        _trendingSeries.value = scoreTrendingSeries(seriesList)
                    } catch (_: Exception) { }
                }

                val featuredWithBanners = heroVods.map { vod ->
                    async {
                        try {
                            val info = contentRepository.getVodInfo(vod.streamId)
                            val backdrop = info.info?.backdropPath?.firstOrNull()
                                ?: info.info?.movieImage
                            val genre = info.info?.genre ?: vod.categoryId ?: ""
                            val ratingStr = info.info?.rating?.let {
                                val num = it.toDoubleOrNull()
                                if (num != null && num > 0) "%.1f".format(num) else ""
                            } ?: ""
                            val yearStr = info.info?.releaseDate?.take(4) ?: ""
                            val plotStr = info.info?.plot?.takeIf { it.length > 10 }
                            FeaturedItem(
                                title = vod.name,
                                backdropUrl = backdrop ?: vod.streamIcon,
                                type = "vod",
                                streamId = vod.streamId.toString(),
                                genre = genre,
                                containerExtension = info.movieData?.containerExtension ?: vod.containerExtension ?: "mp4",
                                rating = ratingStr,
                                year = yearStr,
                                plot = plotStr
                            )
                        } catch (_: Exception) {
                            FeaturedItem(
                                title = vod.name,
                                backdropUrl = vod.streamIcon,
                                type = "vod",
                                streamId = vod.streamId.toString(),
                                genre = vod.categoryId ?: "",
                                containerExtension = vod.containerExtension ?: "mp4"
                            )
                        }
                    }
                }.awaitAll()
                _featuredContent.value = featuredWithBanners
            }
        } catch (e: Exception) {
            _featuredContent.value = emptyList()
            _trendingContent.value = emptyList()
        }
    }

    /**
     * Score VOD streams for "Trending Now" using:
     * - Rating (50%): TMDB rating normalized to 0-1 (filter out < 5.0)
     * - Recency (30%): logarithmic decay — newer content scores higher
     * - User affinity (20%): boost categories the user actually watches
     */
    private suspend fun scoreTrendingVod(allStreams: List<VodStream>): List<VodStream> {
        val userCategoryCounts = try {
            watchAnalyticsRepository.getCategoryWatchCounts("vod")
                .associate { it.categoryId to it.totalCount }
        } catch (_: Exception) { emptyMap() }
        val maxUserCount = userCategoryCounts.values.maxOrNull()?.toFloat() ?: 1f

        val now = System.currentTimeMillis() / 1000
        // Only score content with a rating >= 5.0 (filters out junk)
        val scored = allStreams
            .filter { (it.rating5based ?: 0.0) >= 2.5 || (it.rating?.toDoubleOrNull() ?: 0.0) >= 5.0 }
            .map { vod ->
                val rating10 = vod.rating?.toDoubleOrNull()
                    ?: ((vod.rating5based ?: 0.0) * 2.0)
                val ratingScore = (rating10 / 10.0).coerceIn(0.0, 1.0)

                val addedTs = vod.added?.toLongOrNull() ?: 0L
                val ageDays = ((now - addedTs) / 86400.0).coerceAtLeast(1.0)
                // Logarithmic decay: score 1.0 at day 1, ~0.5 at day 30, ~0.3 at day 180
                val recencyScore = (1.0 / ln(ageDays + 1.0)).coerceIn(0.0, 1.0)

                val userCount = userCategoryCounts[vod.categoryId] ?: 0
                val affinityScore = if (maxUserCount > 0) userCount / maxUserCount else 0f

                val total = ratingScore * 0.50 + recencyScore * 0.30 + affinityScore * 0.20
                vod to total
            }
            .sortedByDescending { it.second }
            .take(20)
            .map { it.first }

        // Fallback: if scoring filters everything, return recently added
        return scored.ifEmpty {
            allStreams.sortedByDescending { it.added?.toLongOrNull() ?: 0L }.take(20)
        }
    }

    /**
     * Score series for "Trending Series" using rating × recency × user affinity.
     * Series objects already have rating and lastModified fields.
     */
    private suspend fun scoreTrendingSeries(allSeries: List<Series>): List<Series> {
        val userCategoryCounts = try {
            watchAnalyticsRepository.getCategoryWatchCounts("series")
                .associate { it.categoryId to it.totalCount }
        } catch (_: Exception) { emptyMap() }
        val maxUserCount = userCategoryCounts.values.maxOrNull()?.toFloat() ?: 1f

        val now = System.currentTimeMillis() / 1000
        val scored = allSeries
            .filter { (it.rating5based ?: 0.0) >= 2.5 || (it.rating?.toDoubleOrNull() ?: 0.0) >= 5.0 }
            .map { series ->
                val rating10 = series.rating?.toDoubleOrNull()
                    ?: ((series.rating5based ?: 0.0) * 2.0)
                val ratingScore = (rating10 / 10.0).coerceIn(0.0, 1.0)

                val modifiedTs = series.lastModified?.toLongOrNull() ?: 0L
                val ageDays = ((now - modifiedTs) / 86400.0).coerceAtLeast(1.0)
                val recencyScore = (1.0 / ln(ageDays + 1.0)).coerceIn(0.0, 1.0)

                val userCount = userCategoryCounts[series.categoryId] ?: 0
                val affinityScore = if (maxUserCount > 0) userCount / maxUserCount else 0f

                val total = ratingScore * 0.50 + recencyScore * 0.30 + affinityScore * 0.20
                series to total
            }
            .sortedByDescending { it.second }
            .take(20)
            .map { it.first }

        return scored.ifEmpty {
            allSeries.sortedByDescending { it.lastModified?.toLongOrNull() ?: 0L }.take(20)
        }
    }

    private suspend fun loadLiveSportsEvent() {
        try {
            val rawStreams = contentRepository.getLiveStreams()
            val streams = contentFilterManager.filterContent("live", rawStreams) { it.categoryId }
            val categories = contentRepository.getLiveCategories()
            val categoryMap = categories.associate { it.categoryId to it.categoryName }

            // Filter to sports channels (capped to avoid too many EPG requests)
            val sportsChannels = streams.filter { stream ->
                val catName = categoryMap[stream.categoryId] ?: ""
                val parsed = ChannelNameParser.parse(stream.name, catName)
                parsed.contentType == ChannelContentType.SPORTS
            }.take(20)

            if (sportsChannels.isEmpty()) return

            val now = System.currentTimeMillis() / 1000

            // Check EPG for each sports channel to find a live event
            // Uses epgCacheRepository.getEpg() which decodes base64-encoded titles
            for (channel in sportsChannels) {
                try {
                    val programs = epgCacheRepository.getEpg(channel.streamId)
                    val liveProgram = programs.firstOrNull { program ->
                        val start = program.startTimestamp?.toLongOrNull() ?: return@firstOrNull false
                        val stop = program.stopTimestamp?.toLongOrNull() ?: return@firstOrNull false
                        now in start..stop && !program.title.isNullOrBlank()
                    }
                    if (liveProgram != null) {
                        _liveSportsEvent.value = LiveSportsEvent(
                            channelId = channel.streamId,
                            channelName = channel.name,
                            channelIcon = channel.streamIcon,
                            eventTitle = liveProgram.title ?: "Live Sports",
                            streamUrl = contentRepository.buildLiveStreamUrl(channel.streamId)
                        )
                        return // Found a live event — done
                    }
                } catch (_: Exception) { }
            }

            // No live EPG found — show top sports channel with generic title
            val topSports = sportsChannels.firstOrNull() ?: return
            _liveSportsEvent.value = LiveSportsEvent(
                channelId = topSports.streamId,
                channelName = topSports.name,
                channelIcon = topSports.streamIcon,
                eventTitle = "Live Sports",
                streamUrl = contentRepository.buildLiveStreamUrl(topSports.streamId)
            )
        } catch (_: Exception) { }
    }
}
