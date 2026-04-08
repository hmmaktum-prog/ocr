package com.example.ocr

import android.graphics.Bitmap
import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val type: Type,
    val timestamp: Long = System.currentTimeMillis(),

    // User properties
    val fileName: String? = null,
    val thumbnailPath: String? = null,
    val pageCount: Int = 0,
    val fileSizeMb: Float = 0f,

    // Bot properties
    var state: BotState = BotState.THINKING,
    var streamedText: String = "",
    var currentPage: Int = 0,
    var totalPages: Int = 0,
    var elapsedSeconds: Int = 0,
    var tokPerSec: Double? = null,
    var errorMessage: String? = null,
    var isMarkdownEnabled: Boolean = true
) {
    enum class Type { USER, BOT }
    enum class BotState { THINKING, STREAMING, DONE, ERROR }
}
