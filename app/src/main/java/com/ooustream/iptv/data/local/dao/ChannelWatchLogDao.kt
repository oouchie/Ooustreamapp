package com.ooustream.iptv.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.ooustream.iptv.data.local.entity.ChannelWatchLogEntity
import kotlinx.coroutines.flow.Flow

/** Projection for the "Recently Watched" rail: identity + recency + display metadata. */
data class RecentChannelRow(
    val channelId: Int,
    val channelName: String,
    val categoryId: String?,
    val channelIcon: String?,
    val lastWatched: Long
)

@Dao
interface ChannelWatchLogDao {

    @Insert
    suspend fun insert(log: ChannelWatchLogEntity)

    /**
     * Distinct channels, most-recently-watched first, for the Live TV "Recently Watched" rail.
     *
     * Three things here are load-bearing:
     *  - EXACTLY ONE aggregate. SQLite's bare-column rule then guarantees channelName /
     *    categoryId / channelIcon come from the same row that produced the MAX. A second
     *    min/max would void that guarantee and mix metadata across rows.
     *  - MAX(timestamp), not MAX(timestamp + durationSeconds * 1000). Ordering by session END
     *    would hand the bare columns to the longest-ending row (stale name/icon), and buys
     *    nothing: WatchSessionLogger keeps a single currentSession and ends it before starting
     *    the next, so live sessions are serial and start-order == end-order.
     *  - NO window function. ROW_NUMBER() OVER needs SQLite 3.25+ (~API 30); minSdk is 23 and
     *    the fleet still includes API 25/28 sticks, where it would compile clean and throw at
     *    runtime. GROUP BY is served by the existing index on timestamp.
     */
    @Query("""
        SELECT channelId, channelName, categoryId, channelIcon,
               MAX(timestamp) AS lastWatched
        FROM channel_watch_log
        WHERE timestamp > :cutoff
        GROUP BY channelId
        ORDER BY lastWatched DESC
        LIMIT :limit
    """)
    fun observeRecentChannels(cutoff: Long, limit: Int): Flow<List<RecentChannelRow>>

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
