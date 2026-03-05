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
}

data class TmdbMovieResponse(
    @SerializedName("poster_path") val posterPath: String?,
    @SerializedName("backdrop_path") val backdropPath: String?
)

data class TmdbTvResponse(
    @SerializedName("poster_path") val posterPath: String?,
    @SerializedName("backdrop_path") val backdropPath: String?
)
