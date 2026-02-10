package com.ooustream.iptv.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watch_analytics")
data class WatchAnalyticsEntity(
    @PrimaryKey val id: String,             // "{type}_{streamId}"
    val streamId: Int,
    val categoryId: String?,
    val type: String,                        // "live", "vod", "series"
    val watchCount: Int = 1,
    val totalWatchTimeMs: Long = 0,
    val lastWatched: Long = System.currentTimeMillis()
)
