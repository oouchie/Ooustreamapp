package com.ooustream.iptv.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ooustream.iptv.data.local.entity.PosterCacheEntity

@Dao
interface PosterCacheDao {

    @Query("SELECT * FROM poster_cache WHERE tmdb_id = :tmdbId LIMIT 1")
    suspend fun get(tmdbId: String): PosterCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PosterCacheEntity)

    @Query("DELETE FROM poster_cache WHERE fetched_at < :cutoff")
    suspend fun deleteExpired(cutoff: Long)
}
