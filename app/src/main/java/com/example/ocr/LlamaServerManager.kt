package com.example.ocr

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.os.Build
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
        private const val COPY_BUFFER_SIZE = 65536 // 64KB chunks — never loads whole file

        // ELF magic bytes — একটি বৈধ Linux/Android binary এভাবে শুরু হয়
        private val ELF_MAGIC = byteArrayOf(0x7F, 0x45, 0x4C, 0x46)

        private val SUPPORTED_ABIS: Array<String> get() = Build.SUPPORTED_ABIS
        private val isArm64Supported: Boolean get() = SUPPORTED_ABIS.contains("arm64-v8a")
        private val primaryAbi: String get() = SUPPORTED_ABIS.firstOrNull() ?: "unknown"
    }

    private var process: Process? = null

    private fun Process.isAliveCompat(): Boolean = try {
        exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

    sealed class StartResult {
        object Success : StartResult()
        object InvalidBinary : StartResult()
        data class UnsupportedAbi(val deviceAbi: String) : StartResult()
        data class ProcessCrashed(val output: String) : StartResult()
        data class Timeout(val output: String) : StartResult()
        data class Error(val message: String) : StartResult()
    }

    suspend fun extractAndStartServer(modelPath: String, mmprojPath: String): StartResult {
        return withContext(Dispatchers.IO) {

            // Step 0: ABI পরীক্ষা করো
            Log.i(TAG, "Device supported ABIs: ${SUPPORTED_ABIS.joinToString()}")
            if (!isArm64Supported) {
                Log.e(TAG, "Device does not support arm64-v8a. Primary ABI: $primaryAbi")
                return@withContext StartResult.UnsupportedAbi(primaryAbi)
            }

            val serverFile = File(context.filesDir, "llama-server")

            // Step 1: Assets থেকে binary extract করো (streaming — OOM হবে না)
            try {
                // Asset-এর আকার জানো — পুরো ফাইল memory-তে লোড না করেই
                val assetSize: Long = try {
                    context.assets.openFd("llama-server").use { fd: AssetFileDescriptor ->
                        fd.length
                    }
                } catch (e: Exception) {
                    // openFd কাজ না করলে (compressed asset) fallback: সরাসরি copy করো
                    -1L
                }

                val needsExtract = !serverFile.exists() ||
                    (assetSize > 0 && serverFile.length() != assetSize)

                if (needsExtract) {
                    Log.i(TAG, "Extracting llama-server binary (streaming, no OOM)...")
                    val tmpFile = File(context.filesDir, "llama-server.tmp")

                    // Stream করে copy — কখনো পুরো ফাইল RAM-এ যায় না
                    context.assets.open("llama-server").use { input ->
                        tmpFile.outputStream().use { output ->
                            input.copyTo(output, bufferSize = COPY_BUFFER_SIZE)
                        }
                    }

                    // Atomic rename: সম্পূর্ণ না হলে replace হবে না
                    if (!tmpFile.renameTo(serverFile)) {
                        tmpFile.copyTo(serverFile, overwrite = true)
                        tmpFile.delete()
                    }

                    Log.i(TAG, "Extracted: ${serverFile.length() / 1024 / 1024}MB")
                } else {
                    Log.i(TAG, "llama-server already extracted (${serverFile.length() / 1024 / 1024}MB), skipping")
                }

                // Step 2: ELF magic byte যাচাই — শুধু প্রথম 4 byte পড়ি
                val magic = serverFile.inputStream().use { stream ->
                    val buf = ByteArray(4)
                    var offset = 0
                    while (offset < 4) {
                        val n = stream.read(buf, offset, 4 - offset)
                        if (n == -1) break
                        offset += n
                    }
                    buf
                }
                if (!magic.contentEquals(ELF_MAGIC)) {
                    val content = serverFile.readText().take(200)
                    Log.e(TAG, "llama-server is not a valid ELF binary. Content: $content")
                    return@withContext StartResult.InvalidBinary
                }

                Log.i(TAG, "llama-server ELF verified (${serverFile.length() / 1024 / 1024}MB)")
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
                Log.i(TAG, "Starting llama-server on arm64 (device ABI: $primaryAbi)")
                Log.d(TAG, "Command: ${cmd.joinToString(" ")}")

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

        val maxWaitMs = 120_000L
        val checkIntervalMs = 1_000L
        var elapsed = 0L

        while (elapsed < maxWaitMs) {
            delay(checkIntervalMs)
            elapsed += checkIntervalMs

            if (!proc.isAliveCompat()) {
                val exitCode = proc.exitValue()
                val output = outputBuilder.toString().trim()
                Log.e(TAG, "llama-server exited with code $exitCode. Output:\n$output")
                return StartResult.ProcessCrashed("Exit code: $exitCode\n$output")
            }

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

    fun isRunning(): Boolean = process?.isAliveCompat() == true
}
