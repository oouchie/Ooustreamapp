package com.ooustream.iptv.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ooustream.iptv.data.local.entity.WatchAnalyticsEntity

@Dao
interface WatchAnalyticsDao {

    @Query("SELECT * FROM watch_analytics WHERE id = :id")
    suspend fun get(id: String): WatchAnalyticsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WatchAnalyticsEntity)

    @Query("SELECT * FROM watch_analytics WHERE type = :type ORDER BY watchCount DESC LIMIT :limit")
    suspend fun getTopStreams(type: String, limit: Int): List<WatchAnalyticsEntity>

    @Query("""
        SELECT categoryId, SUM(watchCount) as totalCount
        FROM watch_analytics
        WHERE type = :type AND categoryId IS NOT NULL
        GROUP BY categoryId
        ORDER BY totalCount DESC
        LIMIT :limit
    """)
    suspend fun getTopCategoryIds(type: String, limit: Int): List<CategoryWatchCount>

    @Query("""
        SELECT categoryId, SUM(watchCount) as totalCount
        FROM watch_analytics
        WHERE type = :type AND categoryId IS NOT NULL
        GROUP BY categoryId
        ORDER BY totalCount DESC
    """)
    suspend fun getCategoryWatchCounts(type: String): List<CategoryWatchCount>

    @Query("SELECT * FROM watch_analytics ORDER BY lastWatched DESC LIMIT :limit")
    suspend fun getRecentlyWatched(limit: Int): List<WatchAnalyticsEntity>

    @Query("DELETE FROM watch_analytics")
    suspend fun clearAll()
}

data class CategoryWatchCount(
    val categoryId: String,
    val totalCount: Int
)
