package com.ooustream.iptv.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val id: String,           // "{type}_{streamId}" e.g. "live_1234"
    val streamId: Int,
    val type: String,                      // "live", "vod", "series"
    val name: String,
    val icon: String?,
    val categoryId: String?,
    val extra: String?,                    // JSON blob for type-specific data
    val createdAt: Long = System.currentTimeMillis()
)
