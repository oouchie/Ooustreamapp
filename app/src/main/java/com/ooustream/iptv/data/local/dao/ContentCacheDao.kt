package com.ooustream.iptv.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ooustream.iptv.data.local.entity.CachedCategoryEntity
import com.ooustream.iptv.data.local.entity.CachedStreamEntity

@Dao
interface ContentCacheDao {
    // Categories
    @Query("SELECT * FROM cached_categories WHERE type = :type ORDER BY sortOrder")
    suspend fun getCategoriesByType(type: String): List<CachedCategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(categories: List<CachedCategoryEntity>)

    @Query("DELETE FROM cached_categories WHERE type = :type")
    suspend fun deleteCategoriesByType(type: String)

    @Query("SELECT MAX(cachedAt) FROM cached_categories WHERE type = :type")
    suspend fun getCategoryLastCached(type: String): Long?

    // Streams
    @Query("SELECT * FROM cached_streams WHERE type = :type AND categoryId = :categoryId ORDER BY name")
    suspend fun getStreams(type: String, categoryId: String): List<CachedStreamEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStreams(streams: List<CachedStreamEntity>)

    @Query("DELETE FROM cached_streams WHERE type = :type AND categoryId = :categoryId")
    suspend fun deleteStreams(type: String, categoryId: String)

    @Query("SELECT MAX(cachedAt) FROM cached_streams WHERE type = :type AND categoryId = :categoryId")
    suspend fun getStreamLastCached(type: String, categoryId: String): Long?

    // Cleanup
    @Query("DELETE FROM cached_categories WHERE cachedAt < :before")
    suspend fun deleteStaleCategories(before: Long)

    @Query("DELETE FROM cached_streams WHERE cachedAt < :before")
    suspend fun deleteStaleStreams(before: Long)

    // Bulk delete (for playlist refresh — clears content without touching user data)
    @Query("DELETE FROM cached_categories")
    suspend fun deleteAllCategories()

    @Query("DELETE FROM cached_streams")
    suspend fun deleteAllStreams()
}
