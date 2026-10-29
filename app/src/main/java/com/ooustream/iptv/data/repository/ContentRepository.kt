package com.ooustream.iptv.data.repository

import com.ooustream.iptv.data.local.dao.VodCastDao
import com.ooustream.iptv.data.local.entity.VodCastEntity
import com.ooustream.iptv.data.model.*
import com.ooustream.iptv.data.remote.XtreamApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    suspend fun getShortEpg(streamId: Int, limit: Int? = null): EpgResponse {
        val creds = getCreds()
        return getApi().getShortEpg(creds.username, creds.password, streamId = streamId, limit = limit)
    }

    /**
     * Filters catalogue lists the caller already holds ([CatalogCache]) — it never downloads them.
     * Until v5.2.2 this fetched all three full lists on every query; with Search's own index
     * rebuild and Trending on top that was several ~10 MB parses at once and ran the heap out
     * (keithahg, AFTKRT: ANR at 0% free of 163 MB). Runs on [Dispatchers.Default], and matching
     * uses `ignoreCase` so no lowercase copy is made of every title per keystroke.
     */
    suspend fun search(
        query: String,
        allLive: List<LiveStream>,
        allVod: List<VodStream>,
        allSeries: List<Series>
    ): SearchResults {
        val q = query.trim()
        // Movies whose CACHED cast contains the query (the bulk VOD list has no cast, so this side
        // cache — filled by detail opens + the backfill worker — is the only actor source for VOD).
        val castIds = try { vodCastDao.findStreamIdsByCast(q).toSet() } catch (_: Exception) { emptySet() }

        return withContext(Dispatchers.Default) {
            val live = allLive.filter { it.name.contains(q, ignoreCase = true) }
            val vod = allVod.filter { it.name.contains(q, ignoreCase = true) || it.streamId in castIds }
            // Series bulk list already carries cast + director — match all three.
            val series = allSeries.filter {
                it.name.contains(q, ignoreCase = true) ||
                    it.cast?.contains(q, ignoreCase = true) == true ||
                    it.director?.contains(q, ignoreCase = true) == true
            }

            // "Starring {actor}" labels — only for results that matched on cast, NOT on the title.
            val lowerQuery = q.lowercase()
            val castMatches = mutableMapOf<String, String>()
            val castOnlyVod = vod.filter { !it.name.contains(q, ignoreCase = true) }
            val vodCastById = try {
                if (castOnlyVod.isEmpty()) emptyMap()
                else vodCastDao.getForIds(castOnlyVod.map { it.streamId }).associateBy { it.streamId }
            } catch (_: Exception) { emptyMap() }
            castOnlyVod.forEach { v ->
                matchedActor(vodCastById[v.streamId]?.cast, lowerQuery)
                    ?.let { castMatches["vod_${v.streamId}"] = it }
            }
            series.forEach { s ->
                if (!s.name.contains(q, ignoreCase = true)) {
                    matchedActor(s.cast, lowerQuery)?.let { castMatches["series_${s.seriesId}"] = it }
                }
            }

            SearchResults(live = live, vod = vod, series = series, castMatches = castMatches)
        }
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

    fun buildCatchUpUrl(streamId: Int, programme: CatchUpProgramme): String {
        val creds = getCreds()
        val minutes = ((programme.stopEpochSec - programme.startEpochSec) / 60L).toInt()
        return StreamUrlBuilder.timeshift(
            creds.serverUrl, creds.username, creds.password, streamId, programme.startEpochSec, minutes
        )
    }

    /**
     * Past programmes this channel can replay, newest first. Only rows the panel flags
     * `has_archive=1` that have already FINISHED (a replay of the airing show would just race the
     * live edge). Titles/descriptions arrive base64-encoded.
     */
    suspend fun getCatchUpProgrammes(streamId: Int): List<CatchUpProgramme> {
        val creds = getCreds()
        val nowSec = System.currentTimeMillis() / 1000L
        return getApi().getSimpleDataTable(creds.username, creds.password, streamId = streamId)
            .epgListings.orEmpty()
            .mapNotNull { p ->
                val start = p.startTimestamp?.toLongOrNull() ?: return@mapNotNull null
                val stop = p.stopTimestamp?.toLongOrNull() ?: return@mapNotNull null
                if (p.hasArchive != 1 || stop <= start || stop > nowSec) return@mapNotNull null
                CatchUpProgramme(
                    title = decodeB64(p.title).ifBlank { "Programme" },
                    description = decodeB64(p.description),
                    startEpochSec = start,
                    stopEpochSec = stop
                )
            }
            .sortedByDescending { it.startEpochSec }
    }

    private fun decodeB64(s: String?): String {
        if (s.isNullOrBlank()) return ""
        return runCatching {
            String(android.util.Base64.decode(s, android.util.Base64.DEFAULT), Charsets.UTF_8)
        }.getOrDefault(s).trim()
    }
}

/** One replayable past programme on a catch-up channel. Epoch seconds, UTC. */
data class CatchUpProgramme(
    val title: String,
    val description: String,
    val startEpochSec: Long,
    val stopEpochSec: Long
)

data class SearchResults(
    val live: List<LiveStream>,
    val vod: List<VodStream>,
    val series: List<Series>,
    /** "type_streamId" (e.g. "vod_123") -> matched actor name, for results matched on cast not title. */
    val castMatches: Map<String, String> = emptyMap()
)
