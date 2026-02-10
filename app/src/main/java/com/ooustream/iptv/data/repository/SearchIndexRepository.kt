package com.ooustream.iptv.data.repository

import android.content.Context
import com.ooustream.iptv.data.local.dao.SearchIndexDao
import com.ooustream.iptv.data.local.entity.SearchIndexEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchIndexRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val searchIndexDao: SearchIndexDao,
    private val contentRepository: ContentRepository
) {
    private val prefs = context.getSharedPreferences("search_index_prefs", Context.MODE_PRIVATE)

    suspend fun search(query: String): List<SearchIndexEntity> {
        if (query.isBlank()) return emptyList()

        return try {
            // FTS4 MATCH requires special syntax - append wildcard for prefix matching
            val ftsQuery = "${query.trim()}*"
            searchIndexDao.search(ftsQuery)
        } catch (e: Exception) {
            // Fallback to LIKE search if FTS fails
            searchIndexDao.searchFallback(query.trim())
        }
    }

    suspend fun rebuildIndex() {
        val lastIndexed = prefs.getLong("last_indexed", 0)
        val now = System.currentTimeMillis()

        // Don't re-index if less than 1 hour old
        if (now - lastIndexed < 3600_000 && searchIndexDao.getCount() > 0) return

        searchIndexDao.clearAll()

        val entries = mutableListOf<SearchIndexEntity>()

        // Index live streams
        try {
            val liveCategories = contentRepository.getLiveCategories()
            val categoryMap = liveCategories.associateBy { it.categoryId }
            val liveStreams = contentRepository.getLiveStreams(null)
            liveStreams.forEach { stream ->
                entries.add(
                    SearchIndexEntity(
                        streamId = stream.streamId,
                        name = stream.name,
                        type = "live",
                        categoryName = categoryMap[stream.categoryId]?.categoryName,
                        rating = null,
                        extra = stream.epgChannelId
                    )
                )
            }
        } catch (_: Exception) { }

        // Index VOD streams
        try {
            val vodCategories = contentRepository.getVodCategories()
            val categoryMap = vodCategories.associateBy { it.categoryId }
            val vodStreams = contentRepository.getVodStreams(null)
            vodStreams.forEach { stream ->
                entries.add(
                    SearchIndexEntity(
                        streamId = stream.streamId,
                        name = stream.name,
                        type = "vod",
                        categoryName = categoryMap[stream.categoryId]?.categoryName,
                        rating = stream.rating,
                        extra = stream.containerExtension
                    )
                )
            }
        } catch (_: Exception) { }

        // Index series
        try {
            val seriesCategories = contentRepository.getSeriesCategories()
            val categoryMap = seriesCategories.associateBy { it.categoryId }
            val series = contentRepository.getSeries(null)
            series.forEach { s ->
                entries.add(
                    SearchIndexEntity(
                        streamId = s.seriesId,
                        name = s.name,
                        type = "series",
                        categoryName = categoryMap[s.categoryId]?.categoryName,
                        rating = s.rating,
                        extra = s.genre
                    )
                )
            }
        } catch (_: Exception) { }

        // Batch insert in chunks of 500 to avoid transaction limits
        if (entries.isNotEmpty()) {
            entries.chunked(500).forEach { chunk ->
                searchIndexDao.insertAll(chunk)
            }
            prefs.edit().putLong("last_indexed", now).apply()
        }
    }

    suspend fun isIndexBuilt(): Boolean = searchIndexDao.getCount() > 0
}
