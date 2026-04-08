package com.example.ocr

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool

// Fix PERF-09: Shared OkHttpClient Singleton across all classes
object HttpClientProvider {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(1, 2, TimeUnit.MINUTES))
            .connectTimeout(30, TimeUnit.SECONDS)
            // Fix LOGIC-12: Kept at 5 minutes for large model downloads and slow network OCR
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    val ocrClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(3, TimeUnit.MINUTES)
            .callTimeout(4, TimeUnit.MINUTES)
            .build()
    }
}
