package com.ooustream.iptv.favorites

import com.ooustream.iptv.epg.ChannelContentType
import com.ooustream.iptv.epg.ChannelNameParser

/**
 * Maps a favorite's categoryId to a human-readable group name for filter tabs.
 * Reuses ChannelNameParser's network recognition for live channels.
 */
object ChannelCategoryMapper {

    /** Filter tab groups shown in the Favorites UI */
    const val GROUP_ALL = "All"
    const val GROUP_SPORTS = "Sports"
    const val GROUP_NEWS = "News"
    const val GROUP_KIDS = "Kids"
    const val GROUP_ENTERTAINMENT = "Entertainment"
    const val GROUP_LIVE_TV = "Live TV"
    const val GROUP_MOVIES = "Movies"
    const val GROUP_SERIES = "Series"

    /** Ordered list of all filter groups for UI */
    val ALL_GROUPS = listOf(
        GROUP_ALL, GROUP_SPORTS, GROUP_NEWS, GROUP_KIDS,
        GROUP_ENTERTAINMENT, GROUP_LIVE_TV, GROUP_MOVIES, GROUP_SERIES
    )

    // Keyword patterns for category name matching
    private val SPORTS_KEYWORDS = listOf("sport", "espn", "nfl", "nba", "mlb", "nhl", "soccer", "football", "baseball", "basketball", "hockey", "boxing", "ufc", "mma", "tennis", "golf", "cricket", "rugby", "f1", "motorsport", "racing", "wrestling", "bein")
    private val NEWS_KEYWORDS = listOf("news", "cnn", "msnbc", "fox news", "bbc news", "cnbc", "bloomberg", "weather")
    private val KIDS_KEYWORDS = listOf("kid", "cartoon", "disney", "nickelodeon", "nick", "children", "animation", "junior", "pbs kids")
    private val ENTERTAINMENT_KEYWORDS = listOf("entertain", "comedy", "drama", "reality", "lifestyle", "cooking", "food", "travel", "music", "mtv", "vh1")

    /**
     * Determine the group for a favorite item.
     *
     * @param type "live", "vod", or "series"
     * @param channelName The channel/content name
     * @param categoryName The resolved category name (if available)
     * @return One of the GROUP_* constants
     */
    fun mapToGroup(type: String, channelName: String, categoryName: String?): String {
        // VOD and Series have fixed groups
        if (type == "vod") return GROUP_MOVIES
        if (type == "series") return GROUP_SERIES

        // For live channels, try ChannelNameParser first (most accurate)
        val parsed = ChannelNameParser.parse(channelName, categoryName)
        val fromParser = contentTypeToGroup(parsed.contentType)
        if (fromParser != null) return fromParser

        // Fallback: keyword match on category name
        if (categoryName != null) {
            val lower = categoryName.lowercase()
            return when {
                SPORTS_KEYWORDS.any { lower.contains(it) } -> GROUP_SPORTS
                NEWS_KEYWORDS.any { lower.contains(it) } -> GROUP_NEWS
                KIDS_KEYWORDS.any { lower.contains(it) } -> GROUP_KIDS
                ENTERTAINMENT_KEYWORDS.any { lower.contains(it) } -> GROUP_ENTERTAINMENT
                else -> GROUP_LIVE_TV
            }
        }

        return GROUP_LIVE_TV
    }

    private fun contentTypeToGroup(contentType: ChannelContentType): String? = when (contentType) {
        ChannelContentType.SPORTS -> GROUP_SPORTS
        ChannelContentType.NEWS -> GROUP_NEWS
        ChannelContentType.KIDS -> GROUP_KIDS
        ChannelContentType.ENTERTAINMENT -> GROUP_ENTERTAINMENT
        ChannelContentType.MOVIES -> GROUP_MOVIES
        ChannelContentType.DOCUMENTARY -> GROUP_ENTERTAINMENT
        ChannelContentType.MUSIC -> GROUP_ENTERTAINMENT
        ChannelContentType.RELIGIOUS -> GROUP_ENTERTAINMENT
        ChannelContentType.UNKNOWN -> null
    }
}
