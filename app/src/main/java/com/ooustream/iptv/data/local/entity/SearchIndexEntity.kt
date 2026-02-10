package com.ooustream.iptv.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "search_index")
data class SearchIndexEntity(
    @PrimaryKey(autoGenerate = true) val rowid: Long = 0,
    val streamId: Int,
    val name: String,
    val type: String,           // "live", "vod", "series"
    val categoryName: String?,
    val rating: String?,
    val extra: String?          // genre, year, epg channel id, container extension, etc.
)
