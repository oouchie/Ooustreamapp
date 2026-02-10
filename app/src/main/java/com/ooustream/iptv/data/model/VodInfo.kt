package com.ooustream.iptv.data.model

import com.google.gson.annotations.SerializedName

data class VodInfo(
    @SerializedName("info") val info: VodDetail?,
    @SerializedName("movie_data") val movieData: VodMovieData?
)

data class VodDetail(
    @SerializedName("movie_image") val movieImage: String?,
    @SerializedName("plot") val plot: String?,
    @SerializedName("cast") val cast: String?,
    @SerializedName("director") val director: String?,
    @SerializedName("genre") val genre: String?,
    @SerializedName("releasedate") val releaseDate: String?,
    @SerializedName("rating") val rating: String?,
    @SerializedName("duration") val duration: String?,
    @SerializedName("duration_secs") val durationSecs: Int?,
    @SerializedName("backdrop_path") val backdropPath: List<String>?,
    @SerializedName("youtube_trailer") val youtubeTrailer: String?,
    @SerializedName("bitrate") val bitrate: Int?,
    @SerializedName("video") val video: VideoInfo?,
    @SerializedName("audio") val audio: AudioInfo?
)

data class VodMovieData(
    @SerializedName("stream_id") val streamId: Int?,
    @SerializedName("name") val name: String?,
    @SerializedName("added") val added: String?,
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("container_extension") val containerExtension: String?,
    @SerializedName("custom_sid") val customSid: String?,
    @SerializedName("direct_source") val directSource: String?
)
