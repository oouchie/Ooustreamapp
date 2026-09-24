package com.ooustream.iptv.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface for TMDB API — used to fetch high-res poster/backdrop
 * URLs when the Xtream Codes API returns low-quality non-TMDB images.
 */
interface TmdbApiService {

    @GET("movie/{id}")
    suspend fun getMovieDetails(
        @Path("id") tmdbId: String,
        @Query("api_key") apiKey: String
    ): TmdbMovieResponse

    @GET("tv/{id}")
    suspend fun getTvDetails(
        @Path("id") tmdbId: String,
        @Query("api_key") apiKey: String
    ): TmdbTvResponse

    /** Series lookup by name — the provider sends no TMDB id for series. */
    @GET("search/tv")
    suspend fun searchTv(
        @Query("query") query: String,
        @Query("first_air_date_year") year: Int?,
        @Query("api_key") apiKey: String
    ): TmdbTvSearchResponse

    @GET("tv/{id}/season/{season}")
    suspend fun getTvSeason(
        @Path("id") tmdbId: Int,
        @Path("season") season: Int,
        @Query("api_key") apiKey: String
    ): TmdbSeasonResponse
}

data class TmdbTvSearchResponse(
    @SerializedName("results") val results: List<TmdbTvSearchResult>?
)

data class TmdbTvSearchResult(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String?,
    @SerializedName("original_name") val originalName: String?,
    @SerializedName("first_air_date") val firstAirDate: String?
)

data class TmdbSeasonResponse(
    @SerializedName("episodes") val episodes: List<TmdbEpisode>?
)

data class TmdbEpisode(
    @SerializedName("episode_number") val episodeNumber: Int,
    @SerializedName("name") val name: String?
)

data class TmdbMovieResponse(
    @SerializedName("poster_path") val posterPath: String?,
    @SerializedName("backdrop_path") val backdropPath: String?
)

data class TmdbTvResponse(
    @SerializedName("poster_path") val posterPath: String?,
    @SerializedName("backdrop_path") val backdropPath: String?
)
