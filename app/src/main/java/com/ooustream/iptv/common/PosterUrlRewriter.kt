package com.ooustream.iptv.common

import android.util.Log

/**
 * Rewrites TMDB image URLs to serve higher resolution images.
 * Most IPTV providers return TMDB poster URLs at w185 or w300 — far too small
 * for TV displays. This rewrites them to w500 (1080p TV) or w780 (4K TV).
 *
 * Zero API calls — pure URL string replacement on the same TMDB CDN.
 */
object PosterUrlRewriter {

    private const val TAG = "PosterUrlRewriter"

    // Target poster size for TV (1080p)
    private const val TV_POSTER_SIZE = "w500"
    // Target backdrop size for TV hero/detail screens
    private const val TV_BACKDROP_SIZE = "w1280"

    // Matches TMDB image URLs with any size parameter:
    //   https://image.tmdb.org/t/p/w185/aqZOJk.jpg
    //   https://image.tmdb.org/t/p/w600_and_h900_bestv2/aqZOJk.jpg
    //   https://image.tmdb.org/t/p/original/aqZOJk.jpg
    private val TMDB_PATTERN = Regex(
        """https?://image\.tmdb\.org/t/p/(w\d+(?:_and_h\d+_\w+)?|original)/(.+)"""
    )

    /**
     * Rewrite a poster URL to serve a higher resolution image.
     * TMDB URLs get their size param upgraded to w500.
     * Non-TMDB URLs pass through unchanged.
     */
    fun rewrite(url: String?): String? {
        if (url.isNullOrBlank()) return null

        val match = TMDB_PATTERN.find(url)
        if (match != null) {
            val currentSize = match.groupValues[1]
            val imagePath = match.groupValues[2]
            if (currentSize != TV_POSTER_SIZE) {
                val rewritten = "https://image.tmdb.org/t/p/$TV_POSTER_SIZE/$imagePath"
                Log.d(TAG, "Poster rewrite: $currentSize → $TV_POSTER_SIZE")
                return rewritten
            }
            return url // Already correct size
        }

        return url // Not a TMDB URL
    }

    /**
     * Rewrite a backdrop/hero URL to serve a high-res landscape image.
     * TMDB URLs get their size param upgraded to w1280.
     * Non-TMDB URLs pass through unchanged.
     */
    fun rewriteBackdrop(url: String?): String? {
        if (url.isNullOrBlank()) return null

        val match = TMDB_PATTERN.find(url)
        if (match != null) {
            val currentSize = match.groupValues[1]
            val imagePath = match.groupValues[2]
            if (currentSize != TV_BACKDROP_SIZE) {
                val rewritten = "https://image.tmdb.org/t/p/$TV_BACKDROP_SIZE/$imagePath"
                Log.d(TAG, "Backdrop rewrite: $currentSize → $TV_BACKDROP_SIZE")
                return rewritten
            }
            return url
        }

        return url
    }

    /**
     * Check if a URL points to TMDB CDN.
     */
    fun isTmdbUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return url.contains("image.tmdb.org/t/p/")
    }
}
