package com.example.ocr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class LlamaServerManager(private val context: Context) {
    companion object {
        private const val TAG = "LlamaServerManager"
        private const val SERVER_PORT = 8080
        const val SERVER_URL = "http://127.0.0.1:${SERVER_PORT}/completion"

        // ELF magic bytes — একটি বৈধ Linux/Android binary এভাবে শুরু হয়
        private val ELF_MAGIC = byteArrayOf(0x7F, 0x45, 0x4C, 0x46)
    }

    private var process: Process? = null

    sealed class StartResult {
        object Success : StartResult()
        object InvalidBinary : StartResult()
        data class ProcessCrashed(val output: String) : StartResult()
        data class Timeout(val output: String) : StartResult()
        data class Error(val message: String) : StartResult()
    }

    suspend fun extractAndStartServer(modelPath: String, mmprojPath: String): StartResult {
        return withContext(Dispatchers.IO) {
            val serverFile = File(context.filesDir, "llama-server")

            // Step 1: Extract the binary from assets
            try {
                val assetBytes = context.assets.open("llama-server").use { it.readBytes() }

                // Always re-extract if the existing file differs in size
                if (!serverFile.exists() || serverFile.length() != assetBytes.size.toLong()) {
                    serverFile.writeBytes(assetBytes)
                }

                // Step 2: ELF magic byte যাচাই — placeholder হলে সাথে সাথে ফিরে যাও
                val magic = serverFile.inputStream().use { it.readNBytes(4) }
                if (!magic.contentEquals(ELF_MAGIC)) {
                    val content = serverFile.readText().take(200)
                    Log.e(TAG, "llama-server is not a valid ELF binary. Content: $content")
                    return@withContext StartResult.InvalidBinary
                }

                serverFile.setExecutable(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract llama-server binary", e)
                return@withContext StartResult.Error("Binary extraction failed: ${e.message}")
            }

            // Step 3: Model ফাইল আছে কিনা যাচাই
            val modelFile = File(modelPath)
            val mmprojFile = File(mmprojPath)
            if (!modelFile.exists()) {
                return@withContext StartResult.Error("Model file not found: $modelPath")
            }
            if (!mmprojFile.exists()) {
                return@withContext StartResult.Error("Projector file not found: $mmprojPath")
            }

            // Step 4: পুরনো প্রসেস বন্ধ করো
            stopServer()

            // Step 5: নতুন প্রসেস শুরু করো
            val process: Process
            try {
                val cmd = listOf(
                    serverFile.absolutePath,
                    "-m", modelPath,
                    "--mmproj", mmprojPath,
                    "--port", SERVER_PORT.toString(),
                    "-c", "4096",
                    "--host", "127.0.0.1",
                    "-cb"
                )
                Log.i(TAG, "Starting: ${cmd.joinToString(" ")}")

                val pb = ProcessBuilder(cmd)
                pb.directory(context.filesDir)
                pb.redirectErrorStream(true)
                process = pb.start()
                this@LlamaServerManager.process = process
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start process", e)
                return@withContext StartResult.Error("Process start failed: ${e.message}")
            }

            // Step 6: প্রসেস জীবিত কিনা দেখো এবং server তৈরি হওয়ার অপেক্ষা করো
            return@withContext waitForServerReady(process)
        }
    }

    private suspend fun waitForServerReady(proc: Process): StartResult {
        val outputBuilder = StringBuilder()
        val outputThread = Thread {
            try {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    outputBuilder.appendLine(line)
                    Log.d(TAG, "llama-server: $line")
                }
            } catch (_: Exception) {}
        }
        outputThread.isDaemon = true
        outputThread.start()

        val maxWaitMs = 120_000L  // 2 মিনিট পর্যন্ত অপেক্ষা (বড় মডেল লোড হতে সময় লাগে)
        val checkIntervalMs = 1_000L
        var elapsed = 0L

        while (elapsed < maxWaitMs) {
            delay(checkIntervalMs)
            elapsed += checkIntervalMs

            // প্রসেস মরে গেছে কিনা দেখো
            if (!proc.isAlive) {
                val exitCode = proc.exitValue()
                val output = outputBuilder.toString().trim()
                Log.e(TAG, "llama-server exited with code $exitCode. Output:\n$output")
                return StartResult.ProcessCrashed("Exit code: $exitCode\n$output")
            }

            // Health endpoint চেক করো
            try {
                val conn = URL("http://127.0.0.1:${SERVER_PORT}/health")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 1500
                conn.readTimeout = 1500
                val code = conn.responseCode
                conn.disconnect()
                when (code) {
                    200 -> {
                        Log.i(TAG, "Server ready after ${elapsed}ms")
                        return StartResult.Success
                    }
                    503 -> { /* মডেল লোড হচ্ছে — অপেক্ষা করো */ }
                    else -> Log.d(TAG, "Health check returned $code")
                }
            } catch (_: Exception) {
                // সার্ভার এখনো শুরু হয়নি — চলতে থাকো
            }
        }

        val output = outputBuilder.toString().trim()
        Log.e(TAG, "Server timed out after ${maxWaitMs}ms. Output:\n$output")
        return StartResult.Timeout(output)
    }

    fun stopServer() {
        try {
            process?.destroy()
            process = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
    }

    fun isRunning(): Boolean = process?.isAlive == true
}
