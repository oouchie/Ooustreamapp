package com.ooustream.iptv.common

import com.ooustream.iptv.data.local.dao.CrashRecoveryDao
import com.ooustream.iptv.data.local.entity.CrashRecoveryEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CrashRecoveryManager @Inject constructor(
    private val crashRecoveryDao: CrashRecoveryDao
) {
    suspend fun savePlaybackState(
        streamUrl: String,
        contentType: String,
        streamId: String,
        streamName: String,
        position: Long
    ) {
        crashRecoveryDao.save(
            CrashRecoveryEntity(
                streamUrl = streamUrl,
                contentType = contentType,
                streamId = streamId,
                streamName = streamName,
                position = position,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    suspend fun markCleanExit() {
        crashRecoveryDao.markCleanExit()
    }

    suspend fun getRecoveryState(): CrashRecoveryEntity? {
        val state = crashRecoveryDao.getLatest() ?: return null
        // Only recover if crash was within 5 minutes and wasn't a clean exit
        if (state.cleanExit) return null
        if (System.currentTimeMillis() - state.timestamp > 5 * 60 * 1000) return null
        return state
    }

    suspend fun clearState() {
        crashRecoveryDao.clear()
    }
}
