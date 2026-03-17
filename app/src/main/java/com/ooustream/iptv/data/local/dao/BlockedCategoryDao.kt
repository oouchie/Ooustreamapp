package com.ooustream.iptv.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ooustream.iptv.data.local.entity.BlockedCategoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedCategoryDao {

    @Query("SELECT * FROM blocked_categories")
    fun getAll(): Flow<List<BlockedCategoryEntity>>

    @Query("SELECT * FROM blocked_categories WHERE section = :section")
    fun getBySection(section: String): Flow<List<BlockedCategoryEntity>>

    @Query("SELECT * FROM blocked_categories WHERE section = :section")
    suspend fun getListBySection(section: String): List<BlockedCategoryEntity>

    @Query("SELECT COUNT(*) > 0 FROM blocked_categories WHERE section = :section AND categoryId = :categoryId")
    suspend fun isBlocked(section: String, categoryId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: BlockedCategoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<BlockedCategoryEntity>)

    @Query("DELETE FROM blocked_categories WHERE section = :section AND categoryId = :categoryId")
    suspend fun delete(section: String, categoryId: String)

    @Query("DELETE FROM blocked_categories WHERE section = :section")
    suspend fun deleteBySection(section: String)

    @Query("DELETE FROM blocked_categories")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM blocked_categories")
    fun getCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM blocked_categories WHERE section = :section")
    fun getCountBySection(section: String): Flow<Int>
}
