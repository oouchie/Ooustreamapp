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

    suspend fun getSeriesEpisodes(seriesId: Int): List<WatchProgressEntity> =
        watchProgressDao.getSeriesEpisodes(seriesId)

    suspend fun getProgressForIds(streamIds: List<String>): List<WatchProgressEntity> =
        watchProgressDao.getProgressForIds(streamIds)

    suspend fun clearAll() = watchProgressDao.clearAll()
}
