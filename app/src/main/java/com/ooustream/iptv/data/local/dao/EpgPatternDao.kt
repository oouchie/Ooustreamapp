package com.ooustream.iptv.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ooustream.iptv.data.local.entity.EpgPatternEntity

@Dao
interface EpgPatternDao {

    /** Get cached pattern for a specific channel at a specific time. */
    @Query("""
        SELECT * FROM epg_pattern_cache
        WHERE channelId = :channelId
          AND dayOfWeek = :dayOfWeek
          AND hourSlot = :hourSlot
        LIMIT 1
    """)
    suspend fun getPattern(channelId: Int, dayOfWeek: Int, hourSlot: Int): EpgPatternEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pattern: EpgPatternEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(patterns: List<EpgPatternEntity>)

    /** Delete patterns not updated in 30 days. */
    @Query("DELETE FROM epg_pattern_cache WHERE updatedAt < :cutoff")
    suspend fun pruneStale(
        cutoff: Long = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
    )

    @Query("DELETE FROM epg_pattern_cache")
    suspend fun clearAll()
}
