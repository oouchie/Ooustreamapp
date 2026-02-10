package com.ooustream.iptv.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor

class AuthInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // Auth params are passed per-request via @Query, not interceptor
        // This interceptor adds common headers
        val newRequest = request.newBuilder()
            .addHeader("User-Agent", "OoustreamIPTV/1.0")
            .addHeader("Accept", "application/json")
            .build()
        return chain.proceed(newRequest)
    }
}
