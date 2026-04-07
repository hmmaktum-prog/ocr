package com.example.ocr

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class LlamaServerManager(private val context: Context) {
    companion object {
        private const val TAG = "LlamaServerManager"
        private const val SERVER_PORT = 8080
        const val SERVER_URL = "http://127.0.0.1:${SERVER_PORT}/completion"
        // ELF magic bytes — একটি বৈধ Linux/Android binary এভাবে শুরু হয়
        private val ELF_MAGIC = byteArrayOf(0x7F, 0x45, 0x4C, 0x46)

        private val SUPPORTED_ABIS: Array<String> get() = Build.SUPPORTED_ABIS
        private val isArm64Supported: Boolean get() = SUPPORTED_ABIS.contains("arm64-v8a")
        private val primaryAbi: String get() = SUPPORTED_ABIS.firstOrNull() ?: "unknown"

        // Output buffer limit — prevent unbounded memory growth
        private const val MAX_OUTPUT_SIZE = 10 * 1024 // 10KB
    }

    private var process: Process? = null

    // Synchronized access to process field for thread safety
    @Synchronized
    private fun setProcess(proc: Process?) {
        process = proc
    }

    @Synchronized
    private fun getProcess(): Process? = process

    sealed class StartResult {
        object Success : StartResult()
        object InvalidBinary : StartResult()
        data class UnsupportedAbi(val deviceAbi: String) : StartResult()
        data class ProcessCrashed(val output: String) : StartResult()
        data class Timeout(val output: String) : StartResult()
        data class Error(val message: String) : StartResult()
    }

    /**
     * Callback interface for server loading progress
     */
    interface LoadingProgressListener {
        fun onLoadingProgress(elapsedSeconds: Int, maxSeconds: Int)
    }

    // Fix UI-04: @Volatile ensures write on Main thread is visible from IO thread
    @Volatile
    private var progressListener: LoadingProgressListener? = null

    fun setLoadingProgressListener(listener: LoadingProgressListener?) {
        progressListener = listener
    }

    suspend fun extractAndStartServer(modelPath: String, mmprojPath: String): StartResult {
        return withContext(Dispatchers.IO) {

            // Step 0: ABI পরীক্ষা করো
            Log.i(TAG, "Device supported ABIs: ${SUPPORTED_ABIS.joinToString()}")
            if (!isArm64Supported) {
                Log.e(TAG, "Device does not support arm64-v8a. Primary ABI: $primaryAbi")
                return@withContext StartResult.UnsupportedAbi(primaryAbi)
            }

            // nativeLibraryDir থেকে binary নাও — Android নিজেই এখানে install করে, noexec সমস্যা নেই
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val serverFile = File(nativeLibDir, "libllama_server.so")

            Log.i(TAG, "Looking for llama-server at: ${serverFile.absolutePath}")

            // Step 1: Binary আছে কিনা যাচাই করো
            if (!serverFile.exists()) {
                Log.e(TAG, "libllama_server.so not found in nativeLibraryDir: $nativeLibDir")
                return@withContext StartResult.Error("Server binary not found: ${serverFile.absolutePath}")
            }

            Log.i(TAG, "Found: libllama_server.so (${serverFile.length() / 1024 / 1024}MB)")

            // Step 2: ELF magic byte যাচাই — শুধু প্রথম 4 byte পড়ি
            try {
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
                    val preview = serverFile.inputStream().use { stream ->
                        val buf = ByteArray(200)
                        val read = stream.read(buf)
                        if (read > 0) String(buf, 0, read, Charsets.UTF_8) else "(empty)"
                    }
                    Log.e(TAG, "libllama_server.so is not a valid ELF binary. Content: $preview")
                    return@withContext StartResult.InvalidBinary
                }
                Log.i(TAG, "llama-server ELF verified (${serverFile.length() / 1024 / 1024}MB)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to verify llama-server binary", e)
                return@withContext StartResult.Error("Binary verification failed: ${e.message}")
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

            // Step 5: Device RAM অনুযায়ী context size নির্ধারণ করো
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            val totalRamMb = memInfo.totalMem / (1024 * 1024)
            val contextSize = when {
                totalRamMb < 3072 -> 2048
                totalRamMb < 6144 -> 4096
                else -> 8192
            }

            // Step 6: নতুন প্রসেস শুরু করো
            val proc: Process
            try {
                val cmd = listOf(
                    serverFile.absolutePath,
                    "-m", modelPath,
                    "--mmproj", mmprojPath,
                    "--port", SERVER_PORT.toString(),
                    "-c", contextSize.toString(),
                    "--host", "127.0.0.1",
                    "-cb"
                )
                Log.i(TAG, "Starting llama-server on arm64 (device ABI: $primaryAbi, ctx: $contextSize)")

                val pb = ProcessBuilder(cmd)
                pb.directory(context.filesDir)
                pb.redirectErrorStream(true)
                proc = pb.start()
                setProcess(proc)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start process", e)
                return@withContext StartResult.Error("Process start failed: ${e.message}")
            }

            // Step 7: প্রসেস জীবিত কিনা দেখো এবং server তৈরি হওয়ার অপেক্ষা করো
            return@withContext waitForServerReady(proc)
        }
    }

    private suspend fun waitForServerReady(proc: Process): StartResult {
        // Thread-safe output buffer with size limit
        val outputBuffer = StringBuffer()
        val outputThread = Thread {
            try {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(outputBuffer) {
                        if (outputBuffer.length < MAX_OUTPUT_SIZE) {
                            outputBuffer.appendLine(line)
                        }
                    }
                    Log.d(TAG, "llama-server: $line")
                }
            } catch (_: Exception) {}
        }
        outputThread.isDaemon = true
        outputThread.start()

        val maxWaitMs = 300_000L   // 5 minutes — large model on emulator needs more time
        val checkIntervalMs = 500L  // Check every 500ms for faster crash detection
        var elapsed = 0L
        var lastProgressReportMs = 0L

        try {
            while (elapsed < maxWaitMs) {
                delay(checkIntervalMs)
                elapsed += checkIntervalMs

                // Report progress to UI every second
                if (elapsed - lastProgressReportMs >= 1000L) {
                    lastProgressReportMs = elapsed
                    progressListener?.onLoadingProgress(
                        elapsedSeconds = (elapsed / 1000).toInt(),
                        maxSeconds = (maxWaitMs / 1000).toInt()
                    )
                }

                if (!proc.isAlive) {
                    val exitCode = proc.exitValue()
                    // Fix LOGIC-06: synchronized read to avoid race with outputThread
                    val output = synchronized(outputBuffer) { outputBuffer.toString().trim() }
                    Log.e(TAG, "llama-server exited with code $exitCode. Output:\n$output")
                    return StartResult.ProcessCrashed("Exit code: $exitCode\n$output")
                }

                // /health endpoint: "ok" = ready, "loading model" = still loading
                var serverReady = false
                var conn: HttpURLConnection? = null
                try {
                    conn = URL("http://127.0.0.1:${SERVER_PORT}/health")
                        .openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 1000
                    conn.readTimeout = 1000
                    val code = conn.responseCode
                    if (code == 200) {
                        val body = conn.inputStream.bufferedReader().readText()
                        if (body.contains("\"ok\"")) {
                            serverReady = true
                        } else {
                            Log.d(TAG, "Health check: server still loading (body=$body)")
                        }
                    } else {
                        Log.d(TAG, "Health check returned HTTP $code (still loading)")
                    }
                } catch (_: Exception) {
                    // Server not yet accepting connections — keep waiting
                } finally {
                    conn?.disconnect()
                }
                if (serverReady) {
                    Log.i(TAG, "Server ready after ${elapsed}ms")
                    return StartResult.Success
                }
            }
        } catch (e: Exception) {
            // Coroutine cancelled or other exception — cleanup
            Log.w(TAG, "waitForServerReady interrupted", e)
            cleanupProcess(proc)
            throw e
        }

        // Timeout — process kill করো
        // Fix LOGIC-06: synchronized read to avoid race with outputThread
        val output = synchronized(outputBuffer) { outputBuffer.toString().trim() }
        Log.e(TAG, "Server timed out after ${maxWaitMs}ms. Output:\n$output")
        cleanupProcess(proc)
        return StartResult.Timeout(output)
    }

    /**
     * Graceful then forceful process cleanup
     */
    private fun cleanupProcess(proc: Process) {
        try {
            proc.destroy()
            // Wait up to 5 seconds for graceful shutdown
            if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                Log.w(TAG, "Process force-killed after timeout")
            }
            // Close streams to unblock output thread
            try { proc.inputStream.close() } catch (_: Exception) {}
            try { proc.outputStream.close() } catch (_: Exception) {}
            try { proc.errorStream.close() } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.e(TAG, "Error during process cleanup", e)
        }
        setProcess(null)
    }

    fun stopServer() {
        val proc = getProcess() ?: return
        cleanupProcess(proc)
    }
    // Fix LOGIC-08: isRunning() was dead code — removed
}
