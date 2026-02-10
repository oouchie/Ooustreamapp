package com.ooustream.iptv.speedtest

import android.graphics.Color
import com.ooustream.iptv.data.repository.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

enum class SpeedRating(val label: String, val color: Int) {
    EXCELLENT(">50 Mbps", Color.parseColor("#10B981")),
    GOOD("20-50 Mbps", Color.parseColor("#FFC107")),
    FAIR("5-20 Mbps", Color.parseColor("#F59E0B")),
    POOR("<5 Mbps", Color.parseColor("#EF4444"))
}

data class SpeedResult(
    val pingMs: Long,
    val downloadMbps: Float,
    val rating: SpeedRating
)

@Singleton
class SpeedTestService @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val credentialStore: CredentialStore
) {

    /**
     * Sends 3 HEAD requests to the server and returns the average round-trip time in milliseconds.
     */
    suspend fun runPingTest(serverUrl: String): Long = withContext(Dispatchers.IO) {
        val url = serverUrl.trimEnd('/')
        val request = Request.Builder()
            .url(url)
            .head()
            .build()

        val times = mutableListOf<Long>()
        repeat(3) {
            try {
                val start = System.nanoTime()
                okHttpClient.newCall(request).execute().use { /* close body */ }
                val elapsed = (System.nanoTime() - start) / 1_000_000L
                times.add(elapsed)
            } catch (e: Exception) {
                // If a single ping fails, record a high value so the average reflects degradation
                times.add(5000L)
            }
        }
        times.average().toLong()
    }

    /**
     * Downloads the live categories endpoint and measures throughput in Mbps.
     * Uses the Xtream Codes API format: /player_api.php?username=X&password=Y&action=get_live_categories
     */
    suspend fun runDownloadTest(
        serverUrl: String,
        username: String,
        password: String
    ): Float = withContext(Dispatchers.IO) {
        val baseUrl = serverUrl.trimEnd('/')
        val url = "$baseUrl/player_api.php?username=$username&password=$password&action=get_live_categories"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val start = System.nanoTime()
        val response = okHttpClient.newCall(request).execute()
        val bytes = response.use { it.body?.bytes() }
        val elapsedNs = System.nanoTime() - start

        val bytesReceived = bytes?.size?.toLong() ?: 0L
        val elapsedSeconds = elapsedNs / 1_000_000_000.0

        if (elapsedSeconds <= 0.0 || bytesReceived == 0L) {
            return@withContext 0f
        }

        // Convert bytes/second to megabits/second: (bytes * 8) / (seconds * 1_000_000)
        val mbps = (bytesReceived * 8.0) / (elapsedSeconds * 1_000_000.0)
        mbps.toFloat()
    }

    /**
     * Runs the full speed test: loads credentials, measures ping and download, returns a rated result.
     * @throws IllegalStateException if no credentials are stored.
     */
    suspend fun runFullTest(): SpeedResult {
        val credentials = credentialStore.load()
            ?: throw IllegalStateException("No server credentials found. Please log in first.")

        val pingMs = runPingTest(credentials.serverUrl)
        val downloadMbps = runDownloadTest(
            credentials.serverUrl,
            credentials.username,
            credentials.password
        )
        val rating = calculateRating(downloadMbps)

        return SpeedResult(
            pingMs = pingMs,
            downloadMbps = downloadMbps,
            rating = rating
        )
    }

    private fun calculateRating(mbps: Float): SpeedRating = when {
        mbps > 50f -> SpeedRating.EXCELLENT
        mbps > 20f -> SpeedRating.GOOD
        mbps > 5f  -> SpeedRating.FAIR
        else       -> SpeedRating.POOR
    }
}
