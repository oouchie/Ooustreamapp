package com.ooustream.iptv.data.local.entity

import androidx.room.Entity

/**
 * Precomputed channel recommendation scores.
 * Written by ChannelRecommendationEngine, read by Home screen "For You — Live Now" row.
 * Score = frequency × recency × duration weight per (channel, day, hourBucket) slot.
 */
@Entity(
    tableName = "channel_scores",
    primaryKeys = ["channelId", "dayOfWeek", "hourBucket"]
)
data class ChannelScoreEntity(
    val channelId: Int,
    val channelName: String,
    val categoryName: String?,
    val channelIcon: String?,
    val dayOfWeek: Int,           // 1-7 (Calendar.DAY_OF_WEEK)
    val hourBucket: Int,          // 0=night(0-5), 1=morning(6-11), 2=afternoon(12-17), 3=evening(18-23)
    val score: Float,
    val watchCount: Int,
    val avgDurationSec: Long,
    val lastWatched: Long,
    val contextHint: String?,     // "Your go-to Monday evening channel"
    val updatedAt: Long = System.currentTimeMillis()
)
