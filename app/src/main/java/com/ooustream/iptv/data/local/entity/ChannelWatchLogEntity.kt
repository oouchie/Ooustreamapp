package com.ooustream.iptv.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Raw watch session log for Live TV channels.
 * Records every viewing session > 30 seconds.
 * Used by ChannelRecommendationEngine for "For You — Live Now" scoring.
 * Separate from WatchProgressEntity (which tracks VOD/Series resume position).
 */
@Entity(
    tableName = "channel_watch_log",
    indices = [
        Index(value = ["channelId"]),
        Index(value = ["dayOfWeek", "hourOfDay"]),
        Index(value = ["timestamp"])
    ]
)
data class ChannelWatchLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelId: Int,
    val channelName: String,
    val categoryId: String?,
    val categoryName: String?,
    val channelIcon: String?,
    val dayOfWeek: Int,           // Calendar.DAY_OF_WEEK (1=Sun, 7=Sat)
    val hourOfDay: Int,           // 0-23
    val durationSeconds: Long,    // How long they watched
    val timestamp: Long           // When the session started (epoch ms)
)
