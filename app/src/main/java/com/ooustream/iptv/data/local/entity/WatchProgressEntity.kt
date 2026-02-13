package com.ooustream.iptv.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watch_progress")
data class WatchProgressEntity(
    @PrimaryKey val streamId: String,
    val type: String,                      // "vod" or "series"
    val name: String,
    val icon: String?,
    val position: Long,                    // milliseconds
    val duration: Long,                    // milliseconds
    val progressPercent: Float,            // 0.0 - 1.0
    val lastWatched: Long = System.currentTimeMillis(),
    val extra: String?,                    // stream URL for resume
    val seriesId: Int? = null,             // series context for binge-watch
    val seasonNum: Int? = null,
    val episodeNum: Int? = null,
    val completed: Boolean = false,        // true when progress > 95% or STATE_ENDED
    val dismissed: Boolean = false         // true when user manually removes from CW
)
