package com.ooustream.iptv.data.repository

import com.ooustream.iptv.data.local.dao.VodCastDao
import com.ooustream.iptv.data.local.entity.VodCastEntity
import com.ooustream.iptv.data.model.*
import com.ooustream.iptv.data.remote.XtreamApiService
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContentRepository @Inject constructor(
    private val authRepository: AuthRepository,
    private val credentialStore: CredentialStore,
    private val vodCastDao: VodCastDao
) {
    private fun getApi(): XtreamApiService {
        val creds = credentialStore.load() ?: throw IllegalStateException("Not logged in")
        return authRepository.createApiForServer(creds.serverUrl)
    }

    private fun getCreds(): XtreamCredentials {
        return credentialStore.load() ?: throw IllegalStateException("Not logged in")
    }

    suspend fun getLiveCategories(): List<Category> {
        val creds = getCreds()
        return getApi().getLiveCategories(creds.username, creds.password)
    }

    suspend fun getVodCategories(): List<Category> {
        val creds = getCreds()
        return getApi().getVodCategories(creds.username, creds.password)
    }

    suspend fun getSeriesCategories(): List<Category> {
        val creds = getCreds()
        return getApi().getSeriesCategories(creds.username, creds.password)
    }

    suspend fun getLiveStreams(categoryId: String? = null): List<LiveStream> {
        val creds = getCreds()
        return getApi().getLiveStreams(creds.username, creds.password, categoryId = categoryId)
    }

    suspend fun getVodStreams(categoryId: String? = null): List<VodStream> {
        val creds = getCreds()
        return getApi().getVodStreams(creds.username, creds.password, categoryId = categoryId)
    }

    suspend fun getSeries(categoryId: String? = null): List<Series> {
        val creds = getCreds()
        return getApi().getSeries(creds.username, creds.password, categoryId = categoryId)
    }

    suspend fun getVodInfo(vodId: Int): VodInfo {
        val creds = getCreds()
        val info = getApi().getVodInfo(creds.username, creds.password, vodId = vodId)
        // Opportunistic actor-search cache: every movie-detail fetch records its cast/director, so
        // movies the user (or backfill worker) opens become searchable by actor for free.
        try {
            vodCastDao.upsert(
                VodCastEntity(
                    streamId = vodId,
                    cast = info.info?.cast ?: "",
                    director = info.info?.director,
                    fetchedAt = System.currentTimeMillis()
                )
            )
        } catch (_: Exception) { }
        return info
    }

    suspend fun getSeriesInfo(seriesId: Int): SeriesInfo {
        val creds = getCreds()
        return getApi().getSeriesInfo(creds.username, creds.password, seriesId = seriesId)
    }

    suspend fun getShortEpg(streamId: Int): EpgResponse {
        val creds = getCreds()
        return getApi().getShortEpg(creds.username, creds.password, streamId = streamId)
    }

    suspend fun search(query: String): SearchResults = coroutineScope {
        val creds = getCreds()
        val api = getApi()
        val lowerQuery = query.lowercase()

        // Movies whose CACHED cast contains the query (the bulk VOD list has no cast, so this side
        // cache — filled by detail opens + the backfill worker — is the only actor source for VOD).
        val castVodIdsDeferred = async {
            try { vodCastDao.findStreamIdsByCast(query).toSet() } catch (_: Exception) { emptySet<Int>() }
        }

        val liveDeferred = async {
            try {
                api.getLiveStreams(creds.username, creds.password)
                    .filter { it.name.lowercase().contains(lowerQuery) }
            } catch (e: Exception) { emptyList() }
        }
        val vodDeferred = async {
            try {
                val all = api.getVodStreams(creds.username, creds.password)
                val castIds = castVodIdsDeferred.await()
                all.filter { it.name.lowercase().contains(lowerQuery) || it.streamId in castIds }
            } catch (e: Exception) { emptyList() }
        }
        val seriesDeferred = async {
            try {
                // Series bulk list already carries cast + director — match all three.
                api.getSeries(creds.username, creds.password).filter {
                    it.name.lowercase().contains(lowerQuery) ||
                        it.cast?.lowercase()?.contains(lowerQuery) == true ||
                        it.director?.lowercase()?.contains(lowerQuery) == true
                }
            } catch (e: Exception) { emptyList() }
        }

        val live = liveDeferred.await()
        val vod = vodDeferred.await()
        val series = seriesDeferred.await()

        // "Starring {actor}" labels — only for results that matched on cast, NOT on the title.
        val castMatches = mutableMapOf<String, String>()
        val vodCastById = try {
            if (vod.isEmpty()) emptyMap()
            else vodCastDao.getForIds(vod.map { it.streamId }).associateBy { it.streamId }
        } catch (_: Exception) { emptyMap() }
        vod.forEach { v ->
            if (!v.name.lowercase().contains(lowerQuery)) {
                matchedActor(vodCastById[v.streamId]?.cast, lowerQuery)
                    ?.let { castMatches["vod_${v.streamId}"] = it }
            }
        }
        series.forEach { s ->
            if (!s.name.lowercase().contains(lowerQuery)) {
                matchedActor(s.cast, lowerQuery)?.let { castMatches["series_${s.seriesId}"] = it }
            }
        }

        SearchResults(live = live, vod = vod, series = series, castMatches = castMatches)
    }

    /** Extract the specific cast member that contains the (already-lowercased) query, for labeling. */
    private fun matchedActor(cast: String?, lowerQuery: String): String? {
        if (cast.isNullOrBlank()) return null
        val member = cast.split(",").map { it.trim() }.firstOrNull { it.lowercase().contains(lowerQuery) }
        return member ?: if (cast.lowercase().contains(lowerQuery)) cast.take(40) else null
    }

    fun buildLiveStreamUrl(streamId: Int): String {
        val creds = getCreds()
        return StreamUrlBuilder.live(creds.serverUrl, creds.username, creds.password, streamId)
    }

    fun buildVodStreamUrl(streamId: Int, ext: String): String {
        val creds = getCreds()
        return StreamUrlBuilder.vod(creds.serverUrl, creds.username, creds.password, streamId, ext)
    }

    fun buildSeriesStreamUrl(streamId: Int, ext: String): String {
        val creds = getCreds()
        return StreamUrlBuilder.series(creds.serverUrl, creds.username, creds.password, streamId, ext)
    }
}

data class SearchResults(
    val live: List<LiveStream>,
    val vod: List<VodStream>,
    val series: List<Series>,
    /** "type_streamId" (e.g. "vod_123") -> matched actor name, for results matched on cast not title. */
    val castMatches: Map<String, String> = emptyMap()
)
