package com.ooustream.iptv.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.ooustream.iptv.data.local.dao.ContentCacheDao
import com.ooustream.iptv.data.local.dao.CrashRecoveryDao
import com.ooustream.iptv.data.local.dao.EpgCacheDao
import com.ooustream.iptv.data.local.dao.FavoriteDao
import com.ooustream.iptv.data.local.dao.SearchHistoryDao
import com.ooustream.iptv.data.local.dao.SearchIndexDao
import com.ooustream.iptv.data.local.dao.WatchAnalyticsDao
import com.ooustream.iptv.data.local.dao.WatchProgressDao
import com.ooustream.iptv.data.local.entity.CachedCategoryEntity
import com.ooustream.iptv.data.local.entity.CachedStreamEntity
import com.ooustream.iptv.data.local.entity.CrashRecoveryEntity
import com.ooustream.iptv.data.local.entity.EpgCacheEntity
import com.ooustream.iptv.data.local.entity.FavoriteEntity
import com.ooustream.iptv.data.local.entity.SearchHistoryEntity
import com.ooustream.iptv.data.local.entity.SearchIndexEntity
import com.ooustream.iptv.data.local.entity.SearchIndexFts
import com.ooustream.iptv.data.local.entity.WatchAnalyticsEntity
import com.ooustream.iptv.data.local.entity.WatchProgressEntity

@Database(
    entities = [
        FavoriteEntity::class,
        WatchProgressEntity::class,
        EpgCacheEntity::class,
        SearchHistoryEntity::class,
        CrashRecoveryEntity::class,
        CachedCategoryEntity::class,
        CachedStreamEntity::class,
        WatchAnalyticsEntity::class,
        SearchIndexEntity::class,
        SearchIndexFts::class
    ],
    version = 5,
    exportSchema = false
)
abstract class OoustreamDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun watchProgressDao(): WatchProgressDao
    abstract fun epgCacheDao(): EpgCacheDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun crashRecoveryDao(): CrashRecoveryDao
    abstract fun contentCacheDao(): ContentCacheDao
    abstract fun watchAnalyticsDao(): WatchAnalyticsDao
    abstract fun searchIndexDao(): SearchIndexDao
}
