package com.ooustream.iptv.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ooustream.iptv.data.local.entity.WatchProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchProgressDao {
    @Query("SELECT * FROM watch_progress WHERE progressPercent > 0.05 AND completed = 0 AND dismissed = 0 ORDER BY lastWatched DESC LIMIT 100")
    fun getContinueWatching(): Flow<List<WatchProgressEntity>>

    @Query("SELECT * FROM watch_progress WHERE streamId = :streamId")
    suspend fun getProgress(streamId: String): WatchProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: WatchProgressEntity)

    @Query("UPDATE watch_progress SET completed = 1 WHERE streamId = :streamId")
    suspend fun markCompleted(streamId: String)

    @Query("UPDATE watch_progress SET dismissed = 1 WHERE streamId = :streamId")
    suspend fun dismiss(streamId: String)

    @Query("SELECT * FROM watch_progress WHERE seriesId = :seriesId ORDER BY lastWatched DESC")
    suspend fun getSeriesEpisodes(seriesId: Int): List<WatchProgressEntity>

    @Query("SELECT * FROM watch_progress WHERE streamId IN (:streamIds)")
    suspend fun getProgressForIds(streamIds: List<String>): List<WatchProgressEntity>

    @Query("SELECT * FROM watch_progress ORDER BY lastWatched DESC")
    fun getAllProgress(): Flow<List<WatchProgressEntity>>

    @Query("""
        SELECT wp.* FROM watch_progress wp
        INNER JOIN (
            SELECT COALESCE(seriesId, streamId) AS groupKey, MAX(lastWatched) AS maxWatched
            FROM watch_progress
            WHERE completed = 1
            GROUP BY groupKey
        ) grouped ON COALESCE(wp.seriesId, wp.streamId) = grouped.groupKey AND wp.lastWatched = grouped.maxWatched
        WHERE wp.completed = 1
        ORDER BY wp.lastWatched DESC
        LIMIT 15
    """)
    fun getCompletedContent(): Flow<List<WatchProgressEntity>>

    @Query("DELETE FROM watch_progress")
    suspend fun clearAll()
}
