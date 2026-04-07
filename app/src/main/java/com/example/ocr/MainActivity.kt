package com.example.ocr

import android.app.ActivityManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.example.ocr.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.Context
import java.io.File
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private val SUPPORTED_IMAGE_TYPES = listOf("image/png", "image/jpeg", "image/jpg", "image/webp")
        private val SUPPORTED_MIME_TYPES = arrayOf("application/pdf", "image/png", "image/jpeg", "image/webp")
        private const val MAX_IMAGE_PIXELS = 50_000_000L
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var downloader: ModelDownloader
    private val ocrEngine = OcrEngine()
    private lateinit var llamaServerManager: LlamaServerManager

    private var processingJob: Job? = null
    private var engineLoadingJob: Job? = null
    // Fix LOGIC-03: Track download job to support back-press cancellation
    private var downloadJob: Job? = null
    private var lastOutputFile: File? = null
    // Fix USE-07: Store last extracted text for preview
    private var lastExtractedText: String = ""
    private var isEngineReady: Boolean = false

    private val selectFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { handleFileSelected(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        downloader = ModelDownloader(this)
        llamaServerManager = LlamaServerManager(this)

        setupBackPressHandler()
        setupButtons()
        checkStartupState()
    }

    private fun checkStartupState() {
        binding.progressBar.visibility = View.GONE
        if (modelsAlreadyDownloaded()) {
            binding.statusText.text = getString(R.string.status_models_found)
            binding.btnDownload.text = getString(R.string.btn_initialize_engine)
        } else {
            binding.statusText.text = getString(R.string.status_initializing)
            
            // Fix USE-11: First-run onboarding screen
            val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
            if (!prefs.getBoolean("onboarding_shown", false)) {
                showOnboardingDialog()
                prefs.edit().putBoolean("onboarding_shown", true).apply()
            }
        }
    }

    private fun showOnboardingDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.onboarding_title))
            .setMessage(getString(R.string.onboarding_message))
            .setPositiveButton(android.R.string.ok, null)
            .setCancelable(false)
            .show()
    }

    private fun setupButtons() {
        binding.btnDownload.setOnClickListener { startModelDownload() }
        binding.btnSelectFile.setOnClickListener { selectFileLauncher.launch(SUPPORTED_MIME_TYPES) }
        binding.btnCancel.setOnClickListener { showCancelDialog() }
        binding.btnShare.setOnClickListener { lastOutputFile?.let { shareFile(it) } }
        // Fix USE-07: In-app text preview button
        binding.btnPreview.setOnClickListener { showTextPreview() }
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    processingJob?.isActive == true -> showCancelDialog()
                    engineLoadingJob?.isActive == true -> showEngineCancelDialog()
                    // Fix LOGIC-03: handle back press during download
                    downloadJob?.isActive == true -> showDownloadCancelDialog()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    // Fix LOGIC-18 / UI-03: Explicitly handle config changes to prevent layout resets while
    // maintaining the running coroutines.
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // No specific UI adjustments needed for this layout on rotation,
        // but overriding prevents the activity from being recreated and
        // killing the active Job/LifecycleScope tasks.
    }

    private fun showEngineCancelDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_cancel_engine_title))
            .setMessage(getString(R.string.dialog_cancel_engine_message))
            .setPositiveButton(getString(R.string.dialog_yes)) { _, _ ->
                engineLoadingJob?.cancel()
                llamaServerManager.stopServer()
                binding.progressBar.visibility = View.GONE
                binding.btnDownload.isEnabled = true
                binding.statusText.text = getString(R.string.status_cancelled)
            }
            .setNegativeButton(getString(R.string.dialog_no), null)
            .show()
    }

    // Fix LOGIC-03: New dialog for cancelling model download
    private fun showDownloadCancelDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_cancel_download_title))
            .setMessage(getString(R.string.dialog_cancel_download_message))
            .setPositiveButton(getString(R.string.dialog_yes)) { _, _ ->
                downloadJob?.cancel()
                downloadJob = null
                binding.progressBar.visibility = View.GONE
                binding.btnDownload.isEnabled = true
                binding.statusText.text = getString(R.string.status_cancelled)
            }
            .setNegativeButton(getString(R.string.dialog_no), null)
            .show()
    }

    private fun startModelDownload() {
        if (modelsAlreadyDownloaded()) {
            initEngine()
            return
        }

        // Fix USE-06: Network type check warning
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val isWifi = cm.activeNetwork?.let { 
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) 
        } ?: false

        val msgParams = if (isWifi) {
            getString(R.string.dialog_download_confirm_message)
        } else {
            getString(R.string.dialog_download_confirm_message) + "\n\n" + getString(R.string.warning_mobile_data)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_download_confirm_title))
            .setMessage(msgParams)
            .setPositiveButton(getString(R.string.dialog_download_start)) { _, _ ->
                performModelDownload()
            }
            .setNegativeButton(getString(R.string.dialog_no), null)
            .show()
    }

    private fun performModelDownload() {
        binding.statusText.text = getString(R.string.status_checking_models)
        setProgressIndeterminate(true)
        binding.btnDownload.isEnabled = false

        // Fix LOGIC-03: Store job reference for back-press cancellation
        downloadJob = lifecycleScope.launch {
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
                        downloadJob = null
                        if (success) {
                            binding.statusText.text = getString(R.string.status_models_ready)
                            initEngine()
                        } else {
                            binding.progressBar.visibility = View.GONE
                            binding.btnDownload.isEnabled = true
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
            .setPositiveButton(getString(R.string.dialog_retry)) { _, _ -> performModelDownload() }
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

    private fun getModelsDir(): File {
        val baseDir = getExternalFilesDir(null) ?: filesDir
        return File(baseDir, "models")
    }

    private fun modelsAlreadyDownloaded(): Boolean {
        val modelDir = getModelsDir()
        val mainModel = File(modelDir, ModelDownloader.MAIN_MODEL_FILE)
        val mmproj = File(modelDir, ModelDownloader.MMPROJ_FILE)
        return mainModel.exists() && mainModel.length() > 0L &&
               mmproj.exists() && mmproj.length() > 0L
    }

    private fun initEngine() {
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true
        binding.statusText.text = getString(R.string.status_engine_starting)
        binding.btnDownload.isEnabled = false

        llamaServerManager.setLoadingProgressListener(
            object : LlamaServerManager.LoadingProgressListener {
                override fun onLoadingProgress(elapsedSeconds: Int) {
                    runOnUiThread {
                        binding.statusText.text = getString(R.string.status_engine_loading, elapsedSeconds)
                    }
                }
            }
        )

        engineLoadingJob = lifecycleScope.launch(Dispatchers.IO) {
            val modelDir = getModelsDir()
            val modelPath = File(modelDir, ModelDownloader.MAIN_MODEL_FILE).absolutePath
            val mmprojPath = File(modelDir, ModelDownloader.MMPROJ_FILE).absolutePath

            val result = llamaServerManager.extractAndStartServer(modelPath, mmprojPath)

            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE
                llamaServerManager.setLoadingProgressListener(null)
                when (result) {
                    is LlamaServerManager.StartResult.Success -> {
                        isEngineReady = true
                        binding.statusText.text = getString(R.string.status_engine_ready)
                        binding.btnSelectFile.isEnabled = true
                        binding.btnDownload.visibility = View.GONE
                    }
                    is LlamaServerManager.StartResult.InvalidBinary -> {
                        binding.statusText.text = getString(R.string.status_engine_failed)
                        showEngineErrorDialog(
                            getString(R.string.dialog_binary_missing_title),
                            getString(R.string.dialog_binary_missing_message)
                        )
                    }
                    is LlamaServerManager.StartResult.UnsupportedAbi -> {
                        binding.statusText.text = getString(R.string.status_engine_failed)
                        showEngineErrorDialog(
                            getString(R.string.dialog_unsupported_abi_title),
                            getString(R.string.dialog_unsupported_abi_message, result.deviceAbi)
                        )
                    }
                    is LlamaServerManager.StartResult.ProcessCrashed -> {
                        val detail = result.output.take(300).ifEmpty { getString(R.string.error_no_output) }
                        binding.statusText.text = getString(R.string.status_engine_failed)
                        showEngineRetryDialog(getString(R.string.dialog_engine_crash_message, detail))
                    }
                    is LlamaServerManager.StartResult.Timeout -> {
                        binding.statusText.text = getString(R.string.status_engine_failed)
                        showEngineRetryDialog(getString(R.string.dialog_engine_timeout_message))
                    }
                    is LlamaServerManager.StartResult.Error -> {
                        binding.statusText.text = getString(R.string.status_engine_failed)
                        showEngineRetryDialog(result.message)
                    }
                }
            }
        }
    }

    private fun showEngineRetryDialog(detail: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_engine_failed_title))
            .setMessage(detail)
            .setPositiveButton(getString(R.string.dialog_retry)) { _, _ -> initEngine() }
            .setNegativeButton(getString(R.string.dialog_no)) { _, _ ->
                binding.btnDownload.isEnabled = true
            }
            .setCancelable(false)
            .show()
    }

    private fun showEngineErrorDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                binding.btnDownload.isEnabled = true
            }
            .setCancelable(false)
            .show()
    }

    private fun handleFileSelected(uri: Uri) {
        val fileName = getFileName(uri)
        val mimeType = contentResolver.getType(uri)

        val isPdf = fileName.lowercase().endsWith(".pdf") || mimeType == "application/pdf"
        val isImage = SUPPORTED_IMAGE_TYPES.any { mimeType == it } ||
                fileName.lowercase().let { it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".webp") }

        if (!isPdf && !isImage) {
            Toast.makeText(this, getString(R.string.error_unsupported_file), Toast.LENGTH_LONG).show()
            return
        }

        binding.fileInfoText.apply {
            text = getString(R.string.file_info, fileName)
            visibility = View.VISIBLE
        }

        processFile(uri, fileName, isPdf)
    }

    private fun processFile(uri: Uri, fileName: String, isPdf: Boolean) {
        processingJob?.cancel()
        setProcessingState(true)

        processingJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val extractedTexts = mutableListOf<String>()
                val failedPages = mutableListOf<Int>()

                if (isPdf) {
                    processPdf(uri, extractedTexts, failedPages)
                } else {
                    processImage(uri, extractedTexts)
                }

                if (extractedTexts.isEmpty() || extractedTexts.all { it.isBlank() }) {
                    withContext(Dispatchers.Main) {
                        setProcessingState(false)
                        binding.statusText.text = getString(R.string.status_no_output)
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    binding.statusText.text = getString(R.string.status_generating_docx)
                }

                val sanitizedName = sanitizeFileName(fileName.substringBeforeLast("."))
                val baseDir = getExternalFilesDir(null) ?: filesDir
                val outDir = File(baseDir, "output").apply { if (!exists()) mkdirs() }
                val outFile = generateUniqueFile(outDir, sanitizedName)

                val result = ocrEngine.generateDocx(extractedTexts.toTypedArray(), outFile.absolutePath, getString(R.string.page_prefix))

                withContext(Dispatchers.Main) {
                    setProcessingState(false)
                    if (result) {
                        lastOutputFile = outFile
                        // Fix USE-07: Store text and make preview button visible
                        lastExtractedText = extractedTexts.joinToString("\n\n")
                        binding.btnPreview.visibility = View.VISIBLE
                        
                        // Fix USE-13: Show absolute path instead of just name
                        val statusMsg = getString(R.string.status_saved_success, outFile.absolutePath)
                        binding.statusText.text = if (failedPages.isNotEmpty()) {
                            statusMsg + "\n" + getString(
                                R.string.status_ocr_partial_fail,
                                failedPages.size,
                                failedPages.joinToString(", ")
                            )
                        } else statusMsg
                        binding.btnShare.visibility = View.VISIBLE
                    } else {
                        binding.statusText.text = getString(R.string.status_saved_failed)
                    }
                }

            } catch (e: CancellationException) {
                Log.i(TAG, "Processing cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Processing failed", e)
                withContext(Dispatchers.Main) {
                    setProcessingState(false)
                    binding.statusText.text = getString(R.string.status_error, getUserFriendlyError(e))
                }
            }
        }
    }

    private fun generateUniqueFile(dir: File, baseName: String): File {
        var file = File(dir, "$baseName.docx")
        var counter = 1
        while (file.exists()) {
            file = File(dir, "${baseName}_$counter.docx")
            counter++
        }
        return file
    }

    private suspend fun processPdf(uri: Uri, extractedTexts: MutableList<String>, failedPages: MutableList<Int>) {
        val fd = contentResolver.openFileDescriptor(uri, "r")
            ?: throw Exception("Cannot open file")

        val renderer: PdfRenderer
        try {
            renderer = PdfRenderer(fd)
        } catch (e: SecurityException) {
            fd.close()
            throw Exception(getString(R.string.error_pdf_password_protected))
        }

        try {
            val pageCount = renderer.pageCount
            if (pageCount == 0) throw Exception(getString(R.string.error_pdf_empty))

            // Fix LOGIC-02: Open page 0 only once to get both dimensions
            val (defaultWidth, defaultHeight) = renderer.openPage(0).use { page ->
                Pair(page.width, page.height)
            }

            // Fix PERF-01: Query available memory once — outside the loop
            val availableMemMb = getAvailableMemoryMb()
            val scale = calculateSafeScale(defaultWidth, defaultHeight, availableMemMb)

            for (i in 0 until pageCount) {
                currentCoroutineContext().ensureActive()

                withContext(Dispatchers.Main) {
                    binding.progressBar.apply {
                        isIndeterminate = false
                        max = pageCount
                        progress = i + 1
                    }
                    binding.statusText.text = getString(R.string.status_processing_pdf, i + 1, pageCount)
                }

                renderer.openPage(i).use { page ->
                    val pageScale = if (page.width != defaultWidth) {
                        calculateSafeScale(page.width, page.height, availableMemMb)
                    } else scale

                    val bitmap = Bitmap.createBitmap(
                        (page.width * pageScale).toInt(),
                        (page.height * pageScale).toInt(),
                        Bitmap.Config.ARGB_8888
                    )
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                    val result = ocrEngine.processImage(bitmap)
                    result.onSuccess { text -> extractedTexts.add(text) }
                    result.onFailure { e ->
                        Log.w(TAG, "OCR failed on page ${i + 1}", e)
                        failedPages.add(i + 1)
                        // Fix LOGIC-16: placeholder instead of empty string
                        extractedTexts.add(getString(R.string.ocr_page_failed_placeholder, i + 1))
                    }
                    bitmap.recycle()
                }
            }
        } finally {
            renderer.close()
        }
    }

    private suspend fun processImage(uri: Uri, extractedTexts: MutableList<String>) {
        withContext(Dispatchers.Main) {
            setProgressIndeterminate(true)
            binding.statusText.text = getString(R.string.status_processing_image)
        }

        val dimensions = getImageDimensions(uri)
        if (dimensions != null) {
            val (width, height) = dimensions
            val pixelCount = width.toLong() * height
            if (pixelCount > MAX_IMAGE_PIXELS) {
                throw Exception(getString(R.string.error_image_too_large, "${width}x${height}", MAX_IMAGE_PIXELS / 1000000))
            }
        }

        contentResolver.openInputStream(uri)?.use { inputStream ->
            var bitmap = BitmapFactory.decodeStream(inputStream)
                ?: throw Exception(getString(R.string.error_decode_failed))

            try {
                try {
                    contentResolver.openInputStream(uri)?.use { exifStream ->
                        bitmap = correctBitmapRotation(bitmap, exifStream)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "EXIF rotation correction failed, using original", e)
                }

                val result = ocrEngine.processImage(bitmap)
                result.onSuccess { text -> extractedTexts.add(text) }
                result.onFailure { e -> throw e }
            } finally {
                // Fix LOGIC-01: Always recycle bitmap — even when OCR fails
                bitmap.recycle()
            }
        } ?: throw Exception("Cannot open input stream")
    }

    private fun getImageDimensions(uri: Uri): Pair<Int, Int>? {
        return try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, options)
                if (options.outWidth > 0 && options.outHeight > 0)
                    Pair(options.outWidth, options.outHeight)
                else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get image dimensions", e)
            null
        }
    }

    private fun correctBitmapRotation(bitmap: Bitmap, inputStream: InputStream): Bitmap {
        val exif = ExifInterface(inputStream)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )
        val rotationDegrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90  -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = Matrix().apply { postRotate(rotationDegrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }

    // Fix PERF-01: Separate function — called ONCE before the PDF loop
    private fun getAvailableMemoryMb(): Long {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.availMem / (1024 * 1024)
    }

    // Fix LOGIC-04: Long arithmetic prevents potential overflow
    // Fix PERF-01: accepts pre-computed availableMemMb instead of calling getSystemService
    private fun calculateSafeScale(pageWidth: Int, pageHeight: Int, availableMemMb: Long): Float {
        val desiredScale = when {
            availableMemMb < 128 -> 0.75f
            availableMemMb < 256 -> 1.0f
            availableMemMb < 512 -> 1.5f
            else -> 2.0f
        }
        val maxPixels = 50L * 1024L * 1024L / 4L  // Fix LOGIC-04: Long literals
        val maxScale = Math.sqrt(maxPixels.toDouble() / (pageWidth.toLong() * pageHeight)).toFloat()
        return minOf(desiredScale, maxScale).coerceAtLeast(0.5f)
    }

    // Fix UI-01: Only hide btnShare when processing STARTS, not when it ends
    // Fix UI-02: Reset fileInfoText visibility on processing start
    private fun setProcessingState(processing: Boolean) {
        binding.progressBar.visibility = if (processing) View.VISIBLE else View.GONE
        
        // Fix USE-08: Explicit texts for disabled states
        binding.btnSelectFile.isEnabled = !processing && isEngineReady
        if (processing) {
            binding.btnSelectFile.text = getString(R.string.status_initializing)
        } else if (!isEngineReady) {
            binding.btnSelectFile.text = getString(R.string.btn_select_file_disabled)
        } else {
            binding.btnSelectFile.text = getString(R.string.btn_select_file)
        }
        
        binding.btnCancel.visibility = if (processing) View.VISIBLE else View.GONE
        if (processing) {
            binding.btnShare.visibility = View.GONE
            binding.btnPreview.visibility = View.GONE
            binding.fileInfoText.visibility = View.GONE
        }
    }

    private fun showTextPreview() {
        val scrollView = android.widget.ScrollView(this)
        val textView = android.widget.TextView(this).apply {
            text = lastExtractedText
            setPadding(48, 48, 48, 48)
            setTextIsSelectable(true)
        }
        scrollView.addView(textView)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.preview_title))
            .setView(scrollView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
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
                this, "${applicationContext.packageName}.fileprovider", file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_document_chooser)))
        } catch (e: Exception) {
            Log.w(TAG, "Share failed", e)
            Toast.makeText(this, getString(R.string.error_share_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^\\p{L}\\p{N}._\\-\\s]"), "_")
            .replace(Regex("\\s+"), "_")
            .take(100)
            .ifEmpty { "document" }
    }

    // Fix LOGIC-05: Use generic localized message instead of raw exception text
    private fun getUserFriendlyError(e: Exception): String {
        return when {
            e is java.net.UnknownHostException -> getString(R.string.error_network)
            e is java.net.SocketTimeoutException -> getString(R.string.error_network)
            e.message?.contains("decode", ignoreCase = true) == true ->
                getString(R.string.error_decode_failed)
            e.message?.contains("password", ignoreCase = true) == true ->
                getString(R.string.error_pdf_password_protected)
            e.message?.contains("too large", ignoreCase = true) == true ->
                e.message ?: getString(R.string.error_image_too_large, "unknown", MAX_IMAGE_PIXELS / 1000000)
            e.message?.contains("storage", ignoreCase = true) == true ->
                getString(R.string.error_insufficient_storage)
            // Fix LOGIC-05: don't expose raw internal exception messages
            else -> getString(R.string.error_unknown)
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
            try {
                result = uri.path?.let { Uri.decode(it) }
            } catch (e: Exception) {
                result = uri.path
            }
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) result = result?.substring(cut + 1)
        }
        return result ?: "document"
    }

    override fun onDestroy() {
        super.onDestroy()
        processingJob?.cancel()
        engineLoadingJob?.cancel()
        downloadJob?.cancel()  // Fix LOGIC-03: cancel download on destroy
        if (isFinishing) {
            llamaServerManager.stopServer()
        }
    }
}
