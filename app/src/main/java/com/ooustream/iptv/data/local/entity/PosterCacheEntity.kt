package com.ooustream.iptv.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Caches TMDB poster/backdrop URLs fetched via TMDB API.
 * TTL: 30 days — poster images rarely change.
 */
@Entity(tableName = "poster_cache")
data class PosterCacheEntity(
    @PrimaryKey
    @ColumnInfo(name = "tmdb_id") val tmdbId: String,
    @ColumnInfo(name = "poster_url") val posterUrl: String?,
    @ColumnInfo(name = "backdrop_url") val backdropUrl: String?,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long = System.currentTimeMillis()
)
