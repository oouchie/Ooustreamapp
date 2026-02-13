package com.ooustream.iptv.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.ooustream.iptv.data.local.entity.ChannelWatchLogEntity

@Dao
interface ChannelWatchLogDao {

    @Insert
    suspend fun insert(log: ChannelWatchLogEntity)

    /** All logs within the last 90 days for pattern analysis. */
    @Query("""
        SELECT * FROM channel_watch_log
        WHERE timestamp > :cutoff
        ORDER BY timestamp DESC
    """)
    suspend fun getRecentLogs(
        cutoff: Long = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
    ): List<ChannelWatchLogEntity>

    /** Number of distinct channels watched (for cold start detection). */
    @Query("SELECT COUNT(DISTINCT channelId) FROM channel_watch_log")
    suspend fun getUniqueChannelCount(): Int

    /** Delete entries older than 90 days. */
    @Query("DELETE FROM channel_watch_log WHERE timestamp < :cutoff")
    suspend fun pruneOldLogs(
        cutoff: Long = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
    )

    @Query("DELETE FROM channel_watch_log")
    suspend fun clearAll()
}
