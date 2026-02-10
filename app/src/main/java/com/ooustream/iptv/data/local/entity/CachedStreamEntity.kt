package com.ooustream.iptv.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_streams")
data class CachedStreamEntity(
    @PrimaryKey val id: String,  // "live_123" or "vod_456"
    val streamId: Int,
    val name: String,
    val icon: String?,
    val categoryId: String?,
    val type: String,  // "live", "vod", "series"
    val rating: String?,
    val added: String?,
    val containerExtension: String?,
    val extra: String?,  // JSON for additional fields
    val cachedAt: Long = System.currentTimeMillis()
)
