package com.ooustream.iptv.data.local.entity

import androidx.room.Entity

/**
 * Cached EPG programming patterns learned from successful EPG fetches.
 * When real EPG is available, we record what was airing at each (channel, day, hour).
 * When EPG is missing later, SmartEpgFiller uses this pattern as Tier 2 inference.
 */
@Entity(
    tableName = "epg_pattern_cache",
    primaryKeys = ["channelId", "dayOfWeek", "hourSlot"]
)
data class EpgPatternEntity(
    val channelId: Int,
    val channelName: String,
    val dayOfWeek: Int,           // 1-7 (Calendar.DAY_OF_WEEK)
    val hourSlot: Int,            // 0-23 (hour of day)
    val programTitle: String,
    val programDescription: String?,
    val programCategory: String?,
    val confidence: Float,        // 0.0-1.0 (increases with repeated confirmation)
    val sampleCount: Int,         // How many times this pattern has been observed
    val updatedAt: Long = System.currentTimeMillis()
)
