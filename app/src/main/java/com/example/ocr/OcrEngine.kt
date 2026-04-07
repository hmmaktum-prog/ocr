package com.example.ocr

import android.graphics.Bitmap
import android.util.Log
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OcrEngine {
    companion object {
        private const val TAG = "OcrEngine"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun processImage(bitmap: Bitmap): Result<String> {
        return try {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

            val jsonBody = JSONObject().apply {
                // Formatting for llama.cpp multimodal
                put("prompt", "Analyze the image and transcribe all the text found inside it:\n[img-1]")
                val imagesArray = JSONArray().apply {
                    put(JSONObject().apply {
                        put("data", base64Image)
                        put("id", 1)
                    })
                }
                put("image_data", imagesArray)
                put("n_predict", 1024)
                put("temperature", 0.1)
                put("stream", false)
            }

            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(LlamaServerManager.SERVER_URL)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Unexpected code $response")
                }
                val responseBody = response.body?.string() ?: ""
                val jsonResponse = JSONObject(responseBody)
                val content = jsonResponse.optString("content", "")
                Result.success(content.trim())
            }
        } catch (e: Exception) {
            Log.e(TAG, "processImage failed over HTTP", e)
            Result.failure(e)
        }
    }

    fun generateDocx(texts: Array<String>, outputPath: String): Boolean {
        return try {
            val file = File(outputPath)
            // Generate a proper OOXML .docx file (minimal valid ZIP structure)
            FileOutputStream(file).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    // [Content_Types].xml
                    zos.putNextEntry(ZipEntry("[Content_Types].xml"))
                    zos.write(contentTypesXml().toByteArray())
                    zos.closeEntry()

                    // _rels/.rels
                    zos.putNextEntry(ZipEntry("_rels/.rels"))
                    zos.write(relsXml().toByteArray())
                    zos.closeEntry()

                    // word/_rels/document.xml.rels
                    zos.putNextEntry(ZipEntry("word/_rels/document.xml.rels"))
                    zos.write(documentRelsXml().toByteArray())
                    zos.closeEntry()

                    // word/document.xml
                    zos.putNextEntry(ZipEntry("word/document.xml"))
                    zos.write(documentXml(texts).toByteArray())
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
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun contentTypesXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""

    private fun relsXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

    private fun documentRelsXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
</Relationships>"""

    private fun documentXml(texts: Array<String>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>
""")
        texts.forEachIndexed { index, text ->
            // Page header
            sb.append("""    <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:rPr><w:b/></w:rPr><w:t>Page ${index + 1}</w:t></w:r></w:p>
""")
            // Split text by lines and add each as a paragraph
            text.lines().forEach { line ->
                sb.append("    <w:p><w:r><w:t xml:space=\"preserve\">${escapeXml(line)}</w:t></w:r></w:p>\n")
            }
            // Page break (except after last page)
            if (index < texts.size - 1) {
                sb.append("    <w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>\n")
            }
        }
        sb.append("""  </w:body>
</w:document>""")
        return sb.toString()
    }
}
