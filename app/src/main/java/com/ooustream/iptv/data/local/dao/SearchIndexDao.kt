package com.ooustream.iptv.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ooustream.iptv.data.local.entity.SearchIndexEntity

@Dao
interface SearchIndexDao {

    @Query("""
        SELECT s.* FROM search_index s
        JOIN search_index_fts f ON s.rowid = f.rowid
        WHERE search_index_fts MATCH :query
        LIMIT :limit
    """)
    suspend fun search(query: String, limit: Int = 50): List<SearchIndexEntity>

    @Query("SELECT * FROM search_index WHERE name LIKE '%' || :query || '%' LIMIT :limit")
    suspend fun searchFallback(query: String, limit: Int = 50): List<SearchIndexEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<SearchIndexEntity>)

    @Query("DELETE FROM search_index")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM search_index")
    suspend fun getCount(): Int
}
