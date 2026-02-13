package com.ooustream.iptv.di

import android.content.Context
import androidx.room.Room
import com.ooustream.iptv.data.local.OoustreamDatabase
import com.ooustream.iptv.data.local.dao.ChannelScoreDao
import com.ooustream.iptv.data.local.dao.ChannelWatchLogDao
import com.ooustream.iptv.data.local.dao.ContentCacheDao
import com.ooustream.iptv.data.local.dao.CrashRecoveryDao
import com.ooustream.iptv.data.local.dao.EpgCacheDao
import com.ooustream.iptv.data.local.dao.EpgPatternDao
import com.ooustream.iptv.data.local.dao.FavoriteDao
import com.ooustream.iptv.data.local.dao.SearchHistoryDao
import com.ooustream.iptv.data.local.dao.SearchIndexDao
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

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): OoustreamDatabase {
        return Room.databaseBuilder(
            context,
            OoustreamDatabase::class.java,
            "ooustream_db"
        ).fallbackToDestructiveMigration().build()
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
}
