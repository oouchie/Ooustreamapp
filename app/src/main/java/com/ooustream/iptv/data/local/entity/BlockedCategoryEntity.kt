package com.ooustream.iptv.data.local.entity

import androidx.room.Entity

@Entity(
    tableName = "blocked_categories",
    primaryKeys = ["section", "categoryId"]
)
data class BlockedCategoryEntity(
    val section: String,          // "live", "vod", "series"
    val categoryId: String,
    val categoryName: String = "",
    val blockedAt: Long = System.currentTimeMillis()
)
