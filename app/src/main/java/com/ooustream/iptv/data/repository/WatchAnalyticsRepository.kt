package com.ooustream.iptv.data.repository

import com.ooustream.iptv.data.local.dao.CategoryWatchCount
import com.ooustream.iptv.data.local.dao.WatchAnalyticsDao
import com.ooustream.iptv.data.local.entity.WatchAnalyticsEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WatchAnalyticsRepository @Inject constructor(
    private val dao: WatchAnalyticsDao
) {
    suspend fun recordPlay(streamId: Int, categoryId: String?, type: String) {
        val id = "${type}_${streamId}"
        val existing = dao.get(id)
        if (existing != null) {
            dao.upsert(
                existing.copy(
                    watchCount = existing.watchCount + 1,
                    lastWatched = System.currentTimeMillis()
                )
            )
        } else {
            dao.upsert(
                WatchAnalyticsEntity(
                    id = id,
                    streamId = streamId,
                    categoryId = categoryId,
                    type = type
                )
            )
        }
    }

    suspend fun recordWatchTime(streamId: Int, type: String, durationMs: Long) {
        val id = "${type}_${streamId}"
        val existing = dao.get(id) ?: return
        dao.upsert(
            existing.copy(
                totalWatchTimeMs = existing.totalWatchTimeMs + durationMs,
                lastWatched = System.currentTimeMillis()
            )
        )
    }

    suspend fun getTopStreams(type: String, limit: Int = 20): List<WatchAnalyticsEntity> {
        return dao.getTopStreams(type, limit)
    }

    suspend fun getTopCategoryIds(type: String, limit: Int = 10): List<CategoryWatchCount> {
        return dao.getTopCategoryIds(type, limit)
    }

    suspend fun getCategoryWatchCounts(type: String): List<CategoryWatchCount> {
        return dao.getCategoryWatchCounts(type)
    }

    suspend fun getRecentlyWatched(limit: Int = 20): List<WatchAnalyticsEntity> {
        return dao.getRecentlyWatched(limit)
    }
}
