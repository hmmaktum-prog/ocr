package com.example.ocr

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import android.util.Log

object HistoryManager {
    private const val FILE_NAME = "ocr_session_history.json"
    private val gson = Gson()
    private const val TAG = "HistoryManager"

    suspend fun saveHistory(context: Context, messages: List<ChatMessage>) = withContext(Dispatchers.IO) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            OutputStreamWriter(file.outputStream()).use { writer ->
                gson.toJson(messages, writer)
            }
            Log.d(TAG, "History saved. Count: ${messages.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save OCR history", e)
        }
    }

    suspend fun loadHistory(context: Context): List<ChatMessage> = withContext(Dispatchers.IO) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return@withContext emptyList()
            
            InputStreamReader(file.inputStream()).use { reader ->
                val type = object : TypeToken<List<ChatMessage>>() {}.type
                val items: List<ChatMessage>? = gson.fromJson(reader, type)
                return@withContext items ?: emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load OCR history", e)
            emptyList()
        }
    }

    suspend fun clearHistory(context: Context) = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) {
            file.delete()
        }
    }
}
