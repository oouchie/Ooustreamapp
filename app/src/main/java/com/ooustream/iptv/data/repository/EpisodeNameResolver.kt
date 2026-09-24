package com.ooustream.iptv.data.repository

import android.content.Context
import android.util.Log
import com.ooustream.iptv.BuildConfig
import com.ooustream.iptv.data.remote.TmdbApiService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real episode names from TMDB. The provider titles every episode "Show S01E01" and sends no TMDB
 * id for series, so the show is found by NAME + YEAR.
 *
 * A wrong name is worse than "Episode 3", so a match is accepted only when the normalized TMDB name
 * (or original name) equals the provider's name exactly AND the first-air year is within one year
 * of the provider's. Anything else returns an empty map and the UI keeps its fallback.
 *
 * Cached in SharedPreferences (not Room — no schema migration for a read-through cache) for 30
 * days: one search per show and one request per season, ever, per device.
 */
@Singleton
class EpisodeNameResolver @Inject constructor(
    private val tmdbApi: TmdbApiService,
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("tmdb_episode_names", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    /** episodeNumber → name for one season; empty when unknown or not confidently matched. */
    suspend fun seasonNames(seriesName: String, year: Int?, season: Int): Map<Int, String> {
        val key = BuildConfig.TMDB_API_KEY
        if (key.isBlank() || season <= 0 || seriesName.isBlank()) return emptyMap()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val tvId = tvIdFor(seriesName, year, key) ?: return@withLock emptyMap()
                    seasonFromCache(tvId, season) ?: fetchSeason(tvId, season, key)
                } catch (e: Exception) {
                    Log.w(TAG, "episode names unavailable for '$seriesName' S$season: ${e.message}")
                    emptyMap()
                }
            }
        }
    }

    private suspend fun tvIdFor(seriesName: String, year: Int?, key: String): Int? {
        val cacheKey = "id:${normalize(seriesName)}:${year ?: 0}"
        cached(cacheKey)?.let { return it.toIntOrNull()?.takeIf { id -> id > 0 } }
        val wanted = normalize(seriesName)
        fun pick(results: List<com.ooustream.iptv.data.remote.TmdbTvSearchResult>?) = results.orEmpty().firstOrNull { r ->
            val nameOk = normalize(r.name.orEmpty()) == wanted || normalize(r.originalName.orEmpty()) == wanted
            val airYear = r.firstAirDate?.take(4)?.toIntOrNull()
            nameOk && (year == null || airYear == null || kotlin.math.abs(airYear - year) <= 1)
        }
        // The year filter is exact on TMDB's side; a provider year is sometimes a season's year
        // rather than the premiere's, so retry unfiltered (still year-checked ±1 above) on a miss.
        val match = pick(tmdbApi.searchTv(seriesName, year, key).results)
            ?: if (year != null) pick(tmdbApi.searchTv(seriesName, null, key).results) else null
        // Cache misses too (id 0) so an unmatched show doesn't search again every visit.
        put(cacheKey, (match?.id ?: 0).toString())
        return match?.id
    }

    private fun seasonFromCache(tvId: Int, season: Int): Map<Int, String>? {
        val raw = cached("s:$tvId:$season") ?: return null
        val json = JSONObject(raw)
        return json.keys().asSequence().associate { it.toInt() to json.getString(it) }
    }

    private suspend fun fetchSeason(tvId: Int, season: Int, key: String): Map<Int, String> {
        val names = tmdbApi.getTvSeason(tvId, season, key).episodes.orEmpty()
            .mapNotNull { e ->
                // TMDB placeholders ("Episode 3") say nothing the numeral doesn't already.
                e.name?.trim()?.takeIf { it.isNotBlank() && !PLACEHOLDER.matches(it) }
                    ?.let { e.episodeNumber to it }
            }.toMap()
        put("s:$tvId:$season", JSONObject(names.mapKeys { it.key.toString() }).toString())
        return names
    }

    private fun cached(k: String): String? {
        val at = prefs.getLong("$k@t", 0L)
        if (at == 0L || System.currentTimeMillis() - at > TTL_MS) return null
        return prefs.getString(k, null)
    }

    private fun put(k: String, v: String) {
        prefs.edit().putString(k, v).putLong("$k@t", System.currentTimeMillis()).apply()
    }

    private companion object {
        const val TAG = "EpisodeNames"
        const val TTL_MS = 30L * 24 * 60 * 60 * 1000
        val PLACEHOLDER = Regex("(?i)^episode\\s+\\d+$")

        /** Case, accents, punctuation and "&"/"and" differences don't block a match. */
        fun normalize(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .lowercase()
                .replace("&", " and ")
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
    }
}
