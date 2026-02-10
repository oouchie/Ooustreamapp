package com.ooustream.iptv.common

import android.app.ActivityManager
import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AdaptiveImageLoader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val imageLoader: ImageLoader by lazy {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryClass = am.memoryClass // MB
        val isLowMemory = memoryClass <= 128 // Fire TV Stick = 1GB

        val memoryCachePercent = if (isLowMemory) 0.10 else 0.15
        val maxSize = (memoryClass * 1024L * 1024L * memoryCachePercent).toLong()

        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizeBytes(maxSize.toInt())
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(50 * 1024 * 1024) // 50MB disk cache
                    .build()
            }
            .allowRgb565(true) // Use RGB_565 by default (50% less memory)
            .crossfade(200)
            .build()
    }

    fun trimForPlayback() {
        imageLoader.memoryCache?.clear()
    }
}
