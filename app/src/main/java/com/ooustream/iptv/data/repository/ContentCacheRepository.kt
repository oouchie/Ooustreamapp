package com.ooustream.iptv.data.repository

import com.ooustream.iptv.data.local.dao.ContentCacheDao
import com.ooustream.iptv.data.local.entity.CachedCategoryEntity
import com.ooustream.iptv.data.model.Category
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContentCacheRepository @Inject constructor(
    private val cacheDao: ContentCacheDao,
    private val contentRepository: ContentRepository,
    private val searchIndexRepository: SearchIndexRepository,
    private val watchAnalyticsRepository: WatchAnalyticsRepository
) {
    companion object {
        const val CATEGORY_TTL = 6 * 60 * 60 * 1000L  // 6 hours
    }

    /**
     * Stale-while-revalidate pattern for categories.
     * Emits cached data immediately, then fetches fresh data in the background
     * and emits again if the data has changed.
     */
    fun getCategories(type: String): Flow<List<Category>> = flow {
        // 1. Emit cached first for instant UI
        val cached = cacheDao.getCategoriesByType(type)
        if (cached.isNotEmpty()) {
            emit(cached.map { it.toCategory() })
        }

        // 2. Check if stale
        val lastCached = cacheDao.getCategoryLastCached(type)
        val isStale = lastCached == null || (System.currentTimeMillis() - lastCached > CATEGORY_TTL)

        if (isStale || cached.isEmpty()) {
            try {
                val fresh = when (type) {
                    "live" -> contentRepository.getLiveCategories()
                    "vod" -> contentRepository.getVodCategories()
                    "series" -> contentRepository.getSeriesCategories()
                    else -> emptyList()
                }

                // Cache the fresh data
                val entities = fresh.mapIndexed { index, category ->
                    CachedCategoryEntity(
                        id = "${type}_${category.categoryId}",
                        categoryId = category.categoryId,
                        categoryName = category.categoryName,
                        type = type,
                        parentId = category.parentId,
                        sortOrder = index
                    )
                }
                // Apply smart category ordering based on watch frequency
                val smartEntities = applySmartOrdering(entities, type)

                cacheDao.deleteCategoriesByType(type)
                cacheDao.insertCategories(smartEntities)

                // Re-emit fresh data sorted by the smart ordering
                if (fresh.isNotEmpty()) {
                    val sortedCategories = smartEntities
                        .sortedBy { it.sortOrder }
                        .map { it.toCategory() }
                    emit(sortedCategories)
                }

                // Trigger search index rebuild in the background after fresh data arrives
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        searchIndexRepository.rebuildIndex()
                    } catch (_: Exception) { }
                }
            } catch (e: Exception) {
                // If network fails and we had cache, we already emitted it
                if (cached.isEmpty()) throw e
            }
        }
    }

    /**
     * Clears cached categories, streams, and search index without touching user data
     * (favorites, watch progress, analytics, EPG patterns, etc.).
     */
    suspend fun clearContentCache() {
        cacheDao.deleteAllCategories()
        cacheDao.deleteAllStreams()
        searchIndexRepository.clearAndReset()
    }

    /**
     * Remove data older than 24 hours.
     */
    suspend fun cleanupStaleData() {
        val cutoff = System.currentTimeMillis() - (24 * 60 * 60 * 1000L)
        cacheDao.deleteStaleCategories(cutoff)
        cacheDao.deleteStaleStreams(cutoff)
    }

    /**
     * Re-order categories using a weighted blend of the original API position
     * and the user's watch frequency.  Categories the user watches more often
     * float to the top.
     *
     * For first-time users with no analytics data the original API ordering is
     * preserved (all watch scores default to 1.0 = lowest priority, so
     * apiIndex weight dominates).
     */
    private suspend fun applySmartOrdering(
        entities: List<CachedCategoryEntity>,
        type: String
    ): List<CachedCategoryEntity> {
        if (entities.isEmpty()) return entities

        val watchCounts = try {
            watchAnalyticsRepository.getCategoryWatchCounts(type)
        } catch (_: Exception) {
            return entities // On error, keep the original order
        }

        val watchMap = watchCounts.associate { it.categoryId to it.totalCount }
        val maxWatch = watchMap.values.maxOrNull() ?: return entities // No data, keep order

        if (maxWatch == 0) return entities

        val categoryCount = entities.size.coerceAtLeast(1)

        val scored = entities.mapIndexed { apiIndex, entity ->
            val watchScore = watchMap[entity.categoryId]?.let { count ->
                1.0 - (count.toDouble() / maxWatch) // 0.0 = most watched, 1.0 = least
            } ?: 1.0 // No watch data = low priority (keep towards original position)

            val combinedScore = (apiIndex.toDouble() / categoryCount * 0.3) + (watchScore * 0.7)
            entity to combinedScore
        }

        return scored
            .sortedBy { it.second }
            .mapIndexed { newIndex, (entity, _) ->
                entity.copy(sortOrder = newIndex)
            }
    }

    private fun CachedCategoryEntity.toCategory(): Category {
        return Category(
            categoryId = categoryId,
            categoryName = categoryName,
            parentId = parentId
        )
    }
}
