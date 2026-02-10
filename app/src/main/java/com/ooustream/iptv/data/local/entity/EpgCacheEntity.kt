package com.ooustream.iptv.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "epg_cache")
data class EpgCacheEntity(
    @PrimaryKey val streamId: Int,
    val programs: String,                  // JSON serialized List<EpgProgram>
    val fetchedAt: Long = System.currentTimeMillis()
)
