package com.ooustream.iptv.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ooustream.iptv.data.local.dao.EpgCacheDao
import com.ooustream.iptv.data.local.entity.EpgCacheEntity
import com.ooustream.iptv.data.model.EpgProgram
import android.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EpgCacheRepository @Inject constructor(
    private val epgCacheDao: EpgCacheDao,
    private val contentRepository: ContentRepository
) {
    private val gson = Gson()
    private val epgListType = object : TypeToken<List<EpgProgram>>() {}.type

    suspend fun getEpg(streamId: Int): List<EpgProgram> {
        // Check cache first — apply decodeBase64 as safety for stale pre-fix cache entries
        val cached = epgCacheDao.get(streamId)
        if (cached != null && !isExpired(cached.fetchedAt)) {
            return deserializePrograms(cached.programs).map { p ->
                p.copy(
                    title = decodeBase64(p.title),
                    description = decodeBase64(p.description)
                )
            }
        }

        // Fetch from network and decode Base64 title/description
        val programs = try {
            (contentRepository.getShortEpg(streamId).epgListings ?: emptyList()).map { p ->
                p.copy(
                    title = decodeBase64(p.title),
                    description = decodeBase64(p.description)
                )
            }
        } catch (e: Exception) {
            emptyList()
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
        private const val CACHE_DURATION_MS = 30 * 60 * 1000L // 30 minutes
    }
}
