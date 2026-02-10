package com.ooustream.iptv.recommendation

import com.ooustream.iptv.data.local.dao.FavoriteDao
import com.ooustream.iptv.data.repository.ContentRepository
import com.ooustream.iptv.data.repository.WatchAnalyticsRepository
import javax.inject.Inject
import javax.inject.Singleton

data class RecommendedItem(
    val streamId: Int,
    val name: String,
    val icon: String?,
    val type: String,       // "live", "vod", "series"
    val score: Float,
    val reason: String,     // "Because you watch Sports", "Trending", etc.
    val containerExtension: String? = null // needed for VOD playback URL
)

@Singleton
class RecommendationEngine @Inject constructor(
    private val watchAnalyticsRepository: WatchAnalyticsRepository,
    private val contentRepository: ContentRepository,
    private val favoriteDao: FavoriteDao
) {
    private var cachedRecommendations: List<RecommendedItem>? = null
    private var cacheTimestamp: Long = 0
    private val CACHE_DURATION = 30 * 60 * 1000L // 30 minutes

    suspend fun getRecommendations(limit: Int = 20): List<RecommendedItem> {
        val now = System.currentTimeMillis()
        if (cachedRecommendations != null && now - cacheTimestamp < CACHE_DURATION) {
            return cachedRecommendations!!
        }

        val recommendations = mutableListOf<RecommendedItem>()

        try {
            // Early exit: no watch history means no recommendations
            val recentlyWatched = watchAnalyticsRepository.getRecentlyWatched(100)
            if (recentlyWatched.isEmpty()) {
                cachedRecommendations = emptyList()
                cacheTimestamp = now
                return emptyList()
            }

            val watchedStreamIds = recentlyWatched
                .map { "${it.type}_${it.streamId}" }.toSet()

            // 1. Category affinity (40% weight) -- find user's top categories
            val topVodCategories = watchAnalyticsRepository.getTopCategoryIds("vod", 5)
            val topSeriesCategories = watchAnalyticsRepository.getTopCategoryIds("series", 3)

            // Get VOD from top categories (most likely to have unseen content)
            val maxVodCount = topVodCategories.firstOrNull()?.totalCount ?: 1
            for (catCount in topVodCategories) {
                try {
                    val streams = contentRepository.getVodStreams(catCount.categoryId)
                    val categoryName = contentRepository.getVodCategories()
                        .find { it.categoryId == catCount.categoryId }?.categoryName ?: "Movies"

                    streams.filter { "vod_${it.streamId}" !in watchedStreamIds }
                        .take(4)
                        .forEach { stream ->
                            recommendations.add(
                                RecommendedItem(
                                    streamId = stream.streamId,
                                    name = stream.name,
                                    icon = stream.streamIcon,
                                    type = "vod",
                                    score = 0.4f * (catCount.totalCount.toFloat() / maxVodCount),
                                    reason = "Because you watch $categoryName",
                                    containerExtension = stream.containerExtension
                                )
                            )
                        }
                } catch (_: Exception) { }
            }

            // Get Series from top categories
            val maxSeriesCount = topSeriesCategories.firstOrNull()?.totalCount ?: 1
            for (catCount in topSeriesCategories) {
                try {
                    val series = contentRepository.getSeries(catCount.categoryId)
                    val categoryName = contentRepository.getSeriesCategories()
                        .find { it.categoryId == catCount.categoryId }?.categoryName ?: "Series"

                    series.filter { "series_${it.seriesId}" !in watchedStreamIds }
                        .take(3)
                        .forEach { s ->
                            recommendations.add(
                                RecommendedItem(
                                    streamId = s.seriesId,
                                    name = s.name,
                                    icon = s.cover,
                                    type = "series",
                                    score = 0.4f * (catCount.totalCount.toFloat() / maxSeriesCount),
                                    reason = "Because you watch $categoryName"
                                )
                            )
                        }
                } catch (_: Exception) { }
            }

            // 2. Favorites-based (20% weight) -- unwatched content in favorited categories
            val coveredVodCategoryIds = topVodCategories.map { it.categoryId }.toSet()
            val favorites = favoriteDao.getFavoritesListByType("vod")
            val favCategoryIds = favorites.mapNotNull { it.categoryId }.distinct().take(3)
            for (catId in favCategoryIds) {
                if (catId in coveredVodCategoryIds) continue // already covered
                try {
                    val streams = contentRepository.getVodStreams(catId)
                    streams.filter { "vod_${it.streamId}" !in watchedStreamIds }
                        .take(2)
                        .forEach { stream ->
                            recommendations.add(
                                RecommendedItem(
                                    streamId = stream.streamId,
                                    name = stream.name,
                                    icon = stream.streamIcon,
                                    type = "vod",
                                    score = 0.2f,
                                    reason = "From your favorites",
                                    containerExtension = stream.containerExtension
                                )
                            )
                        }
                } catch (_: Exception) { }
            }

        } catch (_: Exception) { }

        // Sort by score descending, deduplicate, limit
        val result = recommendations
            .distinctBy { "${it.type}_${it.streamId}" }
            .sortedByDescending { it.score }
            .take(limit)

        cachedRecommendations = result
        cacheTimestamp = System.currentTimeMillis()
        return result
    }

    fun clearCache() {
        cachedRecommendations = null
        cacheTimestamp = 0
    }
}
