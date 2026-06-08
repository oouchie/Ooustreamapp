package com.ooustream.iptv.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ooustream.iptv.data.local.OoustreamDatabase
import com.ooustream.iptv.data.local.dao.BlockedCategoryDao
import com.ooustream.iptv.data.local.dao.ChannelScoreDao
import com.ooustream.iptv.data.local.dao.ChannelWatchLogDao
import com.ooustream.iptv.data.local.dao.ContentCacheDao
import com.ooustream.iptv.data.local.dao.CrashRecoveryDao
import com.ooustream.iptv.data.local.dao.EpgCacheDao
import com.ooustream.iptv.data.local.dao.EpgPatternDao
import com.ooustream.iptv.data.local.dao.FavoriteDao
import com.ooustream.iptv.data.local.dao.PosterCacheDao
import com.ooustream.iptv.data.local.dao.SearchHistoryDao
import com.ooustream.iptv.data.local.dao.SearchIndexDao
import com.ooustream.iptv.data.local.dao.SeriesTrackingDao
import com.ooustream.iptv.data.local.dao.VodCastDao
import com.ooustream.iptv.data.local.dao.WatchAnalyticsDao
import com.ooustream.iptv.data.local.dao.WatchProgressDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    // v8→v9: Add poster_cache table
    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `poster_cache` (
                    `tmdb_id` TEXT NOT NULL PRIMARY KEY,
                    `poster_url` TEXT,
                    `backdrop_url` TEXT,
                    `fetched_at` INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
        }
    }

    // v11→v12: Add vod_cast cache table (cast/director per movie, for actor search)
    private val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `vod_cast` (
                    `streamId` INTEGER NOT NULL PRIMARY KEY,
                    `cast` TEXT NOT NULL DEFAULT '',
                    `director` TEXT,
                    `fetchedAt` INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
        }
    }

    // v10→v11: Add blocked_categories table for parental controls
    private val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `blocked_categories` (
                    `section` TEXT NOT NULL,
                    `categoryId` TEXT NOT NULL,
                    `categoryName` TEXT NOT NULL DEFAULT '',
                    `blockedAt` INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(`section`, `categoryId`)
                )
            """.trimIndent())
        }
    }

    // v9→v10: Add series_tracking table
    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `series_tracking` (
                    `seriesId` INTEGER NOT NULL PRIMARY KEY,
                    `seriesTitle` TEXT NOT NULL DEFAULT '',
                    `posterUrl` TEXT,
                    `lastWatchedSeason` INTEGER NOT NULL DEFAULT 0,
                    `lastWatchedEpisode` INTEGER NOT NULL DEFAULT 0,
                    `lastWatchedEpisodeId` INTEGER NOT NULL DEFAULT 0,
                    `lastWatchedAt` INTEGER NOT NULL DEFAULT 0,
                    `lastKnownSeason` INTEGER NOT NULL DEFAULT 0,
                    `lastKnownEpisode` INTEGER NOT NULL DEFAULT 0,
                    `lastSyncedAt` INTEGER NOT NULL DEFAULT 0,
                    `newEpisodeCount` INTEGER NOT NULL DEFAULT 0,
                    `hasNewEpisodes` INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): OoustreamDatabase {
        return Room.databaseBuilder(
            context,
            OoustreamDatabase::class.java,
            "ooustream_db"
        )
            .addMigrations(MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
            .fallbackToDestructiveMigration() // safety net for ancient versions (pre-v8)
            .build()
    }

    @Provides
    fun provideFavoriteDao(db: OoustreamDatabase): FavoriteDao = db.favoriteDao()

    @Provides
    fun provideWatchProgressDao(db: OoustreamDatabase): WatchProgressDao = db.watchProgressDao()

    @Provides
    fun provideEpgCacheDao(db: OoustreamDatabase): EpgCacheDao = db.epgCacheDao()

    @Provides
    fun provideSearchHistoryDao(db: OoustreamDatabase): SearchHistoryDao = db.searchHistoryDao()

    @Provides
    fun provideCrashRecoveryDao(db: OoustreamDatabase): CrashRecoveryDao = db.crashRecoveryDao()

    @Provides
    fun provideContentCacheDao(db: OoustreamDatabase): ContentCacheDao = db.contentCacheDao()

    @Provides
    fun provideWatchAnalyticsDao(db: OoustreamDatabase): WatchAnalyticsDao = db.watchAnalyticsDao()

    @Provides
    fun provideSearchIndexDao(db: OoustreamDatabase): SearchIndexDao = db.searchIndexDao()

    @Provides
    fun provideChannelWatchLogDao(db: OoustreamDatabase): ChannelWatchLogDao = db.channelWatchLogDao()

    @Provides
    fun provideChannelScoreDao(db: OoustreamDatabase): ChannelScoreDao = db.channelScoreDao()

    @Provides
    fun provideEpgPatternDao(db: OoustreamDatabase): EpgPatternDao = db.epgPatternDao()

    @Provides
    fun providePosterCacheDao(db: OoustreamDatabase): PosterCacheDao = db.posterCacheDao()

    @Provides
    fun provideSeriesTrackingDao(db: OoustreamDatabase): SeriesTrackingDao = db.seriesTrackingDao()

    @Provides
    fun provideBlockedCategoryDao(db: OoustreamDatabase): BlockedCategoryDao = db.blockedCategoryDao()

    @Provides
    fun provideVodCastDao(db: OoustreamDatabase): VodCastDao = db.vodCastDao()
}
