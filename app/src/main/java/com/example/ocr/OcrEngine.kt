package com.example.ocr

import android.graphics.Bitmap
import android.util.Log
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OcrEngine {
    companion object {
        private const val TAG = "OcrEngine"
        private const val MAX_RETRIES = 3

        // Fix PERF-09: Use shared Http client
        private val client = HttpClientProvider.client
    }

    /** Result of a single OCR inference call */
    data class OcrResult(
        val text: String,
        val tokensPerSecond: Double?
    )

    sealed class StreamToken {
        data class Thinking(val elapsed: Long) : StreamToken()
        data class Text(val content: String) : StreamToken()
        data class Done(val fullText: String, val tokPerSec: Double?) : StreamToken()
        data class Error(val message: String) : StreamToken()
    }

    private fun prepareImageForOcr(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        val scale = if (width > maxDimension || height > maxDimension) {
            maxDimension.toFloat() / maxOf(width, height)
        } else {
            1.0f
        }

        val newWidth = (width * scale).toInt().coerceAtLeast(1)
        val newHeight = (height * scale).toInt().coerceAtLeast(1)

        val dest = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(dest)

        val contrast = 1.3f
        val brightness = -10f
        val colorMatrix = android.graphics.ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, brightness,
            0f, contrast, 0f, 0f, brightness,
            0f, 0f, contrast, 0f, brightness,
            0f, 0f, 0f, 1f, 0f
        ))

        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        paint.colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)

        val matrix = android.graphics.Matrix()
        matrix.setScale(scale, scale)

        canvas.drawBitmap(bitmap, matrix, paint)
        return dest
    }

    private fun buildChatRequestBody(base64Image: String, stream: Boolean): JSONObject {
        val imageContent = JSONObject().apply {
            put("type", "image_url")
            put("image_url", JSONObject().apply {
                put("url", "data:image/jpeg;base64,$base64Image")
            })
        }
        val textContent = JSONObject().apply {
            put("type", "text")
            put("text", "OCR:")
        }
        val messagesArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray().apply {
                    put(imageContent)
                    put(textContent)
                })
            })
        }
        return JSONObject().apply {
            put("model", "paddleocr")
            put("messages", messagesArray)
            put("max_tokens", 4096)
            put("temperature", 0.0)
            put("stream", stream)
            put("stop", JSONArray().apply {
                put("<|im_end|>")
                put("<|endoftext|>")
                put("</s>")
            })
        }
    }

    // Fix HIGH-03 & MEDIUM-11: Simplified retry with coroutine-cancellable OkHttp calls
    suspend fun processImage(bitmap: Bitmap): Result<OcrResult> {
        var prepared: Bitmap? = null
        return try {
            prepared = prepareImageForOcr(bitmap, 1536)
            // Fix MEDIUM-12: Use toByteArray to avoid extra String copy
            val outputStream = ByteArrayOutputStream()
            val base64Out = android.util.Base64OutputStream(outputStream, Base64.NO_WRAP)
            val pixels = prepared.width * prepared.height
            val quality = if (pixels > 2_000_000) 80 else 85
            prepared.compress(Bitmap.CompressFormat.JPEG, quality, base64Out)
            base64Out.close()
            val base64Bytes = outputStream.toByteArray()
            val base64Image = String(base64Bytes, Charsets.US_ASCII)

            val jsonBody = buildChatRequestBody(base64Image, stream = false)

            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(LlamaServerManager.CHAT_URL)
                .post(requestBody)
                .build()

            var responseBody = ""
            for (attempt in 0 until MAX_RETRIES) {
                try {
                    val call = HttpClientProvider.ocrClient.newCall(request)
                    // Cancel HTTP call when coroutine is cancelled
                    currentCoroutineContext()[Job]?.invokeOnCompletion {
                        if (it is CancellationException) call.cancel()
                    }
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            if (response.code in listOf(429, 503) && attempt < MAX_RETRIES - 1) {
                                delay(1000L * (attempt + 1))
                                return@use
                            }
                            throw IOException("Server returned HTTP ${response.code}: ${response.message}")
                        }
                        responseBody = response.body?.source()?.readUtf8(5_000_000) ?: ""
                    }
                    if (responseBody.isNotEmpty()) break
                    if (attempt >= MAX_RETRIES - 1) throw IOException("Server returned empty response after $MAX_RETRIES attempts")
                    delay(1000L * (attempt + 1))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val isRetryable = attempt < MAX_RETRIES - 1 && (
                        e is java.net.SocketTimeoutException ||
                        e.message?.contains("503") == true ||
                        e.message?.contains("429") == true
                    )
                    if (isRetryable) {
                        delay(1000L * (attempt + 1))
                    } else throw e
                }
            }

            val jsonResponse: JSONObject
            try {
                jsonResponse = JSONObject(responseBody)
            } catch (e: JSONException) {
                throw IOException("Invalid server response format: ${responseBody.take(100)}")
            }

            if (jsonResponse.has("error")) {
                val errMsg = jsonResponse.optString("error", "Unknown server error")
                throw IOException("llama-server error: $errMsg")
            }

            // Parse chat completions response: choices[0].message.content
            val content = jsonResponse
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                ?: ""

            if (content.isEmpty()) {
                Log.w(TAG, "Server returned empty content")
            }

            // Extract tok/s from timings (llama-server extension field)
            val tokPerSec = jsonResponse.optJSONObject("timings")
                ?.optDouble("predicted_per_second")
                ?.takeIf { it.isFinite() && it > 0.0 }
            Log.d(TAG, "OCR complete: ${content.length} chars, tok/s=${tokPerSec ?: "N/A"}")

            Result.success(OcrResult(text = content.trim(), tokensPerSecond = tokPerSec))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "processImage failed", e)
            Result.failure(e)
        } finally {
            if (prepared != null && prepared != bitmap) {
                prepared.recycle()
            }
        }
    }

    // Fix MEDIUM-09: SSE streaming with coroutine cancellation support
    fun processImageStreaming(bitmap: Bitmap, maxDimension: Int = 1536): Flow<StreamToken> = flow {
        emit(StreamToken.Thinking(0))
        var prepared: Bitmap? = null
        try {
            prepared = prepareImageForOcr(bitmap, maxDimension)
            val outputStream = ByteArrayOutputStream()
            val base64Out = android.util.Base64OutputStream(outputStream, Base64.NO_WRAP)
            val pixels = prepared.width * prepared.height
            val quality = if (pixels > 2_000_000) 80 else 85
            prepared.compress(Bitmap.CompressFormat.JPEG, quality, base64Out)
            base64Out.close()
            val base64Bytes = outputStream.toByteArray()
            val base64Image = String(base64Bytes, Charsets.US_ASCII)

            val jsonBody = buildChatRequestBody(base64Image, stream = true)

            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(LlamaServerManager.CHAT_URL)
                .post(requestBody)
                .addHeader("Accept", "text/event-stream")
                .build()

            val call = HttpClientProvider.ocrClient.newCall(request)
            // Fix MEDIUM-09: Cancel HTTP call when coroutine is cancelled
            currentCoroutineContext()[Job]?.invokeOnCompletion {
                if (it is CancellationException) call.cancel()
            }

            call.execute().use { response ->
                if (!response.isSuccessful) {
                    emit(StreamToken.Error("Server returned HTTP ${response.code}"))
                    return@use
                }

                val source = response.body?.source()
                if (source == null) {
                    emit(StreamToken.Error("Empty response body"))
                    return@use
                }

                val fullText = java.lang.StringBuilder()
                var tokensPerSecond: Double? = null

                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.startsWith("data: ")) {
                        val text = line.substring(6).trim()
                        if (text == "[DONE]") break
                        if (text.isEmpty()) continue

                        try {
                            val json = JSONObject(text)
                            if (json.has("error")) {
                                emit(StreamToken.Error(json.optString("error")))
                                return@use
                            }
                            // Chat completions streaming: choices[0].delta.content
                            val choices = json.optJSONArray("choices")
                            val delta = choices?.optJSONObject(0)?.optJSONObject("delta")
                            
                            // Handling typical DeepSeek reasoning_content
                            val reasoningContent = delta?.optString("reasoning_content", "") ?: ""
                            if (reasoningContent.isNotEmpty()) {
                                fullText.append(reasoningContent)
                                emit(StreamToken.Text(reasoningContent))
                            }
                            
                            val content = delta?.optString("content", "") ?: ""
                            if (content.isNotEmpty()) {
                                fullText.append(content)
                                emit(StreamToken.Text(content))
                            }
                            // Check finish_reason for stop signal
                            val finishReason = choices?.optJSONObject(0)?.optString("finish_reason")
                            if (finishReason == "stop" || finishReason == "length") {
                                // Try to extract tok/s from timings extension or usage
                                tokensPerSecond = json.optJSONObject("timings")
                                    ?.optDouble("predicted_per_second")
                                    ?.takeIf { it.isFinite() && it > 0.0 }
                            }
                        } catch (e: JSONException) {
                            Log.w(TAG, "Invalid SSE token JSON: $text")
                        }
                    }
                }
                emit(StreamToken.Done(fullText.toString().trim(), tokensPerSecond))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Streaming OCR failed", e)
            emit(StreamToken.Error(e.message ?: "Unknown error"))
        } finally {
            if (prepared != null && prepared != bitmap) {
                prepared.recycle()
            }
        }
    }

    fun generateDocx(texts: Array<String>, outputPath: String, pagePrefix: String = "Page"): Boolean {
        // Empty texts check
        if (texts.isEmpty() || texts.all { it.isBlank() }) {
            Log.w(TAG, "No text to generate DOCX from")
            return false
        }

        return try {
            val file = File(outputPath)
            file.parentFile?.mkdirs() // output directory না থাকলে তৈরি করো
            FileOutputStream(file).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    zos.putNextEntry(ZipEntry("[Content_Types].xml"))
                    zos.write(contentTypesXml().toByteArray())
                    zos.closeEntry()

                    zos.putNextEntry(ZipEntry("_rels/.rels"))
                    zos.write(relsXml().toByteArray())
                    zos.closeEntry()

                    zos.putNextEntry(ZipEntry("word/_rels/document.xml.rels"))
                    zos.write(documentRelsXml().toByteArray())
                    zos.closeEntry()

                    // styles.xml তৈরি করো — Heading1 style definition সহ
                    zos.putNextEntry(ZipEntry("word/styles.xml"))
                    zos.write(stylesXml().toByteArray())
                    zos.closeEntry()

                    zos.putNextEntry(ZipEntry("word/document.xml"))
                    zos.write(documentXml(texts, pagePrefix).toByteArray())
                    zos.closeEntry()
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "generateDocx failed", e)
            false
        }
    }

    // Fix MEDIUM-13: Support emoji/supplementary characters via XML numeric references
    private fun escapeXml(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                // Handle surrogate pairs — encode as XML numeric character reference
                c.isHighSurrogate() -> {
                    if (i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                        val codePoint = Character.toCodePoint(c, text[i + 1])
                        out.append("&#x${Integer.toHexString(codePoint)};")
                        i++ // skip low surrogate
                    }
                    // Lone high surrogate — skip (truly illegal in XML)
                }
                c == '&'  -> out.append("&amp;")
                c == '<'  -> out.append("&lt;")
                c == '>'  -> out.append("&gt;")
                c == '"'  -> out.append("&quot;")
                c == '\'' -> out.append("&apos;")
                // Skip illegal XML 1.0 control characters
                (c.code in 0x00..0x08) || (c.code in 0x0B..0x0C) ||
                (c.code in 0x0E..0x1F) || c.code == 0x7F -> {}
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    private fun contentTypesXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
  <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
</Types>"""

    private fun relsXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

    private fun documentRelsXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    /**
     * DOCX styles.xml — Heading1 style definition
     */
    private fun stylesXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:style w:type="paragraph" w:styleId="Heading1">
    <w:name w:val="heading 1"/>
    <w:basedOn w:val="Normal"/>
    <w:next w:val="Normal"/>
    <w:pPr>
      <w:spacing w:before="240" w:after="120"/>
    </w:pPr>
    <w:rPr>
      <w:b/>
      <w:sz w:val="32"/>
      <w:szCs w:val="32"/>
    </w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="Heading2">
    <w:name w:val="heading 2"/>
    <w:basedOn w:val="Normal"/>
    <w:next w:val="Normal"/>
    <w:pPr>
      <w:spacing w:before="200" w:after="100"/>
    </w:pPr>
    <w:rPr>
      <w:b/>
      <w:sz w:val="28"/>
      <w:szCs w:val="28"/>
    </w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="Heading3">
    <w:name w:val="heading 3"/>
    <w:basedOn w:val="Normal"/>
    <w:next w:val="Normal"/>
    <w:pPr>
      <w:spacing w:before="160" w:after="80"/>
    </w:pPr>
    <w:rPr>
      <w:b/>
      <w:sz w:val="24"/>
      <w:szCs w:val="24"/>
    </w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:default="1" w:styleId="Normal">
    <w:name w:val="Normal"/>
    <w:rPr>
      <w:sz w:val="24"/>
      <w:szCs w:val="24"/>
    </w:rPr>
  </w:style>
</w:styles>"""

    private fun parseMarkdownLineToDocxRuns(line: String): String {
        val runs = StringBuilder()
        var i = 0
        while (i < line.length) {
            when {
                line.startsWith("**", i) -> {
                    val end = line.indexOf("**", i + 2)
                    if (end != -1 && end > i + 2) {
                        runs.append("<w:r><w:rPr><w:b/></w:rPr><w:t xml:space=\"preserve\">${escapeXml(line.substring(i + 2, end))}</w:t></w:r>")
                        i = end + 2
                    } else {
                        runs.append("<w:r><w:t xml:space=\"preserve\">*</w:t></w:r>")
                        i++
                    }
                }
                line.startsWith("*", i) && (i == 0 || line[i - 1].isWhitespace()) -> {
                    val end = line.indexOf("*", i + 1)
                    if (end != -1 && end > i + 1) {
                        runs.append("<w:r><w:rPr><w:i/></w:rPr><w:t xml:space=\"preserve\">${escapeXml(line.substring(i + 1, end))}</w:t></w:r>")
                        i = end + 1
                    } else {
                        runs.append("<w:r><w:t xml:space=\"preserve\">*</w:t></w:r>")
                        i++
                    }
                }
                else -> {
                    val nextBold = line.indexOf("**", i)
                    val nextItalic = line.indexOf("*", i)

                    val candidates = mutableListOf<Int>()
                    if (nextBold != -1) candidates.add(nextBold)
                    if (nextItalic != -1 && (nextItalic == 0 || line[nextItalic - 1].isWhitespace())) candidates.add(nextItalic)

                    val nextTokenIndex = if (candidates.isEmpty()) line.length else candidates.minOrNull()!!
                    
                    if (nextTokenIndex > i) {
                        runs.append("<w:r><w:t xml:space=\"preserve\">${escapeXml(line.substring(i, nextTokenIndex))}</w:t></w:r>")
                        i = nextTokenIndex
                    } else {
                        runs.append("<w:r><w:t xml:space=\"preserve\">${escapeXml(line[i].toString())}</w:t></w:r>")
                        i++
                    }
                }
            }
        }
        return runs.toString()
    }

    private fun documentXml(texts: Array<String>, pagePrefix: String): String {
        // Estimate initial capacity to avoid multiple buffer resizes
        val estimatedSize = texts.sumOf { it.length } * 2 + 500
        val sb = StringBuilder(estimatedSize)
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>
""")
        texts.forEachIndexed { index, text ->
            if (texts.size > 1) {
                sb.append("""    <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:rPr><w:b/></w:rPr><w:t>$pagePrefix ${index + 1}</w:t></w:r></w:p>
""")
            }
            text.lines().forEach { line ->
                if (line.trim().isEmpty()) {
                    sb.append("    <w:p/>\n")
                } else if (line.startsWith("### ")) {
                    sb.append("    <w:p><w:pPr><w:pStyle w:val=\"Heading3\"/></w:pPr>${parseMarkdownLineToDocxRuns(line.substring(4))}</w:p>\n")
                } else if (line.startsWith("## ")) {
                    sb.append("    <w:p><w:pPr><w:pStyle w:val=\"Heading2\"/></w:pPr>${parseMarkdownLineToDocxRuns(line.substring(3))}</w:p>\n")
                } else if (line.startsWith("# ")) {
                    sb.append("    <w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr>${parseMarkdownLineToDocxRuns(line.substring(2))}</w:p>\n")
                } else {
                    sb.append("    <w:p>${parseMarkdownLineToDocxRuns(line)}</w:p>\n")
                }
            }
            if (index < texts.size - 1) {
                sb.append("    <w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>\n")
            }
        }
        sb.append("""  </w:body>
</w:document>""")
        return sb.toString()
    }
}
