package com.ooustream.iptv.recommendation

import com.ooustream.iptv.data.local.dao.FavoriteDao
import com.ooustream.iptv.data.repository.ContentRepository
import com.ooustream.iptv.data.repository.WatchAnalyticsRepository
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

data class BecauseYouWatchedRow(
    val seedTitle: String,
    val seedIcon: String?,
    val recommendations: List<RecommendedItem>
)

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
        cachedGrouped = null
        groupedCacheTimestamp = 0
    }

    // ── Because You Watched rows ────────────────────────────────────────

    private var cachedGrouped: List<BecauseYouWatchedRow>? = null
    private var groupedCacheTimestamp: Long = 0

    /**
     * Returns up to [maxRows] "Because You Watched X" rows, each seeded by a
     * distinct recently-watched VOD/Series title. Recommendations are matched
     * by **genre and cast overlap** — not just same provider category.
     *
     * VOD: fetches seed info for genre/cast, then scores candidates from same
     * category pool by similarity. Series: genre/cast already on list objects.
     */
    suspend fun getGroupedRecommendations(maxRows: Int = 3): List<BecauseYouWatchedRow> {
        val now = System.currentTimeMillis()
        if (cachedGrouped != null && now - groupedCacheTimestamp < CACHE_DURATION) {
            return cachedGrouped!!
        }

        val rows = mutableListOf<BecauseYouWatchedRow>()

        try {
            val recentlyWatched = watchAnalyticsRepository.getRecentlyWatched(20)
            val eligible = recentlyWatched.filter { it.type == "vod" || it.type == "series" }
            if (eligible.isEmpty()) {
                cachedGrouped = emptyList()
                groupedCacheTimestamp = now
                return emptyList()
            }

            val watchedStreamIds = recentlyWatched.map { "${it.type}_${it.streamId}" }.toSet()

            // Pick seeds from distinct categories for variety
            val seenCategoryIds = mutableSetOf<String?>()
            val seeds = eligible.filter { entity ->
                if (entity.categoryId in seenCategoryIds) return@filter false
                seenCategoryIds.add(entity.categoryId)
                true
            }.take(maxRows)

            for (seed in seeds) {
                try {
                    val candidates: List<RecommendedItem> = when (seed.type) {
                        "vod" -> buildVodRow(seed.streamId, seed.categoryId, watchedStreamIds)
                        "series" -> buildSeriesRow(seed.streamId, seed.categoryId, watchedStreamIds)
                        else -> emptyList()
                    }
                    if (candidates.isNotEmpty()) {
                        val seedTitle = candidates.first().reason
                            .removePrefix("Because You Watched ")
                        val (_, seedIcon) = resolveSeedTitleAndIcon(seed.type, seed.streamId, seed.categoryId)
                        rows.add(BecauseYouWatchedRow(seedTitle, seedIcon, candidates))
                    }
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }

        cachedGrouped = rows
        groupedCacheTimestamp = System.currentTimeMillis()
        return rows
    }

    /**
     * Build a "Because You Watched" row for a VOD seed by fetching the seed's
     * genre/cast from the API, then scoring candidates by overlap.
     */
    private suspend fun buildVodRow(
        seedId: Int,
        categoryId: String?,
        watchedIds: Set<String>
    ): List<RecommendedItem> {
        // Fetch seed metadata for genre + cast
        val seedInfo = try { contentRepository.getVodInfo(seedId) } catch (_: Exception) { return emptyList() }
        val seedTitle = seedInfo.movieData?.name ?: seedInfo.info?.let { "" } ?: return emptyList()
        val seedGenres = parseTokens(seedInfo.info?.genre)
        val seedCast = parseTokens(seedInfo.info?.cast)
        Log.w("OOUSTREAM", "BYW seed: '$seedTitle' genres=$seedGenres cast=${seedCast.take(3)}")
        if (seedGenres.isEmpty() && seedCast.isEmpty()) {
            Log.w("OOUSTREAM", "BYW seed '$seedTitle': no genre/cast data — skipping")
            return emptyList()
        }

        // Search ALL VOD for genre/cast matches — not limited to same category.
        // Shuffle to get variety, then cap at 40 info fetches to avoid overloading.
        val allVod = contentRepository.getVodStreams()
        val unwatched = allVod
            .filter { "vod_${it.streamId}" !in watchedIds && it.streamId != seedId }
            .shuffled()
            .take(40)

        // Fetch info for candidates in parallel to get genre/cast
        val scored = coroutineScope {
            unwatched.map { stream ->
                async {
                    try {
                        val info = contentRepository.getVodInfo(stream.streamId)
                        val genres = parseTokens(info.info?.genre)
                        val cast = parseTokens(info.info?.cast)
                        val genreOverlap = (seedGenres intersect genres).size.toFloat() /
                                maxOf(seedGenres.size, 1).toFloat()
                        val castOverlap = (seedCast intersect cast).size.toFloat() /
                                maxOf(seedCast.size, 1).toFloat()
                        val score = genreOverlap * 0.6f + castOverlap * 0.4f
                        if (score > 0f) {
                            RecommendedItem(
                                streamId = stream.streamId,
                                name = stream.name,
                                icon = stream.streamIcon,
                                type = "vod",
                                score = score,
                                reason = "Because You Watched $seedTitle",
                                containerExtension = stream.containerExtension
                            )
                        } else null
                    } catch (_: Exception) { null }
                }
            }.awaitAll().filterNotNull()
        }

        val result = scored.sortedByDescending { it.score }.take(8)
        Log.w("OOUSTREAM", "BYW '$seedTitle': ${scored.size} genre/cast matches from ${unwatched.size} candidates → showing ${result.size}")
        return result
    }

    /**
     * Build a "Because You Watched" row for a Series seed.
     * Series objects already have genre/cast — no extra API calls needed.
     */
    private suspend fun buildSeriesRow(
        seedId: Int,
        categoryId: String?,
        watchedIds: Set<String>
    ): List<RecommendedItem> {
        // Find the seed series to get genre/cast
        val allSeries = contentRepository.getSeries()
        val seedSeries = allSeries.find { it.seriesId == seedId } ?: return emptyList()
        val seedTitle = seedSeries.name
        val seedGenres = parseTokens(seedSeries.genre)
        val seedCast = parseTokens(seedSeries.cast)
        if (seedGenres.isEmpty() && seedCast.isEmpty()) return emptyList()

        // Score all unwatched series by genre/cast overlap
        val scored = allSeries
            .filter { "series_${it.seriesId}" !in watchedIds && it.seriesId != seedId }
            .mapNotNull { s ->
                val genres = parseTokens(s.genre)
                val cast = parseTokens(s.cast)
                val genreOverlap = (seedGenres intersect genres).size.toFloat() /
                        maxOf(seedGenres.size, 1).toFloat()
                val castOverlap = (seedCast intersect cast).size.toFloat() /
                        maxOf(seedCast.size, 1).toFloat()
                val score = genreOverlap * 0.6f + castOverlap * 0.4f
                if (score > 0f) {
                    RecommendedItem(
                        streamId = s.seriesId,
                        name = s.name,
                        icon = s.cover,
                        type = "series",
                        score = score,
                        reason = "Because You Watched $seedTitle"
                    )
                } else null
            }

        return scored.sortedByDescending { it.score }.take(8)
    }

    /**
     * Parse a comma-separated genre or cast string into a normalized set of tokens.
     * "Action, Sci-Fi, Thriller" → {"action", "sci-fi", "thriller"}
     */
    private fun parseTokens(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    /**
     * Resolves the display title and icon for a seed item. Because
     * WatchAnalyticsEntity has no name field, we look it up from the
     * content catalogue using streamId + categoryId.
     */
    private suspend fun resolveSeedTitleAndIcon(
        type: String,
        streamId: Int,
        categoryId: String?
    ): Pair<String, String?> {
        return try {
            when (type) {
                "vod" -> {
                    // Try category-scoped fetch first (faster cache hit), fall back to all
                    val streams = if (categoryId != null)
                        contentRepository.getVodStreams(categoryId)
                    else
                        contentRepository.getVodStreams()
                    val match = streams.find { it.streamId == streamId }
                    Pair(match?.name ?: "", match?.streamIcon)
                }
                "series" -> {
                    val seriesList = if (categoryId != null)
                        contentRepository.getSeries(categoryId)
                    else
                        contentRepository.getSeries()
                    val match = seriesList.find { it.seriesId == streamId }
                    Pair(match?.name ?: "", match?.cover)
                }
                else -> Pair("", null)
            }
        } catch (_: Exception) {
            Pair("", null)
        }
    }
}
