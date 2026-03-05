package com.ooustream.iptv.common

import android.util.Log
import com.ooustream.iptv.BuildConfig
import com.ooustream.iptv.data.local.dao.PosterCacheDao
import com.ooustream.iptv.data.local.entity.PosterCacheEntity
import com.ooustream.iptv.data.remote.TmdbApiService
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decision tree for resolving the best poster URL:
 *   1. Try PosterUrlRewriter (free URL rewrite for TMDB URLs)
 *   2. Check local Room cache for previous TMDB lookup
 *   3. Fetch from TMDB API using tmdb ID field (rate-limited)
 *
 * After first session: all posters load from cache — instant + sharp.
 */
@Singleton
class PosterUrlResolver @Inject constructor(
    private val tmdbApi: TmdbApiService,
    private val posterCacheDao: PosterCacheDao
) {
    companion object {
        private const val TAG = "PosterUrlResolver"
        private const val TMDB_POSTER_SIZE = "w500"
        private const val TMDB_BACKDROP_SIZE = "w1280"
        private const val TTL_MS = 30L * 24 * 60 * 60 * 1000 // 30 days
    }

    // Rate limit: max 5 concurrent TMDB requests (API limit: 40/10s)
    private val semaphore = Semaphore(5)

    /**
     * Resolve the best poster URL for a given stream icon and TMDB ID.
     * Returns the best available URL (rewritten, cached, or freshly fetched).
     * Falls back to original URL if all else fails.
     */
    suspend fun resolvePoster(streamIcon: String?, tmdbId: String?, contentType: String = "vod"): String? {
        // Step 1: Try rewriting (free, instant)
        val rewritten = PosterUrlRewriter.rewrite(streamIcon)
        if (rewritten != null && PosterUrlRewriter.isTmdbUrl(rewritten)) {
            return rewritten // Already a TMDB URL at correct size
        }

        // Step 2: Check local cache
        if (!tmdbId.isNullOrBlank()) {
            val cached = posterCacheDao.get(tmdbId)
            if (cached != null && !isExpired(cached)) {
                return cached.posterUrl ?: rewritten
            }

            // Step 3: Fetch from TMDB API
            val apiKey = BuildConfig.TMDB_API_KEY
            if (apiKey.isNotBlank()) {
                try {
                    val fetched = fetchFromTmdb(tmdbId, contentType, apiKey)
                    if (fetched != null) return fetched.posterUrl ?: rewritten
                } catch (e: Exception) {
                    Log.w(TAG, "TMDB poster lookup failed for $tmdbId: ${e.message}")
                }
            }
        }

        return rewritten ?: streamIcon
    }

    /**
     * Resolve the best backdrop URL for detail/hero screens.
     */
    suspend fun resolveBackdrop(streamIcon: String?, tmdbId: String?, contentType: String = "vod"): String? {
        val rewritten = PosterUrlRewriter.rewriteBackdrop(streamIcon)

        if (!tmdbId.isNullOrBlank()) {
            val cached = posterCacheDao.get(tmdbId)
            if (cached != null && !isExpired(cached) && cached.backdropUrl != null) {
                return cached.backdropUrl
            }

            val apiKey = BuildConfig.TMDB_API_KEY
            if (apiKey.isNotBlank()) {
                try {
                    val fetched = fetchFromTmdb(tmdbId, contentType, apiKey)
                    if (fetched?.backdropUrl != null) return fetched.backdropUrl
                } catch (e: Exception) {
                    Log.w(TAG, "TMDB backdrop lookup failed for $tmdbId: ${e.message}")
                }
            }
        }

        return rewritten ?: streamIcon
    }

    /**
     * Fetch poster+backdrop from TMDB API and cache the result.
     * Rate-limited via semaphore.
     */
    private suspend fun fetchFromTmdb(
        tmdbId: String,
        type: String,
        apiKey: String
    ): PosterCacheEntity? = semaphore.withPermit {
        try {
            val (poster, backdrop) = if (type == "series") {
                val r = tmdbApi.getTvDetails(tmdbId, apiKey)
                r.posterPath to r.backdropPath
            } else {
                val r = tmdbApi.getMovieDetails(tmdbId, apiKey)
                r.posterPath to r.backdropPath
            }

            val posterUrl = poster?.let {
                "https://image.tmdb.org/t/p/$TMDB_POSTER_SIZE$it"
            }
            val backdropUrl = backdrop?.let {
                "https://image.tmdb.org/t/p/$TMDB_BACKDROP_SIZE$it"
            }

            val entity = PosterCacheEntity(
                tmdbId = tmdbId,
                posterUrl = posterUrl,
                backdropUrl = backdropUrl
            )
            posterCacheDao.insert(entity)
            Log.d(TAG, "Cached TMDB poster for id=$tmdbId: poster=${posterUrl != null}, backdrop=${backdropUrl != null}")
            entity
        } catch (e: Exception) {
            Log.w(TAG, "TMDB API call failed for $tmdbId: ${e.message}")
            null
        }
    }

    private fun isExpired(entity: PosterCacheEntity): Boolean {
        return System.currentTimeMillis() - entity.fetchedAt > TTL_MS
    }
}
