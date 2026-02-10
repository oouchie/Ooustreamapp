package com.ooustream.iptv.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "crash_recovery")
data class CrashRecoveryEntity(
    @PrimaryKey val id: Int = 1,  // Always one row
    val streamUrl: String,
    val contentType: String,  // "LIVE", "VOD", "SERIES"
    val streamId: String,
    val streamName: String,
    val position: Long,
    val timestamp: Long,
    val cleanExit: Boolean = false
)
