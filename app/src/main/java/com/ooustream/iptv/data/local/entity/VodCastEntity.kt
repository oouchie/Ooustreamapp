package com.ooustream.iptv.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached cast/director for a VOD movie, pulled from `get_vod_info`.
 *
 * The bulk VOD list (`get_vod_streams`) does NOT include cast, so searching by actor needs this
 * side cache. A row exists once a movie's detail has been fetched (even when it had no cast — the
 * cast string is then empty), which also serves as the "already attempted" marker so the backfill
 * worker doesn't re-fetch the same movie.
 */
@Entity(tableName = "vod_cast")
data class VodCastEntity(
    @PrimaryKey val streamId: Int,
    val cast: String = "",
    val director: String? = null,
    val fetchedAt: Long = 0
)
