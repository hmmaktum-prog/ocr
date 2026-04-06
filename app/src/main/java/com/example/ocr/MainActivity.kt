package com.example.ocr

import android.app.ActivityManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.ocr.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private val SUPPORTED_IMAGE_TYPES = listOf("image/png", "image/jpeg", "image/jpg", "image/webp")
        private val SUPPORTED_MIME_TYPES = listOf("application/pdf") + SUPPORTED_IMAGE_TYPES
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var downloader: ModelDownloader
    private val ocrEngine = OcrEngine()

    private var processingJob: Job? = null
    private var lastOutputFile: File? = null

    // File picker with MIME type filter
    private val selectFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handleFileSelected(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        downloader = ModelDownloader(this)

        setupBackPressHandler()
        setupButtons()
        startModelDownload()
    }

    private fun setupButtons() {
        binding.btnSelectFile.setOnClickListener {
            // Launch file picker with filtered MIME types
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, SUPPORTED_MIME_TYPES.toTypedArray())
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            selectFileLauncher.launch("*/*")
        }

        binding.btnCancel.setOnClickListener {
            showCancelDialog()
        }

        binding.btnShare.setOnClickListener {
            lastOutputFile?.let { shareFile(it) }
        }
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (processingJob?.isActive == true) {
                    showCancelDialog()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun startModelDownload() {
        binding.statusText.text = getString(R.string.status_checking_models)
        setProgressIndeterminate(true)

        lifecycleScope.launch {
            downloader.checkAndDownloadModels(
                onProgress = { progress ->
                    runOnUiThread {
                        setProgressIndeterminate(false)
                        binding.progressBar.progress = progress
                        binding.statusText.text = getString(R.string.status_downloading_model, progress)
                    }
                },
                onComplete = { success ->
                    runOnUiThread {
                        if (success) {
                            binding.statusText.text = getString(R.string.status_models_ready)
                            initEngine()
                        } else {
                            binding.progressBar.visibility = View.GONE
                            showRetryDownloadDialog()
                        }
                    }
                }
            )
        }
    }

    private fun showRetryDownloadDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_retry_download_title))
            .setMessage(getString(R.string.dialog_retry_download_message))
            .setPositiveButton(getString(R.string.dialog_retry)) { _, _ ->
                startModelDownload()
            }
            .setNegativeButton(getString(R.string.dialog_no)) { _, _ ->
                binding.statusText.text = getString(R.string.status_models_failed)
            }
            .setCancelable(false)
            .show()
    }

    private fun showCancelDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_cancel_title))
            .setMessage(getString(R.string.dialog_cancel_message))
            .setPositiveButton(getString(R.string.dialog_yes)) { _, _ ->
                processingJob?.cancel()
                resetToReadyState()
                binding.statusText.text = getString(R.string.status_cancelled)
            }
            .setNegativeButton(getString(R.string.dialog_no), null)
            .show()
    }

    private fun initEngine() {
        lifecycleScope.launch(Dispatchers.IO) {
            val modelDir = File(getExternalFilesDir(null), "models").absolutePath
            val success = ocrEngine.initModel(modelDir)
            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE
                if (success) {
                    binding.statusText.text = getString(R.string.status_engine_ready)
                    binding.btnSelectFile.isEnabled = true
                } else {
                    binding.statusText.text = getString(R.string.status_engine_failed)
                }
            }
        }
    }

    private fun handleFileSelected(uri: Uri) {
        val fileName = getFileName(uri)
        val mimeType = contentResolver.getType(uri)

        // Validate file type
        val isPdf = fileName.lowercase().endsWith(".pdf") || mimeType == "application/pdf"
        val isImage = SUPPORTED_IMAGE_TYPES.any { mimeType == it } ||
                fileName.lowercase().let { it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".webp") }

        if (!isPdf && !isImage) {
            Toast.makeText(this, getString(R.string.error_unsupported_file), Toast.LENGTH_LONG).show()
            return
        }

        // Show file info
        binding.fileInfoText.apply {
            text = getString(R.string.file_info, fileName)
            visibility = View.VISIBLE
        }

        processFile(uri, fileName, isPdf)
    }

    private fun processFile(uri: Uri, fileName: String, isPdf: Boolean) {
        setProcessingState(true)

        processingJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val extractedTexts = mutableListOf<String>()

                if (isPdf) {
                    processPdf(uri, extractedTexts)
                } else {
                    processImage(uri, extractedTexts)
                }

                withContext(Dispatchers.Main) {
                    binding.statusText.text = getString(R.string.status_generating_docx)
                }

                val sanitizedName = sanitizeFileName(fileName.substringBeforeLast("."))
                val outDir = getExternalFilesDir(null)
                val outFile = File(outDir, "$sanitizedName.docx")

                val result = ocrEngine.generateDocx(extractedTexts.toTypedArray(), outFile.absolutePath)

                withContext(Dispatchers.Main) {
                    setProcessingState(false)
                    if (result) {
                        lastOutputFile = outFile
                        binding.statusText.text = getString(R.string.status_saved_success, outFile.absolutePath)
                        binding.btnShare.visibility = View.VISIBLE
                    } else {
                        binding.statusText.text = getString(R.string.status_saved_failed)
                    }
                }

            } catch (e: CancellationException) {
                Log.i(TAG, "Processing cancelled")
                // State already reset by cancel handler
            } catch (e: Exception) {
                Log.e(TAG, "Processing failed", e)
                withContext(Dispatchers.Main) {
                    setProcessingState(false)
                    binding.statusText.text = getString(R.string.status_error, getUserFriendlyError(e))
                }
            }
        }
    }

    private suspend fun processPdf(uri: Uri, extractedTexts: MutableList<String>) {
        val fd = contentResolver.openFileDescriptor(uri, "r")
            ?: throw Exception("Cannot open file")

        // PdfRenderer takes ownership of the file descriptor — do NOT use fd.use{}
        val renderer = PdfRenderer(fd)
        try {
            val pageCount = renderer.pageCount

            for (i in 0 until pageCount) {
                kotlinx.coroutines.ensureActive() // Support cancellation

                withContext(Dispatchers.Main) {
                    binding.progressBar.apply {
                        isIndeterminate = false
                        max = pageCount
                        progress = i + 1
                    }
                    binding.statusText.text = getString(R.string.status_processing_pdf, i + 1, pageCount)
                }

                renderer.openPage(i).use { page ->
                    val scale = calculateSafeScale(page.width, page.height)
                    val bitmap = Bitmap.createBitmap(
                        (page.width * scale).toInt(),
                        (page.height * scale).toInt(),
                        Bitmap.Config.ARGB_8888
                    )

                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    val result = ocrEngine.processImage(bitmap)
                    result.onSuccess { text -> extractedTexts.add(text) }
                    result.onFailure { e -> Log.w(TAG, "OCR failed on page ${i + 1}", e) }

                    bitmap.recycle()
                }
            }
        } finally {
            renderer.close() // This also closes the fd
        }
    }

    private suspend fun processImage(uri: Uri, extractedTexts: MutableList<String>) {
        withContext(Dispatchers.Main) {
            setProgressIndeterminate(true)
            binding.statusText.text = getString(R.string.status_processing_image)
        }

        contentResolver.openInputStream(uri)?.use { inputStream ->
            val bitmap = BitmapFactory.decodeStream(inputStream)
                ?: throw Exception(getString(R.string.error_decode_failed))

            val result = ocrEngine.processImage(bitmap)
            result.onSuccess { text -> extractedTexts.add(text) }
            result.onFailure { e -> throw e }

            bitmap.recycle()
        } ?: throw Exception("Cannot open input stream")
    }

    private fun calculateSafeScale(pageWidth: Int, pageHeight: Int): Float {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        // Each pixel = 4 bytes (ARGB_8888)
        val availableMb = memInfo.availMem / (1024 * 1024)
        return when {
            availableMb < 128 -> 0.75f
            availableMb < 256 -> 1.0f
            availableMb < 512 -> 1.5f
            else -> 2.0f
        }
    }

    private fun setProcessingState(processing: Boolean) {
        binding.progressBar.visibility = if (processing) View.VISIBLE else View.GONE
        binding.btnSelectFile.isEnabled = !processing
        binding.btnCancel.visibility = if (processing) View.VISIBLE else View.GONE
        binding.btnShare.visibility = View.GONE
    }

    private fun resetToReadyState() {
        setProcessingState(false)
        binding.statusText.text = getString(R.string.status_engine_ready)
    }

    private fun setProgressIndeterminate(indeterminate: Boolean) {
        binding.progressBar.apply {
            visibility = View.VISIBLE
            isIndeterminate = indeterminate
        }
    }

    private fun shareFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share Document"))
        } catch (e: Exception) {
            Log.w(TAG, "Share failed", e)
            Toast.makeText(this, "Unable to share file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._\\-\\s]"), "_")
            .replace(Regex("\\s+"), "_")
            .take(100) // Limit length
    }

    private fun getUserFriendlyError(e: Exception): String {
        return when {
            e is java.net.UnknownHostException -> getString(R.string.error_network)
            e is java.net.SocketTimeoutException -> getString(R.string.error_network)
            e.message?.contains("decode", ignoreCase = true) == true ->
                getString(R.string.error_decode_failed)
            else -> e.message ?: "Unknown error"
        }
    }

    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index != -1) result = cursor.getString(index)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get filename from content URI", e)
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) result = result?.substring(cut + 1)
        }
        return result ?: "document"
    }

    override fun onDestroy() {
        super.onDestroy()
        processingJob?.cancel()
        ocrEngine.release()
    }
}
