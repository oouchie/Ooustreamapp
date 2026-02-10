package com.ooustream.iptv.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ooustream.iptv.data.local.entity.EpgCacheEntity

@Dao
interface EpgCacheDao {
    @Query("SELECT * FROM epg_cache WHERE streamId = :streamId")
    suspend fun get(streamId: Int): EpgCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: EpgCacheEntity)

    @Query("DELETE FROM epg_cache WHERE fetchedAt < :beforeTimestamp")
    suspend fun deleteExpired(beforeTimestamp: Long)

    @Query("DELETE FROM epg_cache")
    suspend fun clearAll()
}
