package com.ooustream.iptv.data.repository

import com.google.gson.Gson
import com.ooustream.iptv.data.model.AuthResponse
import com.ooustream.iptv.data.model.XtreamCredentials
import com.ooustream.iptv.data.remote.XtreamApiService
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val credentialStore: CredentialStore,
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {
    suspend fun login(serverUrl: String, username: String, password: String): Result<AuthResponse> {
        return try {
            val api = createApiForServer(serverUrl)
            val response = api.authenticate(username, password)

            if (response.userInfo.auth == 1 && response.userInfo.status == "Active") {
                credentialStore.save(XtreamCredentials(serverUrl, username, password))
                Result.success(response)
            } else if (response.userInfo.status == "Expired") {
                Result.failure(Exception("Account expired"))
            } else {
                Result.failure(Exception("Invalid credentials"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun autoLogin(): Result<AuthResponse>? {
        val creds = credentialStore.load() ?: return null
        return login(creds.serverUrl, creds.username, creds.password)
    }

    fun logout() {
        credentialStore.clear()
    }

    fun getStoredCredentials(): XtreamCredentials? = credentialStore.load()

    /**
     * Fetches the latest account info from the server using stored credentials.
     * This re-authenticates to get fresh user_info and server_info data
     * (subscription status, expiry, active connections, etc.).
     */
    suspend fun getAccountInfo(): Result<AuthResponse> {
        val creds = credentialStore.load()
            ?: return Result.failure(Exception("No stored credentials"))
        return try {
            val api = createApiForServer(creds.serverUrl)
            val response = api.authenticate(creds.username, creds.password)
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun createApiForServer(serverUrl: String): XtreamApiService {
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(XtreamApiService::class.java)
    }
}
