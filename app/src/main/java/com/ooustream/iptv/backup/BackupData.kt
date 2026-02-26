package com.ooustream.iptv.backup

import com.ooustream.iptv.data.local.entity.FavoriteEntity
import com.ooustream.iptv.data.local.entity.WatchProgressEntity

data class BackupData(
    val version: Int = CURRENT_VERSION,
    val timestamp: Long = System.currentTimeMillis(),
    val favorites: List<FavoriteEntity> = emptyList(),
    val watchProgress: List<WatchProgressEntity> = emptyList()
) {
    companion object {
        const val CURRENT_VERSION = 2
    }
}
