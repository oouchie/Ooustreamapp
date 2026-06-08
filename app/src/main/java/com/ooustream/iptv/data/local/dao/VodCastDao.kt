package com.ooustream.iptv.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ooustream.iptv.data.local.entity.VodCastEntity

@Dao
interface VodCastDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: VodCastEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<VodCastEntity>)

    /**
     * Stream ids whose cached cast contains the query. SQLite `LIKE` is case-insensitive for
     * ASCII, so "tom hanks" matches "Tom Hanks, ...".
     */
    @Query("SELECT streamId FROM vod_cast WHERE `cast` LIKE '%' || :query || '%'")
    suspend fun findStreamIdsByCast(query: String): List<Int>

    @Query("SELECT * FROM vod_cast WHERE streamId IN (:ids)")
    suspend fun getForIds(ids: List<Int>): List<VodCastEntity>

    /** Stream ids already fetched (the backfill worker skips these). */
    @Query("SELECT streamId FROM vod_cast")
    suspend fun getFetchedIds(): List<Int>

    @Query("SELECT COUNT(*) FROM vod_cast")
    suspend fun getCount(): Int
}
