package com.ooustream.iptv.data.model

import com.google.gson.annotations.SerializedName

data class EpgResponse(
    @SerializedName("epg_listings") val epgListings: List<EpgProgram>?
)

data class EpgProgram(
    @SerializedName("id") val id: String?,
    @SerializedName("epg_id") val epgId: String?,
    @SerializedName("title") val title: String?,
    @SerializedName("lang") val lang: String?,
    @SerializedName("start") val start: String?,
    @SerializedName("end") val end: String?,
    @SerializedName("description") val description: String?,
    @SerializedName("channel_id") val channelId: String?,
    @SerializedName("start_timestamp") val startTimestamp: String?,
    @SerializedName("stop_timestamp") val stopTimestamp: String?,
    @SerializedName("has_archive") val hasArchive: Int?,
    @SerializedName("now_playing") val nowPlaying: Int?
)
