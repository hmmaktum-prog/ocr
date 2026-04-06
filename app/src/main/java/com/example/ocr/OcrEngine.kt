package com.example.ocr

import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OcrEngine {
    companion object {
        private const val TAG = "OcrEngine"
        init {
            System.loadLibrary("ocr_engine")
        }
    }

    @Volatile
    private var enginePtr: Long = 0

    private external fun initModelNative(modelDir: String): Long
    private external fun processImageNative(enginePtr: Long, bitmap: Bitmap): String
    private external fun releaseNative(enginePtr: Long)

    @Synchronized
    fun initModel(modelDir: String): Boolean {
        if (enginePtr != 0L) {
            releaseNative(enginePtr)
            enginePtr = 0L
        }
        enginePtr = initModelNative(modelDir)
        return enginePtr != 0L
    }

    @Synchronized
    fun processImage(bitmap: Bitmap): Result<String> {
        if (enginePtr == 0L) {
            return Result.failure(IllegalStateException("OCR Engine not initialized"))
        }
        return try {
            val text = processImageNative(enginePtr, bitmap)
            Result.success(text)
        } catch (e: Exception) {
            Log.e(TAG, "processImage failed", e)
            Result.failure(e)
        }
    }

    @Synchronized
    fun release() {
        if (enginePtr != 0L) {
            releaseNative(enginePtr)
            enginePtr = 0L
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
