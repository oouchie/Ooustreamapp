package com.ooustream.iptv.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ooustream.iptv.common.StreamDiagnosticLogger
import com.ooustream.iptv.data.local.dao.EpgCacheDao
import com.ooustream.iptv.data.local.entity.EpgCacheEntity
import com.ooustream.iptv.data.model.EpgProgram
import android.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EpgCacheRepository @Inject constructor(
    private val epgCacheDao: EpgCacheDao,
    private val contentRepository: ContentRepository,
    private val streamDiagnosticLogger: StreamDiagnosticLogger
) {
    private val gson = Gson()
    private val epgListType = object : TypeToken<List<EpgProgram>>() {}.type

    /**
     * Cached EPG for a stream. [minPrograms] > 0 (EPG grid guide) re-fetches with the Xtream
     * `limit` param when the cached entry is too short — a player-path fetch caches only the
     * server-default ~4 listings, which covers the banner but not a multi-hour guide lane.
     */
    suspend fun getEpg(streamId: Int, minPrograms: Int = 0): List<EpgProgram> {
        // Check cache first — cached programs are already decoded
        val cached = epgCacheDao.get(streamId)
        if (cached != null && !isExpired(cached.fetchedAt)) {
            val programs = deserializePrograms(cached.programs)
            if (minPrograms <= 0 || programs.size >= minPrograms) return programs
        }

        return fetchAndCache(streamId, if (minPrograms > 0) minPrograms else null)
    }

    /**
     * Force-refresh EPG from server, bypassing cache.
     * Used when cached EPG has no program matching current time.
     */
    suspend fun forceRefresh(streamId: Int): List<EpgProgram> {
        return fetchAndCache(streamId)
    }

    private suspend fun fetchAndCache(streamId: Int, limit: Int? = null): List<EpgProgram> {
        // Fetch from network and decode Base64 title/description
        val startMs = System.currentTimeMillis()
        val programs = try {
            (contentRepository.getShortEpg(streamId, limit).epgListings ?: emptyList()).map { p ->
                p.copy(
                    title = decodeBase64(p.title),
                    description = decodeBase64(p.description)
                )
            }
        } catch (e: Exception) {
            streamDiagnosticLogger.logApiError(
                "get_short_epg",
                "streamId=$streamId, cls=${e.javaClass.simpleName}, msg=${e.message}, " +
                    "cause=${e.cause?.javaClass?.simpleName}:${e.cause?.message}"
            )
            emptyList()
        }
        val durationMs = System.currentTimeMillis() - startMs

        if (programs.isEmpty()) {
            // 200 OK but empty listings — almost always provider's XMLTV ingestion is broken.
            // Silent failure until v3.7.2; log so Send Debug Log captures it.
            streamDiagnosticLogger.logAppEvent(
                "EPG_EMPTY",
                "streamId=$streamId, took=${durationMs}ms"
            )
        }

        // Cache the result
        if (programs.isNotEmpty()) {
            val entity = EpgCacheEntity(
                streamId = streamId,
                programs = serializePrograms(programs),
                fetchedAt = System.currentTimeMillis()
            )
            epgCacheDao.insert(entity)
        }

        return programs
    }

    suspend fun clearExpired() {
        val cutoff = System.currentTimeMillis() - CACHE_DURATION_MS
        epgCacheDao.deleteExpired(cutoff)
    }

    private fun isExpired(fetchedAt: Long): Boolean {
        return System.currentTimeMillis() - fetchedAt > CACHE_DURATION_MS
    }

    private fun serializePrograms(programs: List<EpgProgram>): String {
        return gson.toJson(programs)
    }

    private fun deserializePrograms(json: String): List<EpgProgram> {
        return try {
            gson.fromJson(json, epgListType) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun decodeBase64(encoded: String?): String? {
        if (encoded.isNullOrBlank()) return encoded
        return try {
            String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) {
            encoded // Return as-is if not valid Base64
        }
    }

    companion object {
        private const val CACHE_DURATION_MS = 5 * 60 * 1000L // 5 minutes
    }
}
