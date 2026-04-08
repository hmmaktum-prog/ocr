package com.example.ocr

import android.content.Context
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

class ModelDownloader(private val context: Context) {
    companion object {
        private const val TAG = "ModelDownloader"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 2000L
        private const val BUFFER_SIZE = 65536 // 64KB buffer
        private const val PROGRESS_UPDATE_INTERVAL_MS = 200L

        const val BASE_REPO = "https://huggingface.co/PaddlePaddle/PaddleOCR-VL-1.5-GGUF/resolve/main"
        const val MAIN_MODEL_FILE = "PaddleOCR-VL-1.5.gguf"
        const val MMPROJ_FILE = "PaddleOCR-VL-1.5-mmproj.gguf"
        
        private val downloadClient = HttpClientProvider.client
    }

    private val modelUrls = listOf(
        "$BASE_REPO/$MAIN_MODEL_FILE",
        "$BASE_REPO/$MMPROJ_FILE"
    )

    /**
     * onProgress: (overallPercent, currentFileName, fileIndex, totalFiles)
     * — UI can show "Downloading (1/2): PaddleOCR-VL-1.5.gguf ... 45%"
     */
    suspend fun checkAndDownloadModels(
        onProgress: (overallPercent: Int, fileName: String, fileIndex: Int, totalFiles: Int) -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            try {
                val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
                val modelDir = File(baseDir, "models")
                if (!modelDir.exists()) modelDir.mkdirs()

                // Storage space warning
                val stat = StatFs(modelDir.absolutePath)
                if (stat.availableBytes < 200 * 1024 * 1024) {
                    Log.w(TAG, "Low storage: ${stat.availableBytes / 1024 / 1024}MB available")
                }

                val urls = modelUrls
                val totalFiles = urls.size

                // Pre-check storage requirements and collect expected sizes per URL
                val expectedSizes = mutableMapOf<String, Long>()
                var totalRequiredSpace: Long = 0
                for (urlStr in urls) {
                    try {
                        val req = Request.Builder().url(urlStr).head().build()
                        downloadClient.newCall(req).execute().use { res ->
                            if (res.isSuccessful) {
                                // contentLength() returns -1 if server uses chunked encoding — treat as unknown (0)
                                val size = res.body?.contentLength()?.takeIf { it > 0 } ?: 0L
                                expectedSizes[urlStr] = size
                                totalRequiredSpace += size
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed HEAD request for size check", e)
                    }
                }

                if (totalRequiredSpace > 0) {
                    val storageStat = StatFs(modelDir.absolutePath)
                    val safetyMargin = 50 * 1024 * 1024L
                    if (storageStat.availableBytes < totalRequiredSpace + safetyMargin) {
                        val requiredMb = totalRequiredSpace / (1024 * 1024)
                        throw Exception(context.getString(R.string.error_insufficient_storage) + " (Needs ~${requiredMb}MB)")
                    }
                }

                // Sequential download — one file at a time
                for (i in urls.indices) {
                    coroutineContext.ensureActive()

                    val urlStr = urls[i]
                    val fileName = urlStr.substringAfterLast("/")
                    val file = File(modelDir, fileName)
                    val expectedSize = expectedSizes[urlStr] ?: 0L

                    val needsDownload = !file.exists() ||
                        file.length() == 0L ||
                        (expectedSize > 0L && file.length() != expectedSize)

                    if (needsDownload) {
                        val fileStat = StatFs(modelDir.absolutePath)
                        if (fileStat.availableBytes < 50 * 1024 * 1024) {
                            throw Exception(context.getString(R.string.error_insufficient_storage))
                        }
                        Log.i(TAG, "Starting download ${i + 1}/$totalFiles: $fileName")
                        downloadFileWithRetry(urlStr, file, i, totalFiles, fileName, onProgress)
                    } else {
                        val progress = ((i + 1) * 100) / totalFiles
                        onProgress(progress, fileName, i, totalFiles)
                        Log.i(TAG, "File ${i + 1}/$totalFiles already exists: $fileName (${file.length()} bytes)")
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
        displayName: String,
        onProgress: (overallPercent: Int, fileName: String, fileIndex: Int, totalFiles: Int) -> Unit
    ) {
        var lastException: Exception? = null
        for (attempt in 1..MAX_RETRIES) {
            coroutineContext.ensureActive()
            try {
                downloadFile(urlStr, targetFile, fileIndex, totalFiles, displayName, onProgress)
                if (targetFile.exists() && targetFile.length() > 0) {
                    return // Success
                } else {
                    throw Exception("Downloaded file is empty")
                }
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
        displayName: String,
        onProgress: (overallPercent: Int, fileName: String, fileIndex: Int, totalFiles: Int) -> Unit
    ) {
        val tempFile = File(targetFile.parent, "${targetFile.name}.tmp")

        // Fix USE-05: Download Resume Support
        val existingSize = if (tempFile.exists()) tempFile.length() else 0L

        val requestBuilder = Request.Builder()
            .url(urlStr)
            .header("User-Agent", "PaddleOCR-VL-App/1.0 (Android)")

        if (existingSize > 0) {
            requestBuilder.header("Range", "bytes=$existingSize-")
            Log.i(TAG, "Resuming download for ${targetFile.name} from byte $existingSize")
        }
        val request = requestBuilder.build()
            
        // Use a Call object so it can potentially be cancelled cleanly, 
        // though OkHttp's execute blocks, so coroutine cancellation will mostly occur between reads.
        val call = downloadClient.newCall(request)
        
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Server returned HTTP ${response.code}: ${response.message}")
                }
                
                val isPartial = response.code == 206
                val appendMode = isPartial
                val startIndex = if (isPartial) existingSize else 0L
                if (!isPartial && existingSize > 0) {
                    Log.w(TAG, "Server does not support Range requests. Starting over.")
                }
                
                val body = response.body ?: throw IOException("Empty response body")
                // contentLength() returns -1 if unknown (chunked encoding) — treat as 0 (skip exact-size check)
                val originalContentLength = body.contentLength().takeIf { it > 0 } ?: 0L
                // For partial (206) response: total file size = already downloaded + remaining content
                // For full (200) response: total file size = content length
                val fileLength = if (isPartial && originalContentLength > 0) startIndex + originalContentLength else originalContentLength
                
                val fileProgressBase = (fileIndex * 100) / totalFiles
                val fileProgressMultiplier = 100 / totalFiles
                var lastProgressTime = 0L

                body.byteStream().use { input ->
                    java.io.FileOutputStream(tempFile, appendMode).use { output ->
                        val data = ByteArray(BUFFER_SIZE)
                        var total: Long = startIndex
                        var count: Int
                        while (input.read(data).also { count = it } != -1) {
                            coroutineContext.ensureActive()
                            total += count
                            output.write(data, 0, count)

                            val now = System.currentTimeMillis()
                            if (now - lastProgressTime >= PROGRESS_UPDATE_INTERVAL_MS) {
                                lastProgressTime = now
                                val overallProgress = if (fileLength > 0) {
                                    val fileRelativeProgress = ((total * 100) / fileLength).toInt()
                                    fileProgressBase + (fileRelativeProgress * fileProgressMultiplier / 100)
                                } else {
                                    fileProgressBase + (fileProgressMultiplier / 2)
                                }
                                onProgress(overallProgress.coerceAtMost(100), displayName, fileIndex, totalFiles)
                            }
                        }
                        output.flush()
                    }
                }
                
                // Final size check
                if (fileLength > 0 && tempFile.length() != fileLength) {
                    throw IOException("Incomplete file: expected $fileLength bytes, got ${tempFile.length()}")
                }

                // Fix SEC-01 / SEC-05: Verify file integrity (Placeholder for SHA-256 validation)
                if (!verifyChecksum(tempFile)) {
                    throw SecurityException("Checksum verification failed for ${tempFile.name}")
                }

                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
                Log.i(TAG, "Downloaded: ${targetFile.name} (${targetFile.length()} bytes)")
            }
        } catch (e: Exception) {
            if (e is SecurityException) {
                tempFile.delete()
            }
            throw e
        }
    }

    private fun verifyChecksum(file: File): Boolean {
        // Compute SHA-256 Hash of the file and compare against a known source of truth.
        val knownHashes = mapOf<String, String>(
            // Add actual SHA-256 hashes here to enforce security:
            // MAIN_MODEL_FILE to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            // MMPROJ_FILE to "..."
        )
        
        val expectedHash = knownHashes[file.name]
        if (expectedHash.isNullOrEmpty()) {
            Log.w(TAG, "No checksum available to verify ${file.name}, proceeding without verification.")
            return true // Proceed conditionally if no hash is specified
        }

        Log.i(TAG, "Verifying checksum for ${file.name}...")
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(65536)
                var read: Int
                while (stream.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            val calculatedHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (calculatedHash.equals(expectedHash, ignoreCase = true)) {
                Log.i(TAG, "Checksum matched successfully for ${file.name}")
                true
            } else {
                Log.e(TAG, "Checksum MISMATCH for ${file.name}! Expected: $expectedHash, Got: $calculatedHash")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during checksum verification", e)
            false
        }
    }
}
