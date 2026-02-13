package com.ooustream.iptv.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ooustream.iptv.data.local.entity.ChannelScoreEntity

@Dao
interface ChannelScoreDao {

    /** Top channels for a specific time slot (used by Home "For You — Live Now" row). */
    @Query("""
        SELECT * FROM channel_scores
        WHERE dayOfWeek = :dayOfWeek AND hourBucket = :hourBucket
        ORDER BY score DESC
        LIMIT :limit
    """)
    suspend fun getTopChannelsForSlot(
        dayOfWeek: Int,
        hourBucket: Int,
        limit: Int = 15
    ): List<ChannelScoreEntity>

    /** Top channels overall (fallback when current time slot has sparse data). */
    @Query("""
        SELECT channelId, channelName, categoryName, channelIcon,
               0 AS dayOfWeek, 0 AS hourBucket,
               SUM(score) AS score, SUM(watchCount) AS watchCount,
               AVG(avgDurationSec) AS avgDurationSec,
               MAX(lastWatched) AS lastWatched,
               NULL AS contextHint, MAX(updatedAt) AS updatedAt
        FROM channel_scores
        GROUP BY channelId
        ORDER BY score DESC
        LIMIT :limit
    """)
    suspend fun getTopChannelsOverall(limit: Int = 15): List<ChannelScoreEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(scores: List<ChannelScoreEntity>)

    @Query("DELETE FROM channel_scores")
    suspend fun clearAll()
}
