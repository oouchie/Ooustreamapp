package com.ooustream.iptv.common

import android.util.LruCache

/**
 * LRU cache mapping stream identifiers to dominant colors.
 * Colors are extracted from images via Palette and stored for
 * instant placeholder rendering.
 */
object DominantColorCache {
    private val cache = LruCache<String, Int>(500)

    fun put(key: String, color: Int) {
        cache.put(key, color)
    }

    fun get(key: String): Int? {
        return cache.get(key)
    }
}
