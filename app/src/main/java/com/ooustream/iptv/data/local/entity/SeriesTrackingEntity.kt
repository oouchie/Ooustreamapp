package com.ooustream.iptv.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "series_tracking")
data class SeriesTrackingEntity(
    @PrimaryKey val seriesId: Int,
    val seriesTitle: String = "",
    val posterUrl: String? = null,

    // User's progress through the series
    val lastWatchedSeason: Int = 0,
    val lastWatchedEpisode: Int = 0,
    val lastWatchedEpisodeId: Int = 0,
    val lastWatchedAt: Long = 0,

    // New episode detection (from API sync)
    val lastKnownSeason: Int = 0,
    val lastKnownEpisode: Int = 0,
    val lastSyncedAt: Long = 0,
    val newEpisodeCount: Int = 0,
    val hasNewEpisodes: Boolean = false
)
