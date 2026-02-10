package com.ooustream.iptv.data.local.entity

import androidx.room.Entity
import androidx.room.Fts4

@Fts4(contentEntity = SearchIndexEntity::class)
@Entity(tableName = "search_index_fts")
data class SearchIndexFts(
    val name: String,
    val categoryName: String?,
    val rating: String?,
    val extra: String?
)
