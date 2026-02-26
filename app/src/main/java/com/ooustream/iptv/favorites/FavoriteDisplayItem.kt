package com.ooustream.iptv.favorites

/**
 * Enriched display model for the Favorites screen.
 * Built from FavoriteEntity + EPG + WatchProgress + category mapping.
 */
data class FavoriteDisplayItem(
    val id: String,              // "{type}_{streamId}"
    val streamId: Int,
    val type: String,            // "live", "vod", "series"
    val name: String,
    val icon: String?,
    val categoryId: String?,
    val categoryName: String?,   // Resolved from cached categories
    val categoryGroup: String,   // From ChannelCategoryMapper: "Sports", "News", etc.
    val extra: String?,          // Container extension for VOD
    val createdAt: Long,
    val epgTitle: String?,       // Current program title (live only)
    val epgNextTitle: String?,   // Next program title (live only)
    val epgProgress: Float,      // 0.0–1.0 progress into current program
    val score: String?,          // Parsed from EPG title (sports)
    val scoreDetail: String?,    // "Q4 3:12", "2nd Half", etc.
    val lastWatched: Long?,      // From WatchProgressEntity
    val watchProgress: Float,    // VOD/series watch progress 0.0–1.0
    val isLive: Boolean          // type == "live"
)
