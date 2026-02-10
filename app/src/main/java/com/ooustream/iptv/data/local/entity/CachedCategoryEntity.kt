package com.ooustream.iptv.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_categories")
data class CachedCategoryEntity(
    @PrimaryKey val id: String,  // "live_123" or "vod_456"
    val categoryId: String,
    val categoryName: String,
    val type: String,  // "live", "vod", "series"
    val parentId: Int?,
    val sortOrder: Int = 0,
    val cachedAt: Long = System.currentTimeMillis()
)
