package com.ooustream.iptv.data.model

import com.google.gson.annotations.SerializedName

data class SeriesInfo(
    @SerializedName("seasons") val seasons: List<Season>?,
    @SerializedName("info") val info: SeriesDetail?,
    @SerializedName("episodes") val episodes: Map<String, List<Episode>>?
)

data class SeriesDetail(
    @SerializedName("name") val name: String?,
    @SerializedName("cover") val cover: String?,
    @SerializedName("plot") val plot: String?,
    @SerializedName("cast") val cast: String?,
    @SerializedName("director") val director: String?,
    @SerializedName("genre") val genre: String?,
    @SerializedName("releaseDate") val releaseDate: String?,
    @SerializedName("rating") val rating: String?,
    @SerializedName("rating_5based") val rating5based: Double?,
    @SerializedName("backdrop_path") val backdropPath: List<String>?,
    @SerializedName("youtube_trailer") val youtubeTrailer: String?,
    @SerializedName("episode_run_time") val episodeRunTime: String?,
    @SerializedName("category_id") val categoryId: String?
)

data class Season(
    @SerializedName("air_date") val airDate: String?,
    @SerializedName("episode_count") val episodeCount: Int?,
    @SerializedName("id") val id: Int?,
    @SerializedName("name") val name: String?,
    @SerializedName("overview") val overview: String?,
    @SerializedName("season_number") val seasonNumber: Int,
    @SerializedName("cover") val cover: String?,
    @SerializedName("cover_big") val coverBig: String?
)

data class Episode(
    @SerializedName("id") val id: String?,
    @SerializedName("episode_num") val episodeNum: Int,
    @SerializedName("title") val title: String?,
    @SerializedName("container_extension") val containerExtension: String?,
    @SerializedName("info") val info: EpisodeInfo?,
    @SerializedName("custom_sid") val customSid: String?,
    @SerializedName("added") val added: String?,
    @SerializedName("season") val season: Int?,
    @SerializedName("direct_source") val directSource: String?
)

data class EpisodeInfo(
    @SerializedName("movie_image") val movieImage: String?,
    @SerializedName("plot") val plot: String?,
    @SerializedName("releasedate") val releaseDate: String?,
    @SerializedName("rating") val rating: Double?,
    @SerializedName("duration_secs") val durationSecs: Int?,
    @SerializedName("duration") val duration: String?,
    @SerializedName("bitrate") val bitrate: Int?,
    @SerializedName("video") val video: VideoInfo?,
    @SerializedName("audio") val audio: AudioInfo?
)

data class VideoInfo(
    @SerializedName("codec_name") val codecName: String?,
    @SerializedName("width") val width: Int?,
    @SerializedName("height") val height: Int?
)

data class AudioInfo(
    @SerializedName("codec_name") val codecName: String?,
    @SerializedName("channels") val channels: Int?,
    @SerializedName("sample_rate") val sampleRate: String?
)
