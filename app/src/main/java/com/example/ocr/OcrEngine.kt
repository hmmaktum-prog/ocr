package com.example.ocr

import android.graphics.Bitmap
import android.util.Log
import android.util.Base64
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

    private fun downscaleForOcr(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDimension && height <= maxDimension) return bitmap

        val scale = maxDimension.toFloat() / maxOf(width, height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    suspend fun processImage(bitmap: Bitmap): Result<OcrResult> {
        var resized: Bitmap? = null
        return try {
            resized = downscaleForOcr(bitmap, 1536)
            val outputStream = ByteArrayOutputStream()
            val base64Out = android.util.Base64OutputStream(outputStream, Base64.NO_WRAP)
            resized.compress(Bitmap.CompressFormat.JPEG, 90, base64Out)
            base64Out.close()
            val base64Image = outputStream.toString("UTF-8")

            val jsonBody = JSONObject().apply {
                put("prompt", "Analyze the image and transcribe all the text found inside it:\n[img-1]")
                val imagesArray = JSONArray().apply {
                    put(JSONObject().apply {
                        put("data", base64Image)
                        put("id", 1)
                    })
                }
                put("image_data", imagesArray)
                put("n_predict", 4096)    // বড় পেজে বেশি token দরকার (ছিল 1024)
                put("temperature", 0.1)
                put("stream", false)
            }

            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(LlamaServerManager.SERVER_URL)
                .post(requestBody)
                .build()

            var responseBody = ""
            var attempt = 0
            while (attempt <= 2) {
                try {
                    HttpClientProvider.ocrClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            if (response.code in listOf(429, 503) && attempt < 2) {
                                attempt++
                                kotlinx.coroutines.delay(1000L * attempt)
                                return@use
                            }
                            throw IOException("Server returned HTTP ${response.code}: ${response.message}")
                        }
                        // Cap response body at 5MB — large OCR pages can produce long text
                        responseBody = response.body?.source()?.readUtf8(5_000_000) ?: ""
                    }
                    if (responseBody.isNotEmpty()) break
                    attempt++
                    if (attempt > 2) throw IOException("Server returned empty response after 3 attempts")
                    kotlinx.coroutines.delay(1000L * attempt)
                } catch (e: Exception) {
                    // Fix LOGIC-14: retry on SocketTimeoutException too (transient failure)
                    val isRetryable = attempt < 2 && (
                        e is java.net.SocketTimeoutException ||
                        e.message?.contains("503") == true ||
                        e.message?.contains("429") == true
                    )
                    if (isRetryable) {
                        attempt++
                        kotlinx.coroutines.delay(1000L * attempt)
                    } else throw e
                }
            }

            val jsonResponse: JSONObject
            try {
                jsonResponse = JSONObject(responseBody)
            } catch (e: JSONException) {
                throw IOException("Invalid server response format: ${responseBody.take(100)}")
            }

                // Server error হলে "error" field থাকে — silent empty response এড়াতে চেক করো
                if (jsonResponse.has("error")) {
                    val errMsg = jsonResponse.optString("error", "Unknown server error")
                    throw IOException("llama-server error: $errMsg")
                }

                val content = jsonResponse.optString("content", "")
                if (content.isEmpty()) {
                    Log.w(TAG, "Server returned empty content")
                }

                // Extract tokens/second from llama-server timings
                val tokPerSec = jsonResponse.optJSONObject("timings")
                    ?.optDouble("predicted_per_second")
                    ?.takeIf { it.isFinite() && it > 0.0 }
                Log.d(TAG, "OCR complete: ${content.length} chars, tok/s=${tokPerSec ?: "N/A"}")

                Result.success(OcrResult(text = content.trim(), tokensPerSecond = tokPerSec))
        } catch (e: Exception) {
            Log.e(TAG, "processImage failed", e)
            Result.failure(e)
        } finally {
            if (resized != null && resized != bitmap) {
                resized.recycle()
            }
        }
    }

    fun processImageStreaming(bitmap: Bitmap, maxDimension: Int = 1536): Flow<StreamToken> = flow {
        val startMs = System.currentTimeMillis()
        emit(StreamToken.Thinking(0))
        var resized: Bitmap? = null
        try {
            resized = downscaleForOcr(bitmap, maxDimension)
            val outputStream = ByteArrayOutputStream()
            val base64Out = android.util.Base64OutputStream(outputStream, Base64.NO_WRAP)
            resized.compress(Bitmap.CompressFormat.JPEG, 90, base64Out)
            base64Out.close()
            val base64Image = outputStream.toString("UTF-8")

            val jsonBody = JSONObject().apply {
                put("prompt", "Analyze the image and transcribe all the text found inside it:\n[img-1]")
                val imagesArray = JSONArray().apply {
                    put(JSONObject().apply {
                        put("data", base64Image)
                        put("id", 1)
                    })
                }
                put("image_data", imagesArray)
                put("n_predict", 4096)
                put("temperature", 0.1)
                put("stream", true)
            }

            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(LlamaServerManager.SERVER_URL)
                .post(requestBody)
                .addHeader("Accept", "text/event-stream")
                .build()

            HttpClientProvider.ocrClient.newCall(request).execute().use { response ->
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
                            val content = json.optString("content", "")
                            if (content.isNotEmpty()) {
                                fullText.append(content)
                                emit(StreamToken.Text(content))
                            }
                            if (json.optBoolean("stop", false)) {
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
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Streaming OCR failed", e)
            emit(StreamToken.Error(e.message ?: "Unknown error"))
        } finally {
            if (resized != null && resized != bitmap) {
                resized.recycle()
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

    private fun escapeXml(text: String): String {
        val out = StringBuilder(text.length)
        // Fix LOGIC-15: Use index-based loop to handle surrogate pairs (emoji etc.)
        // Surrogate pairs (U+D800–U+DFFF) are illegal in XML 1.0 — skip them
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                // Skip high+low surrogate pairs entirely
                c.isHighSurrogate() -> {
                    if (i + 1 < text.length && text[i + 1].isLowSurrogate()) i++ // skip low too
                    // Do not append — surrogate codepoints are illegal in XML 1.0
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
  <w:style w:type="paragraph" w:default="1" w:styleId="Normal">
    <w:name w:val="Normal"/>
    <w:rPr>
      <w:sz w:val="24"/>
      <w:szCs w:val="24"/>
    </w:rPr>
  </w:style>
</w:styles>"""

    private fun documentXml(texts: Array<String>, pagePrefix: String): String {
        // Estimate initial capacity to avoid multiple buffer resizes
        val estimatedSize = texts.sumOf { it.length } * 2 + 500
        val sb = StringBuilder(estimatedSize)
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>
""")
        texts.forEachIndexed { index, text ->
            sb.append("""    <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:rPr><w:b/></w:rPr><w:t>$pagePrefix ${index + 1}</w:t></w:r></w:p>
""")
            text.lines().forEach { line ->
                sb.append("    <w:p><w:r><w:t xml:space=\"preserve\">${escapeXml(line)}</w:t></w:r></w:p>\n")
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
