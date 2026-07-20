package com.ooustream.iptv.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ooustream.iptv.data.local.entity.SeriesTrackingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SeriesTrackingDao {
    @Query("SELECT * FROM series_tracking WHERE hasNewEpisodes = 1 ORDER BY lastSyncedAt DESC")
    fun getSeriesWithNewEpisodes(): Flow<List<SeriesTrackingEntity>>

    @Query("SELECT * FROM series_tracking ORDER BY lastWatchedAt DESC")
    suspend fun getAllTrackedSeries(): List<SeriesTrackingEntity>

    @Query("SELECT * FROM series_tracking WHERE seriesId = :seriesId")
    suspend fun getTracking(seriesId: Int): SeriesTrackingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tracking: SeriesTrackingEntity)

    @Query("UPDATE series_tracking SET hasNewEpisodes = :hasNew, newEpisodeCount = :count, lastKnownSeason = :season, lastKnownEpisode = :episode, lastSyncedAt = :syncedAt WHERE seriesId = :seriesId")
    suspend fun updateNewEpisodeStatus(seriesId: Int, hasNew: Boolean, count: Int, season: Int, episode: Int, syncedAt: Long)

    @Query("DELETE FROM series_tracking")
    suspend fun clearAll()

    /** Delete by explicit id list — pass the dead set only (see WatchProgressDao.deleteByStreamIds). */
    @Query("DELETE FROM series_tracking WHERE seriesId IN (:seriesIds)")
    suspend fun deleteBySeriesIds(seriesIds: List<Int>)
}
