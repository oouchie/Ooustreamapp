package com.ooustream.iptv.update

import com.google.gson.annotations.SerializedName

data class UpdateManifest(
    @SerializedName("version_name")
    val versionName: String,

    @SerializedName("version_code")
    val versionCode: Int,

    @SerializedName("download_url")
    val downloadUrl: String,

    @SerializedName("changelog")
    val changelog: String,

    @SerializedName("mandatory")
    val mandatory: Boolean
)
