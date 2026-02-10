package com.ooustream.iptv.data.repository

import com.ooustream.iptv.data.model.*
import com.ooustream.iptv.data.remote.XtreamApiService
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContentRepository @Inject constructor(
    private val authRepository: AuthRepository,
    private val credentialStore: CredentialStore
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
        return getApi().getVodInfo(creds.username, creds.password, vodId = vodId)
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

        val liveDeferred = async {
            try {
                api.getLiveStreams(creds.username, creds.password)
                    .filter { it.name.lowercase().contains(lowerQuery) }
            } catch (e: Exception) { emptyList() }
        }
        val vodDeferred = async {
            try {
                api.getVodStreams(creds.username, creds.password)
                    .filter { it.name.lowercase().contains(lowerQuery) }
            } catch (e: Exception) { emptyList() }
        }
        val seriesDeferred = async {
            try {
                api.getSeries(creds.username, creds.password)
                    .filter { it.name.lowercase().contains(lowerQuery) }
            } catch (e: Exception) { emptyList() }
        }

        SearchResults(
            live = liveDeferred.await(),
            vod = vodDeferred.await(),
            series = seriesDeferred.await()
        )
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
    val series: List<Series>
)
