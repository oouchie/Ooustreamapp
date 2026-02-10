package com.ooustream.iptv.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.ooustream.iptv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateService @Inject constructor(
    private val okHttpClient: OkHttpClient,
    @ApplicationContext private val context: Context
) {

    companion object {
        const val DEFAULT_MANIFEST_URL = "https://flarecoral.com/ooustream/update.json"
        private const val APK_FILE_NAME = "update.apk"
        private const val FILE_PROVIDER_AUTHORITY = "com.ooustream.iptv.fileprovider"
    }

    private val gson = Gson()

    /**
     * Checks the remote manifest for a newer version.
     * Returns the manifest if an update is available, null if the app is up-to-date.
     */
    suspend fun checkForUpdate(
        manifestUrl: String = DEFAULT_MANIFEST_URL
    ): Result<UpdateManifest?> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(manifestUrl)
                .header("Cache-Control", "no-cache")
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                throw Exception("Failed to fetch update manifest: HTTP ${response.code}")
            }

            val body = response.body?.string()
                ?: throw Exception("Empty response from update server")

            val manifest = gson.fromJson(body, UpdateManifest::class.java)
                ?: throw Exception("Failed to parse update manifest")

            if (manifest.versionCode > BuildConfig.VERSION_CODE) {
                manifest
            } else {
                null
            }
        }
    }

    /**
     * Downloads the APK from the given URL to cache directory.
     * Reports download progress (0.0 to 1.0) via the [onProgress] callback.
     */
    suspend fun downloadApk(
        url: String,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                throw Exception("Failed to download APK: HTTP ${response.code}")
            }

            val responseBody = response.body
                ?: throw Exception("Empty response body when downloading APK")

            val contentLength = responseBody.contentLength()
            val outputFile = File(context.cacheDir, APK_FILE_NAME)

            // Delete any previous download
            if (outputFile.exists()) {
                outputFile.delete()
            }

            responseBody.byteStream().use { inputStream ->
                FileOutputStream(outputFile).use { outputStream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = 0L

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        if (contentLength > 0) {
                            val progress = totalBytesRead.toFloat() / contentLength.toFloat()
                            onProgress(progress.coerceIn(0f, 1f))
                        }
                    }

                    outputStream.flush()
                }
            }

            // Final progress report
            onProgress(1f)

            outputFile
        }
    }

    /**
     * Launches the system package installer for the given APK file.
     * Uses FileProvider to grant URI permissions on API 24+.
     */
    fun installApk(file: File) {
        val apkUri: Uri = FileProvider.getUriForFile(
            context,
            FILE_PROVIDER_AUTHORITY,
            file
        )

        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(installIntent)
    }

    /**
     * Returns the current app version name (e.g. "1.0.0").
     */
    fun getCurrentVersion(): String = BuildConfig.VERSION_NAME

    /**
     * Returns the current app version code (e.g. 1).
     */
    fun getCurrentVersionCode(): Int = BuildConfig.VERSION_CODE
}
