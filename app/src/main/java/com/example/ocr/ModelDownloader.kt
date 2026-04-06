package com.example.ocr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

class ModelDownloader(private val context: Context) {
    companion object {
        private const val TAG = "ModelDownloader"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 2000L
        private const val BUFFER_SIZE = 65536 // 64KB buffer for faster downloads
        private const val CONNECT_TIMEOUT = 15000
        private const val READ_TIMEOUT = 30000
    }

    // Actual HuggingFace links to PaddleOCR v3 ONNX mobile models
    private val modelUrls = listOf(
        "https://huggingface.co/monkt/paddleocr-onnx/resolve/main/ch_PP-OCRv3_det_infer.onnx", // Detection Model
        "https://huggingface.co/monkt/paddleocr-onnx/resolve/main/ch_PP-OCRv3_rec_infer.onnx", // Recognition Model
        "https://huggingface.co/monkt/paddleocr-onnx/resolve/main/ppocr_keys_v1.txt"           // Dictionary
    )

    // TODO: Add actual SHA-256 checksums if you want strict validation
    private val modelChecksums = mapOf(
        "ch_PP-OCRv3_det_infer.onnx" to "",
        "ch_PP-OCRv3_rec_infer.onnx" to "",
        "ppocr_keys_v1.txt" to ""
    )

    suspend fun checkAndDownloadModels(
        onProgress: (Int) -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            try {
                val modelDir = File(context.getExternalFilesDir(null), "models")
                if (!modelDir.exists()) modelDir.mkdirs()

                val totalFiles = modelUrls.size
                for (i in modelUrls.indices) {
                    coroutineContext.ensureActive() // Support cancellation

                    val urlStr = modelUrls[i]
                    val fileName = urlStr.substringAfterLast("/")
                    val file = File(modelDir, fileName)

                    if (!file.exists()) {
                        downloadFileWithRetry(urlStr, file, i, totalFiles, onProgress)
                    } else {
                        // File already exists, report progress
                        val progress = ((i + 1) * 100) / totalFiles
                        onProgress(progress)
                        Log.i(TAG, "Model file already exists: $fileName")
                    }
                }
                onComplete(true)
            } catch (e: CancellationException) {
                Log.i(TAG, "Download cancelled")
                throw e // Re-throw to properly cancel coroutine
            } catch (e: Exception) {
                Log.e(TAG, "Model download failed", e)
                onComplete(false)
            }
        }
    }

    private suspend fun downloadFileWithRetry(
        urlStr: String,
        targetFile: File,
        fileIndex: Int,
        totalFiles: Int,
        onProgress: (Int) -> Unit
    ) {
        var lastException: Exception? = null
        for (attempt in 1..MAX_RETRIES) {
            coroutineContext.ensureActive()
            try {
                downloadFile(urlStr, targetFile, fileIndex, totalFiles, onProgress)
                return // Success
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Download attempt $attempt/$MAX_RETRIES failed: ${e.message}")
                if (attempt < MAX_RETRIES) {
                    kotlinx.coroutines.delay(RETRY_DELAY_MS * attempt)
                }
            }
        }
        throw lastException ?: Exception("Download failed after $MAX_RETRIES attempts")
    }

    private suspend fun downloadFile(
        urlStr: String,
        targetFile: File,
        fileIndex: Int,
        totalFiles: Int,
        onProgress: (Int) -> Unit
    ) {
        val tempFile = File(targetFile.parent, "${targetFile.name}.tmp")
        var connection: HttpURLConnection? = null

        try {
            val url = URL(urlStr)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("Server returned HTTP ${connection.responseCode} ${connection.responseMessage}")
            }

            val fileLength = connection.contentLength.toLong()
            val fileProgressBase = (fileIndex * 100) / totalFiles
            val fileProgressMultiplier = 100 / totalFiles

            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    val data = ByteArray(BUFFER_SIZE)
                    var total: Long = 0
                    var count: Int
                    while (input.read(data).also { count = it } != -1) {
                        coroutineContext.ensureActive() // Check cancellation during download
                        total += count
                        if (fileLength > 0) {
                            val fileRelativeProgress = ((total * 100) / fileLength).toInt()
                            val overallProgress = fileProgressBase + (fileRelativeProgress * fileProgressMultiplier / 100)
                            onProgress(overallProgress.coerceAtMost(100))
                        } else {
                            // Unknown content length — show indeterminate-style progress
                            val estimatedProgress = fileProgressBase + (fileProgressMultiplier / 2)
                            onProgress(estimatedProgress.coerceAtMost(100))
                        }
                        output.write(data, 0, count)
                    }
                    output.flush()
                }
            }

            // TODO: Verify checksum if available
            val expectedChecksum = modelChecksums[targetFile.name]
            if (!expectedChecksum.isNullOrEmpty()) {
                // val actualChecksum = computeSha256(tempFile)
                // if (actualChecksum != expectedChecksum) {
                //     tempFile.delete()
                //     throw Exception("Checksum mismatch for ${targetFile.name}")
                // }
            }

            // Atomic rename: only replace target after successful download
            if (!tempFile.renameTo(targetFile)) {
                // renameTo can fail on some filesystems, fallback to copy
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            Log.i(TAG, "Downloaded: ${targetFile.name}")

        } catch (e: Exception) {
            tempFile.delete() // Clean up partial download
            throw e
        } finally {
            connection?.disconnect()
        }
    }
}
