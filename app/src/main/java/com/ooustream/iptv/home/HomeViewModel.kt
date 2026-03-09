package com.ooustream.iptv.home

import android.graphics.Color
import com.ooustream.iptv.R
import com.ooustream.iptv.common.BaseViewModel
import com.ooustream.iptv.data.local.dao.SeriesTrackingDao
import com.ooustream.iptv.data.local.entity.SeriesTrackingEntity
import com.ooustream.iptv.data.local.entity.WatchProgressEntity
import com.ooustream.iptv.data.model.Series
import com.ooustream.iptv.data.model.VodStream
import com.ooustream.iptv.data.repository.ContentRepository
import com.ooustream.iptv.data.repository.EpgCacheRepository
import com.ooustream.iptv.data.repository.PredictivePreFetcher
import com.ooustream.iptv.data.repository.WatchProgressRepository
import com.ooustream.iptv.epg.SmartEpgFiller
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
    val containerExtension: String = ""
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
    private val seriesTrackingDao: SeriesTrackingDao
) : BaseViewModel() {

    init {
        // Kick off predictive pre-fetching of EPG data for top channels (WiFi only)
        predictivePreFetcher.prefetchIfNeeded()

        // Load personalized recommendations in background
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _forYouContent.value = recommendationEngine.getRecommendations()
            } catch (_: Exception) { }
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

    private val _forYouLiveNow = MutableStateFlow<List<ForYouChannel>>(emptyList())
    val forYouLiveNow: StateFlow<List<ForYouChannel>> = _forYouLiveNow.asStateFlow()

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
            val vodStreams = contentRepository.getVodStreams()
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

            // Trending: next 20 titles
            _trendingContent.value = sorted.drop(6).take(20)

            // Fetch banner/backdrop images + trending series in parallel
            coroutineScope {
                // Trending Series: most recently updated
                launch {
                    try {
                        val seriesList = contentRepository.getSeries()
                        _trendingSeries.value = seriesList
                            .sortedByDescending { it.lastModified?.toLongOrNull() ?: 0L }
                            .take(20)
                    } catch (_: Exception) { }
                }

                val featuredWithBanners = heroVods.map { vod ->
                    async {
                        try {
                            val info = contentRepository.getVodInfo(vod.streamId)
                            val backdrop = info.info?.backdropPath?.firstOrNull()
                                ?: info.info?.movieImage
                            val genre = info.info?.genre ?: vod.categoryId ?: ""
                            FeaturedItem(
                                title = vod.name,
                                backdropUrl = backdrop ?: vod.streamIcon,
                                type = "vod",
                                streamId = vod.streamId.toString(),
                                genre = genre,
                                containerExtension = info.movieData?.containerExtension ?: vod.containerExtension ?: "mp4"
                            )
                        } catch (_: Exception) {
                            // Fall back to poster if info fetch fails
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
}
