package com.example.ocr

import android.content.Context
import android.content.pm.PackageManager
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

    /**
     * Device hardware & inference configuration report.
     * Shown to the user via the "Device Info" menu item.
     */
    data class DeviceReport(
        val totalCores: Int,
        val bigCores: Int,
        val maxFreqMhz: Long,
        val totalRamMb: Long,
        val availableRamMb: Long,
        val hasVulkan: Boolean,
        val gpuLayers: Int,
        val cpuThreads: Int,
        val contextSize: Int,
        val primaryAbi: String,
        val androidVersion: String,
        val deviceModel: String
    )

    /**
     * Collects all hardware and inferred inference parameters for display.
     */
    fun buildDeviceReport(): DeviceReport {
        val cpuCount = Runtime.getRuntime().availableProcessors()
        var bigCores = cpuCount / 2
        var maxFreqMhz = 0L
        try {
            val freqs = (0 until cpuCount).mapNotNull { i ->
                File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
                    .takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
            }
            if (freqs.isNotEmpty()) {
                val maxFreq = freqs.max()
                maxFreqMhz = maxFreq / 1000
                bigCores = freqs.count { it >= maxFreq * 0.9 }.coerceAtLeast(2)
            }
        } catch (_: Exception) {}

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val totalRamMb = memInfo.totalMem / (1024 * 1024)
        val availableRamMb = memInfo.availMem / (1024 * 1024)

        val hasVulkan = context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL) ||
                        context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)

        val gpuLayers = detectGpuLayers(totalRamMb)
        val cpuThreads = detectBigCoreCount()
        val contextSize = when {
            totalRamMb < 3072 -> 2048
            totalRamMb < 6144 -> 4096
            else              -> 8192
        }

        return DeviceReport(
            totalCores     = cpuCount,
            bigCores       = bigCores,
            maxFreqMhz     = maxFreqMhz,
            totalRamMb     = totalRamMb,
            availableRamMb = availableRamMb,
            hasVulkan      = hasVulkan,
            gpuLayers      = gpuLayers,
            cpuThreads     = cpuThreads,
            contextSize    = contextSize,
            primaryAbi     = primaryAbi,
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            deviceModel    = "${Build.MANUFACTURER} ${Build.MODEL}"
        )
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
     * Callback interface for server loading progress.
     * stageMessage: human-readable phase — "Loading main model (1/2)…" etc.
     */
    interface LoadingProgressListener {
        fun onLoadingProgress(elapsedSeconds: Int, maxSeconds: Int, stageMessage: String)
    }

    /**
     * Big core count detection — reads CPU max-frequency per core from sysfs.
     * On big.LITTLE chips (Snapdragon, Dimensity) only big cores are counted.
     * Using LITTLE cores for LLM inference is counter-productive — they're slow.
     */
    private fun detectBigCoreCount(): Int {
        return try {
            val cpuCount = Runtime.getRuntime().availableProcessors()
            val freqs = (0 until cpuCount).mapNotNull { i ->
                File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
                    .takeIf { it.exists() }
                    ?.readText()?.trim()?.toLongOrNull()
            }
            if (freqs.isEmpty()) {
                // Fallback: half of all cores, minimum 2
                return (cpuCount / 2).coerceAtLeast(2)
            }
            val maxFreq = freqs.max()
            // Count cores whose max freq is ≥ 90% of the highest — those are big cores
            val bigCount = freqs.count { it >= maxFreq * 0.9 }
            Log.i(TAG, "CPU: $cpuCount cores total, $bigCount big cores detected (max ${maxFreq / 1000}MHz)")
            bigCount.coerceAtLeast(2)
        } catch (e: Exception) {
            Log.w(TAG, "Big core detection failed, using fallback", e)
            (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2)
        }
    }

    /**
     * GPU layer count for Vulkan offload.
     * Returns 0 if Vulkan is unavailable — all computation stays on CPU.
     * PaddleOCR-VL-1.5 has 32 transformer layers total.
     * We offload conservatively: leave some layers on CPU for memory safety.
     */
    private fun detectGpuLayers(totalRamMb: Long): Int {
        val hasVulkan = context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL) ||
                        context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
        if (!hasVulkan) {
            Log.i(TAG, "GPU: Vulkan not available — CPU only")
            return 0
        }
        // Vulkan available: offload layers based on available RAM
        val layers = when {
            totalRamMb < 4096 -> 0   // < 4GB RAM — too risky, avoid GPU OOM
            totalRamMb < 6144 -> 10  // 4–6GB RAM — partial offload (10/32 layers)
            totalRamMb < 8192 -> 20  // 6–8GB RAM — moderate offload (20/32 layers)
            else              -> 28  // 8GB+ RAM  — near-full offload (28/32 layers)
        }
        Log.i(TAG, "GPU: Vulkan available, offloading $layers/32 layers (RAM: ${totalRamMb}MB)")
        return layers
    }

    /** Detect current loading phase by scanning llama-server stdout */
    private fun detectStage(output: String): String = when {
        // HTTP server is up — almost done
        output.contains("HTTP server listening") ||
        output.contains("srv  listen") ||
        output.contains("listening on") ->
            "Server starting… almost ready"

        // Projector / mmproj / vision encoder loading
        output.contains("clip_model_load") ||
        output.contains("mmproj") ||
        output.contains("vision model") ->
            "Loading vision model (2/2)…"

        // Main LLM tensor loading
        output.contains("llm_load_tensors") ||
        output.contains("llama_model_load") ||
        output.contains("load_model") ||
        output.contains(".gguf") ->
            "Loading main model (1/2)…"

        // Very early — process just started
        else -> "Initializing engine…"
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

            // Step 5: Device RAM ও CPU অনুযায়ী parameters নির্ধারণ করো
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            val totalRamMb = memInfo.totalMem / (1024 * 1024)

            val contextSize = when {
                totalRamMb < 3072 -> 2048
                totalRamMb < 6144 -> 4096
                else              -> 8192
            }
            val cpuThreads   = detectBigCoreCount()
            val gpuLayers    = detectGpuLayers(totalRamMb)

            // Step 6: নতুন প্রসেস শুরু করো
            val proc: Process
            try {
                val cmd = mutableListOf(
                    serverFile.absolutePath,
                    "-m",        modelPath,
                    "--mmproj",  mmprojPath,
                    "--port",    SERVER_PORT.toString(),
                    "--host",    "127.0.0.1",
                    "-c",        contextSize.toString(),
                    "-t",        cpuThreads.toString(),
                    "-tb",       Runtime.getRuntime().availableProcessors().toString(),
                    "-cb"
                )
                if (gpuLayers > 0) {
                    cmd += listOf("-ngl", gpuLayers.toString())
                }
                Log.i(TAG, "Starting llama-server: ABI=$primaryAbi, ctx=$contextSize, threads=$cpuThreads, ngl=$gpuLayers")

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

                // Report progress to UI every second with current stage
                if (elapsed - lastProgressReportMs >= 1000L) {
                    lastProgressReportMs = elapsed
                    val snapshot = synchronized(outputBuffer) { outputBuffer.toString() }
                    val stage = detectStage(snapshot)
                    progressListener?.onLoadingProgress(
                        elapsedSeconds = (elapsed / 1000).toInt(),
                        maxSeconds = (maxWaitMs / 1000).toInt(),
                        stageMessage = stage
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

    /**
     * Hybrid approach: check whether the server is already running and healthy.
     * Returns true only if the child process is alive AND /health returns {"status":"ok"}.
     * Called on app resume to decide whether to skip re-loading the model.
     */
    suspend fun isServerAlive(): Boolean = withContext(Dispatchers.IO) {
        // Process reference must exist and be alive
        if (getProcess()?.isAlive != true) return@withContext false
        var conn: HttpURLConnection? = null
        try {
            conn = URL("http://127.0.0.1:${SERVER_PORT}/health")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                body.contains("\"ok\"")
            } else {
                false
            }
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }
}
