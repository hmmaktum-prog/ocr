package com.example.ocr

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

// Fix PERF-09: Shared OkHttpClient Singleton across all classes
object HttpClientProvider {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            // Fix LOGIC-12: Kept at 5 minutes for large model downloads and slow network OCR
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
