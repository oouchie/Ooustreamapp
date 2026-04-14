package com.ooustream.iptv.update

import android.os.Build
import com.google.gson.annotations.SerializedName

data class UpdateManifest(
    @SerializedName("version_name")
    val versionName: String,

    @SerializedName("version_code")
    val versionCode: Int,

    @SerializedName("download_url")
    val downloadUrl: String,

    @SerializedName("download_url_arm64")
    val downloadUrlArm64: String? = null,

    @SerializedName("download_url_arm32")
    val downloadUrlArm32: String? = null,

    @SerializedName("changelog")
    val changelog: String,

    @SerializedName("mandatory")
    val mandatory: Boolean
) {
    /**
     * Picks the right APK URL for this device's primary ABI, falling back to
     * the universal `download_url` if an ABI-specific URL isn't published.
     */
    fun resolveDownloadUrl(): String {
        val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        return when {
            primaryAbi == "arm64-v8a" && !downloadUrlArm64.isNullOrBlank() -> downloadUrlArm64
            primaryAbi == "armeabi-v7a" && !downloadUrlArm32.isNullOrBlank() -> downloadUrlArm32
            else -> downloadUrl
        }
    }
}
