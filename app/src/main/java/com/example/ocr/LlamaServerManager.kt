package com.example.ocr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class LlamaServerManager(private val context: Context) {
    companion object {
        private const val TAG = "LlamaServerManager"
        private const val SERVER_PORT = 8080
        const val SERVER_URL = "http://127.0.0.1:${SERVER_PORT}/completion"
    }

    private var process: Process? = null

    suspend fun extractAndStartServer(modelPath: String, mmprojPath: String): Boolean {
        return withContext(Dispatchers.IO) {
            val serverFile = File(context.filesDir, "llama-server")
            
            // Extract the binary from assets to internal storage so it can be executed
            try {
                if (!serverFile.exists() || serverFile.length() == 0L) {
                    context.assets.open("llama-server").use { input ->
                        serverFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                // Make it executable
                serverFile.setExecutable(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract llama-server binary", e)
                return@withContext false
            }

            // Start the process
            try {
                if (process != null) {
                    stopServer()
                }

                val cmd = mutableListOf(
                    serverFile.absolutePath,
                    "-m", modelPath,
                    "--mmproj", mmprojPath,
                    "--port", SERVER_PORT.toString(),
                    "-c", "4096", // Context size
                    "-cb" // continuous batching
                )

                val pb = ProcessBuilder(cmd)
                pb.directory(context.filesDir)
                // Redirect error stream to catch logs
                pb.redirectErrorStream(true)
                process = pb.start()

                // Wait for the server to be ready by pinging the endpoint or waiting for output
                return@withContext waitForServerReady()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start llama-server process", e)
                return@withContext false
            }
        }
    }

    private suspend fun waitForServerReady(): Boolean {
        val maxWaitSeconds = 60
        for (i in 0 until maxWaitSeconds) {
            delay(1000)
            try {
                val url = URL("http://127.0.0.1:${SERVER_PORT}/health")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                val responseCode = connection.responseCode
                connection.disconnect()
                when (responseCode) {
                    200 -> return true          // Server is fully ready
                    503 -> continue             // Server is loading the model — keep waiting
                    else -> continue            // Any other code — keep waiting
                }
            } catch (e: Exception) {
                // Server not up yet — keep waiting
            }
        }
        return false
    }

    fun stopServer() {
        try {
            process?.destroy()
            process = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
    }
}
