package com.ooustream.iptv.recommendation

import com.ooustream.iptv.data.local.entity.SeriesTrackingEntity
import com.ooustream.iptv.data.model.SeriesInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Compares a tracked series' last-watched position against the latest
 * episodes from the API to detect new (unwatched) episodes.
 */
@Singleton
class NewEpisodeDetector @Inject constructor() {

    data class DetectionResult(
        val latestSeason: Int,
        val latestEpisode: Int,
        val newEpisodeCount: Int
    )

    /**
     * Count episodes beyond the user's [tracking] position in [seriesInfo].
     * Returns null if the series has no episodes.
     */
    fun detect(tracking: SeriesTrackingEntity, seriesInfo: SeriesInfo): DetectionResult? {
        val episodesMap = seriesInfo.episodes ?: return null
        if (episodesMap.isEmpty()) return null

        val sortedSeasons = episodesMap.keys.sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }

        var latestSeason = 0
        var latestEpisode = 0
        var newCount = 0

        for (seasonKey in sortedSeasons) {
            val seasonNum = seasonKey.toIntOrNull() ?: continue
            val episodes = episodesMap[seasonKey] ?: continue

            for (episode in episodes) {
                val epNum = episode.episodeNum

                // Track the absolute latest
                if (seasonNum > latestSeason || (seasonNum == latestSeason && epNum > latestEpisode)) {
                    latestSeason = seasonNum
                    latestEpisode = epNum
                }

                // Count episodes beyond user's last watched position
                if (seasonNum > tracking.lastWatchedSeason ||
                    (seasonNum == tracking.lastWatchedSeason && epNum > tracking.lastWatchedEpisode)
                ) {
                    newCount++
                }
            }
        }

        return DetectionResult(
            latestSeason = latestSeason,
            latestEpisode = latestEpisode,
            newEpisodeCount = newCount
        )
    }
}
