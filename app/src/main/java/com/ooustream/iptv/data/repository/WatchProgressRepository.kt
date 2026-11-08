package com.ooustream.iptv.data.repository

import com.ooustream.iptv.data.local.dao.WatchProgressDao
import com.ooustream.iptv.data.local.entity.WatchProgressEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WatchProgressRepository @Inject constructor(
    private val watchProgressDao: WatchProgressDao
) {
    fun getContinueWatching(): Flow<List<WatchProgressEntity>> =
        watchProgressDao.getContinueWatching()

    suspend fun getProgress(streamId: String): WatchProgressEntity? =
        watchProgressDao.getProgress(streamId)

    suspend fun saveProgress(progress: WatchProgressEntity) =
        watchProgressDao.upsert(progress)

    suspend fun markCompleted(streamId: String) =
        watchProgressDao.markCompleted(streamId)

    suspend fun dismiss(streamId: String) =
        watchProgressDao.dismiss(streamId)

    suspend fun dismissSeries(seriesId: Int) =
        watchProgressDao.dismissSeries(seriesId)

    suspend fun getSeriesEpisodes(seriesId: Int): List<WatchProgressEntity> =
        watchProgressDao.getSeriesEpisodes(seriesId)

    /**
     * Chunked: SQLite before Android 11 allows at most 999 bound variables, and one adult movie
     * category on bp-v2 held 125k titles (2026-11-04). An unchunked IN list threw there.
     */
    suspend fun getProgressForIds(streamIds: List<String>): List<WatchProgressEntity> =
        if (streamIds.size <= MAX_IN_ARGS) watchProgressDao.getProgressForIds(streamIds)
        else streamIds.chunked(MAX_IN_ARGS).flatMap { watchProgressDao.getProgressForIds(it) }

    private companion object {
        const val MAX_IN_ARGS = 900
    }

    fun getCompletedContent(): Flow<List<WatchProgressEntity>> =
        watchProgressDao.getCompletedContent()

    suspend fun clearAll() = watchProgressDao.clearAll()
}
