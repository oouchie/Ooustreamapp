package com.ooustream.iptv.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ooustream.iptv.data.local.entity.CrashRecoveryEntity

@Dao
interface CrashRecoveryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(state: CrashRecoveryEntity)

    @Query("SELECT * FROM crash_recovery WHERE id = 1 LIMIT 1")
    suspend fun getLatest(): CrashRecoveryEntity?

    @Query("DELETE FROM crash_recovery")
    suspend fun clear()

    @Query("UPDATE crash_recovery SET cleanExit = 1 WHERE id = 1")
    suspend fun markCleanExit()
}
