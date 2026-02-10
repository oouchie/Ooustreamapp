package com.ooustream.iptv.di

import android.content.Context
import coil.ImageLoader
import com.ooustream.iptv.common.AdaptiveImageLoader
import com.ooustream.iptv.common.NetworkMonitor
import com.ooustream.iptv.data.repository.CredentialStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideCredentialStore(@ApplicationContext context: Context): CredentialStore {
        return CredentialStore(context)
    }

    @Provides
    @Singleton
    fun provideImageLoader(adaptiveImageLoader: AdaptiveImageLoader): ImageLoader {
        return adaptiveImageLoader.imageLoader
    }

    @Provides
    @Singleton
    fun provideNetworkMonitor(@ApplicationContext context: Context): NetworkMonitor {
        return NetworkMonitor(context)
    }
}
