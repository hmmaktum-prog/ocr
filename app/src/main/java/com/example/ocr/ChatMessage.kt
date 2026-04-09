package com.example.ocr

import java.util.UUID

// Fix MEDIUM-14: Changed from data class to regular class.
// data class with mutable `var` properties breaks equals/hashCode contracts.
// Using `id` for identity comparison instead.
class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val type: Type,
    val timestamp: Long = System.currentTimeMillis(),

    // User properties
    val fileName: String? = null,
    val thumbnailPath: String? = null,
    val pageCount: Int = 0,
    val fileSizeMb: Float = 0f,

    // Bot properties (mutable — updated during streaming)
    var state: BotState = BotState.THINKING,
    var streamedText: String = "",
    var currentPage: Int = 0,
    var totalPages: Int = 0,
    var elapsedSeconds: Int = 0,
    var tokPerSec: Double? = null,
    var errorMessage: String? = null,
    var isMarkdownEnabled: Boolean = true,
    var pagesContent: MutableList<String> = mutableListOf()
) {
    enum class Type { USER, BOT }
    enum class BotState { THINKING, STREAMING, DONE, ERROR }

    // Identity-based equals/hashCode using immutable `id`
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChatMessage) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    // Provide copy() for USER messages (thumbnailPath updates)
    fun copy(
        id: String = this.id,
        type: Type = this.type,
        timestamp: Long = this.timestamp,
        fileName: String? = this.fileName,
        thumbnailPath: String? = this.thumbnailPath,
        pageCount: Int = this.pageCount,
        fileSizeMb: Float = this.fileSizeMb,
        state: BotState = this.state,
        streamedText: String = this.streamedText,
        currentPage: Int = this.currentPage,
        totalPages: Int = this.totalPages,
        elapsedSeconds: Int = this.elapsedSeconds,
        tokPerSec: Double? = this.tokPerSec,
        errorMessage: String? = this.errorMessage,
        isMarkdownEnabled: Boolean = this.isMarkdownEnabled,
        pagesContent: MutableList<String> = this.pagesContent.toMutableList()
    ): ChatMessage = ChatMessage(
        id, type, timestamp, fileName, thumbnailPath, pageCount, fileSizeMb,
        state, streamedText, currentPage, totalPages, elapsedSeconds,
        tokPerSec, errorMessage, isMarkdownEnabled, pagesContent
    )
}
