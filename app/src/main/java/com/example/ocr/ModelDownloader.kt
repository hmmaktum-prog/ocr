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
        private const val CONNECT_TIMEOUT = 30000
        private const val READ_TIMEOUT = 60000

        const val BASE_REPO = "https://huggingface.co/PaddlePaddle/PaddleOCR-VL-1.5-GGUF/resolve/main"
        const val MAIN_MODEL_FILE = "PaddleOCR-VL-1.5.gguf"
        const val MMPROJ_FILE = "PaddleOCR-VL-1.5-mmproj.gguf"
    }

    // Both fast and accurate use the same model (no quantized versions available in the repo)
    private val modelUrls = listOf(
        "$BASE_REPO/$MAIN_MODEL_FILE",
        "$BASE_REPO/$MMPROJ_FILE"
    )

    suspend fun checkAndDownloadModels(
        fastMode: Boolean,
        onProgress: (Int) -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            try {
                val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
                val modelDir = File(baseDir, "models")
                if (!modelDir.exists()) modelDir.mkdirs()

                val urls = modelUrls
                val totalFiles = urls.size
                for (i in urls.indices) {
                    coroutineContext.ensureActive()

                    val urlStr = urls[i]
                    val fileName = urlStr.substringAfterLast("/")
                    val file = File(modelDir, fileName)

                    if (!file.exists() || file.length() == 0L) {
                        downloadFileWithRetry(urlStr, file, i, totalFiles, onProgress)
                    } else {
                        val progress = ((i + 1) * 100) / totalFiles
                        onProgress(progress)
                        Log.i(TAG, "Model file already exists: $fileName (${file.length()} bytes)")
                    }
                }
                onComplete(true)
            } catch (e: CancellationException) {
                Log.i(TAG, "Download cancelled")
                throw e
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
            var currentUrl = urlStr
            var redirectCount = 0
            val maxRedirects = 10

            // Manually follow redirects to handle cross-protocol (http→https) cases
            while (redirectCount <= maxRedirects) {
                val url = URL(currentUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = CONNECT_TIMEOUT
                connection.readTimeout = READ_TIMEOUT
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
                connection.connect()

                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw Exception("Redirect with no Location header")
                    connection.disconnect()
                    currentUrl = location
                    redirectCount++
                    continue
                }

                if (code != HttpURLConnection.HTTP_OK) {
                    throw Exception("Server returned HTTP $code ${connection.responseMessage}")
                }
                break
            }

            if (redirectCount > maxRedirects) {
                throw Exception("Too many redirects")
            }

            val fileLength = connection!!.contentLengthLong
            val fileProgressBase = (fileIndex * 100) / totalFiles
            val fileProgressMultiplier = 100 / totalFiles

            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    val data = ByteArray(BUFFER_SIZE)
                    var total: Long = 0
                    var count: Int
                    while (input.read(data).also { count = it } != -1) {
                        coroutineContext.ensureActive()
                        total += count
                        if (fileLength > 0) {
                            val fileRelativeProgress = ((total * 100) / fileLength).toInt()
                            val overallProgress = fileProgressBase + (fileRelativeProgress * fileProgressMultiplier / 100)
                            onProgress(overallProgress.coerceAtMost(100))
                        } else {
                            val estimatedProgress = fileProgressBase + (fileProgressMultiplier / 2)
                            onProgress(estimatedProgress.coerceAtMost(100))
                        }
                        output.write(data, 0, count)
                    }
                    output.flush()
                }
            }

            // Atomic rename: only replace target after successful download
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            Log.i(TAG, "Downloaded: ${targetFile.name} (${targetFile.length()} bytes)")

        } catch (e: Exception) {
            tempFile.delete()
            throw e
        } finally {
            connection?.disconnect()
        }
    }
}
