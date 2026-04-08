package com.example.ocr

import android.Manifest
import android.animation.ObjectAnimator
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.ocr.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
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
    private lateinit var chatAdapter: ChatAdapter

    private var processingJob: Job? = null
    private var engineLoadingJob: Job? = null
    private var downloadJob: Job? = null
    private var ramUpdateJob: Job? = null
    private var isEngineReady: Boolean = false

    private val saveDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        )
    ) { uri ->
        uri ?: return@registerForActivityResult
        val src = File(getExternalFilesDir(null), "temp_export.docx")
        if (!src.exists()) {
            Toast.makeText(this, getString(R.string.error_save_failed), Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                }
                withContext(Dispatchers.Main) {
                    com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.status_saved_to_device), com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { 
                    com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.error_save_failed), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show() 
                }
            }
        }
    }

    private val selectFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { handleFileSelected(it) } }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        binding.topAppBar.inflateMenu(R.menu.menu_main)

        downloader = ModelDownloader(this)
        llamaServerManager = LlamaServerManager(this)

        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val isDark = prefs.getBoolean("dark_mode", false)
        AppCompatDelegate.setDefaultNightMode(
            if (isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )

        chatAdapter = ChatAdapter(this,
            onExportClicked = { msg ->
                val baseDir = getExternalFilesDir(null) ?: filesDir
                val outFile = File(baseDir, "temp_export.docx")
                val exportTexts = if (msg.pagesContent.isNotEmpty()) msg.pagesContent.toTypedArray() else arrayOf(msg.streamedText)
                val success = ocrEngine.generateDocx(exportTexts, outFile.absolutePath, "Page")
                if (success) {
                    saveDocumentLauncher.launch((msg.fileName ?: "document").substringBeforeLast(".") + ".docx")
                } else {
                    com.google.android.material.snackbar.Snackbar.make(binding.root, getString(R.string.status_saved_failed), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
                }
            },
            onImageClicked = { path ->
                showImagePreview(path)
            },
            onShareClicked = { msg ->
                shareText(msg.streamedText)
            }
        )
        val layoutManager = LinearLayoutManager(this)
        layoutManager.stackFromEnd = true
        binding.chatRecyclerView.layoutManager = layoutManager
        binding.chatRecyclerView.adapter = chatAdapter

        binding.qualityChip.text = getString(R.string.mode_fast)

        binding.topAppBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_toggle_theme -> {
                    val currentlyDark = AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES
                    val newMode = if (currentlyDark) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
                    AppCompatDelegate.setDefaultNightMode(newMode)
                    getSharedPreferences("app_prefs", MODE_PRIVATE).edit().putBoolean("dark_mode", !currentlyDark).apply()
                    true
                }
                R.id.action_clear -> {
                    if (chatAdapter.itemCount > 0) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(getString(R.string.dialog_clear_title))
                            .setMessage(getString(R.string.dialog_clear_message))
                            .setPositiveButton(getString(R.string.dialog_yes)) { _, _ ->
                                chatAdapter.clearMessages()
                                binding.emptyStateLayout.visibility = View.VISIBLE
                                com.google.android.material.snackbar.Snackbar.make(binding.root, "Chat cleared", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
                                
                                lifecycleScope.launch(Dispatchers.IO) {
                                    cacheDir.listFiles()?.forEach { if (it.name.startsWith("thumb_")) it.delete() }
                                }
                            }
                            .setNegativeButton(getString(R.string.dialog_no), null)
                            .show()
                    }
                    true
                }
                R.id.action_device_info -> {
                    showDeviceInfoDialog()
                    true
                }
                R.id.action_benchmark -> {
                    runBenchmark()
                    true
                }
                R.id.action_battery_saver -> {
                    val newMode = !llamaServerManager.batterySaverMode
                    llamaServerManager.batterySaverMode = newMode
                    item.isChecked = newMode
                    val msg = if (newMode) getString(R.string.battery_saver_on) else getString(R.string.battery_saver_off)
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                    
                    if (isEngineReady) {
                        llamaServerManager.stopServer()
                        isEngineReady = false
                        initEngine()
                    }
                    true
                }
                else -> false
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setupButtons()
        setupBackPressHandler()
        checkStartupState()
    }

    private fun shareText(text: String) {
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, text)
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, null)
        startActivity(shareIntent)
    }

    private fun showImagePreview(imagePath: String) {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_image_preview)
        val imageView = dialog.findViewById<android.widget.ImageView>(R.id.fullScreenImageView)
        val btnClose = dialog.findViewById<android.widget.ImageButton>(R.id.btnClosePreview)

        lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = BitmapFactory.decodeFile(imagePath)
            withContext(Dispatchers.Main) {
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                }
            }
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        imageView.setOnClickListener { dialog.dismiss() } // Tap to close
        dialog.show()
    }

    private fun startProcessingService() {
        val intent = Intent(this, OcrProcessingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopProcessingService() {
        stopService(Intent(this, OcrProcessingService::class.java))
    }

    private fun setMainState(subtitle: String, loading: Boolean) {
        binding.topAppBar.subtitle = subtitle
        binding.mainProgressIndicator.visibility = if (loading) View.VISIBLE else View.GONE
        if (!loading && isEngineReady) {
            binding.addFileButton.isEnabled = true
            binding.chatInputHint.text = getString(R.string.hint_select_file)
        }
    }

    private fun checkStartupState() {
        if (modelsAlreadyDownloaded()) {
            binding.emptyStateLayout.visibility = View.VISIBLE
            binding.downloadModelButton.visibility = View.GONE
            binding.startEngineButton.visibility = View.VISIBLE
            binding.startEngineButton.text = getString(R.string.btn_initialize_engine)
            setMainState(getString(R.string.status_checking_engine), true)

            lifecycleScope.launch {
                val alive = llamaServerManager.isServerAlive()
                if (alive) {
                    isEngineReady = true
                    binding.emptyStateLayout.visibility = if (chatAdapter.itemCount == 0) View.VISIBLE else View.GONE
                    binding.startEngineButton.visibility = View.GONE
                    setMainState(getString(R.string.status_engine_ready), false)
                } else {
                    setMainState(getString(R.string.status_models_found), false)
                }
            }
        } else {
            binding.emptyStateLayout.visibility = View.VISIBLE
            binding.downloadModelButton.visibility = View.VISIBLE
            binding.startEngineButton.visibility = View.GONE
            setMainState(getString(R.string.status_initializing), false)

            val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
            if (!prefs.getBoolean("onboarding_shown", false)) {
                showOnboardingDialog()
                prefs.edit().putBoolean("onboarding_shown", true).apply()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startLiveRamUpdates()
        if (isEngineReady) {
            lifecycleScope.launch {
                if (!llamaServerManager.isServerAlive()) {
                    isEngineReady = false
                    binding.emptyStateLayout.visibility = if (chatAdapter.itemCount == 0) View.VISIBLE else View.GONE
                    setMainState(getString(R.string.status_engine_reloading), true)
                    initEngine()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        ramUpdateJob?.cancel()
    }

    private fun startLiveRamUpdates() {
        ramUpdateJob?.cancel()
        ramUpdateJob = lifecycleScope.launch(Dispatchers.Main) {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            while (isActive) {
                activityManager.getMemoryInfo(memInfo)
                val totalRam = memInfo.totalMem / (1024 * 1024)
                val availRam = memInfo.availMem / (1024 * 1024)
                val usedRam = totalRam - availRam
                binding.ramUsageText.text = "RAM: ${usedRam}MB / ${totalRam}MB"
                
                if (memInfo.lowMemory) {
                    binding.ramUsageText.setTextColor(android.graphics.Color.RED)
                } else {
                    binding.ramUsageText.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.seed))
                }
                delay(1000)
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
        binding.addFileButton.setOnClickListener { selectFileLauncher.launch(SUPPORTED_MIME_TYPES) }
        binding.chatInputHint.setOnClickListener { selectFileLauncher.launch(SUPPORTED_MIME_TYPES) }
        binding.cancelButton.setOnClickListener { showCancelDialog() }
        binding.downloadModelButton.setOnClickListener { startModelDownload() }
        binding.startEngineButton.setOnClickListener { initEngine() }

        binding.qualityChip.setOnClickListener {
            val isFast = binding.qualityChip.text == getString(R.string.mode_fast)
            if (isFast) {
                binding.qualityChip.text = getString(R.string.mode_hq)
            } else {
                binding.qualityChip.text = getString(R.string.mode_fast)
            }
        }
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    processingJob?.isActive == true -> showCancelDialog()
                    engineLoadingJob?.isActive == true -> showEngineCancelDialog()
                    downloadJob?.isActive == true -> showDownloadCancelDialog()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }



    private fun showEngineCancelDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_cancel_engine_title))
            .setMessage(getString(R.string.dialog_cancel_engine_message))
            .setPositiveButton(getString(R.string.dialog_yes)) { _, _ ->
                engineLoadingJob?.cancel()
                llamaServerManager.stopServer()
                binding.downloadModelButton.isEnabled = true
                setMainState(getString(R.string.status_cancelled), false)
            }
            .setNegativeButton(getString(R.string.dialog_no), null)
            .show()
    }

    private fun showDownloadCancelDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_cancel_download_title))
            .setMessage(getString(R.string.dialog_cancel_download_message))
            .setPositiveButton(getString(R.string.dialog_yes)) { _, _ ->
                downloadJob?.cancel()
                downloadJob = null
                binding.downloadModelButton.isEnabled = true
                setMainState(getString(R.string.status_cancelled), false)
            }
            .setNegativeButton(getString(R.string.dialog_no), null)
            .show()
    }

    private fun startModelDownload() {
        if (modelsAlreadyDownloaded()) {
            initEngine()
            return
        }

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
        setMainState(getString(R.string.status_checking_models), true)
        binding.downloadModelButton.isEnabled = false

        downloadJob = lifecycleScope.launch {
            downloader.checkAndDownloadModels(
                onProgress = { overallPercent, fileName, fileIndex, totalFiles ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        setMainState(getString(
                            R.string.status_downloading_model_detail,
                            fileIndex + 1, totalFiles, fileName, overallPercent
                        ), true)
                    }
                },
                onComplete = { success ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        downloadJob = null
                        if (success) {
                            setMainState(getString(R.string.status_models_ready), false)
                            initEngine()
                        } else {
                            binding.downloadModelButton.isEnabled = true
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
                setMainState(getString(R.string.status_models_failed), false)
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
                setProcessingUIVisible(false)
                setMainState(getString(R.string.status_cancelled), false)
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
        val mainModelMinBytes = 100L * 1024 * 1024
        val mmprojMinBytes    = 10L  * 1024 * 1024
        return mainModel.exists() && mainModel.length() >= mainModelMinBytes &&
               mmproj.exists()    && mmproj.length()    >= mmprojMinBytes
    }

    private fun initEngine() {
        setMainState(getString(R.string.status_engine_starting), true)
        binding.downloadModelButton.isEnabled = false
        binding.startEngineButton.isEnabled = false

        llamaServerManager.setLoadingProgressListener(
            object : LlamaServerManager.LoadingProgressListener {
                override fun onLoadingProgress(elapsedSeconds: Int, maxSeconds: Int, stageMessage: String) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        setMainState(getString(
                            R.string.status_engine_loading_stage,
                            stageMessage, elapsedSeconds
                        ), true)
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
                llamaServerManager.setLoadingProgressListener(null)
                when (result) {
                    is LlamaServerManager.StartResult.Success -> {
                        isEngineReady = true
                        binding.startEngineButton.visibility = View.GONE
                        binding.emptyStateLayout.visibility = if (chatAdapter.itemCount == 0) View.VISIBLE else View.GONE
                        setMainState(getString(R.string.status_engine_ready), false)
                        binding.chatInputHint.text = getString(R.string.hint_select_file)
                    }
                    is LlamaServerManager.StartResult.InvalidBinary -> {
                        setMainState(getString(R.string.status_engine_failed), false)
                        showEngineErrorDialog(
                            getString(R.string.dialog_binary_missing_title),
                            getString(R.string.dialog_binary_missing_message)
                        )
                    }
                    is LlamaServerManager.StartResult.UnsupportedAbi -> {
                        setMainState(getString(R.string.status_engine_failed), false)
                        showEngineErrorDialog(
                            getString(R.string.dialog_unsupported_abi_title),
                            getString(R.string.dialog_unsupported_abi_message, result.deviceAbi)
                        )
                    }
                    is LlamaServerManager.StartResult.ProcessCrashed -> {
                        val detail = result.output.take(300).ifEmpty { getString(R.string.error_no_output) }
                        setMainState(getString(R.string.status_engine_failed), false)
                        showEngineRetryDialog(getString(R.string.dialog_engine_crash_message, detail))
                    }
                    is LlamaServerManager.StartResult.Timeout -> {
                        setMainState(getString(R.string.status_engine_failed), false)
                        showEngineRetryDialog(getString(R.string.dialog_engine_timeout_message))
                    }
                    is LlamaServerManager.StartResult.Error -> {
                        setMainState(getString(R.string.status_engine_failed), false)
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
                binding.startEngineButton.isEnabled = true
            }
            .setCancelable(false)
            .show()
    }

    private fun showEngineErrorDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                binding.startEngineButton.isEnabled = true
            }
            .setCancelable(false)
            .show()
    }

    private fun showDeviceInfoDialog() {
        val report = llamaServerManager.buildDeviceReport()

        val sb = java.lang.StringBuilder()
        sb.appendLine(getString(R.string.device_info_model, report.deviceModel))
        sb.appendLine(getString(R.string.device_info_android, report.androidVersion))
        sb.appendLine(getString(R.string.device_info_abi, report.primaryAbi))
        sb.appendLine()
        sb.appendLine(getString(R.string.device_info_section_cpu))
        sb.appendLine(getString(R.string.device_info_cpu_cores, report.totalCores, report.bigCores))
        if (report.maxFreqMhz > 0) {
            sb.appendLine(getString(R.string.device_info_cpu_freq, report.maxFreqMhz))
        } else {
            sb.appendLine(getString(R.string.device_info_cpu_freq_unknown))
        }
        sb.appendLine()
        sb.appendLine(getString(R.string.device_info_section_gpu))
        if (report.hasVulkan) {
            sb.appendLine(getString(R.string.device_info_gpu_vulkan_yes))
            sb.appendLine(getString(R.string.device_info_gpu_layers, report.gpuLayers))
        } else {
            sb.appendLine(getString(R.string.device_info_gpu_vulkan_no))
        }
        sb.appendLine()
        sb.appendLine(getString(R.string.device_info_section_ram))
        sb.appendLine(getString(R.string.device_info_ram_total, report.totalRamMb))
        sb.appendLine(getString(R.string.device_info_ram_available, report.availableRamMb))
        sb.appendLine()
        sb.appendLine(getString(R.string.device_info_section_inference))
        sb.appendLine(getString(R.string.device_info_threads, report.cpuThreads))
        sb.appendLine(getString(R.string.device_info_ctx_size, report.contextSize))
        if (report.gpuLayers > 0) {
            sb.appendLine(getString(R.string.device_info_gpu_layers, report.gpuLayers))
        }

        sb.appendLine()
        sb.appendLine(getString(R.string.device_info_section_thermal))
        if (report.cpuTemperatureC != null) {
            val tempStr = if (report.cpuTemperatureC > 70f)
                getString(R.string.device_info_cpu_temp_hot, report.cpuTemperatureC)
            else
                getString(R.string.device_info_cpu_temp, report.cpuTemperatureC)
            sb.appendLine(tempStr)
        } else {
            sb.appendLine(getString(R.string.device_info_cpu_temp_unknown))
        }
        sb.appendLine()
        sb.appendLine(
            if (report.batterySaverMode) getString(R.string.device_info_battery_saver_on)
            else getString(R.string.device_info_battery_saver_off)
        )

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.device_info_title))
            .setMessage(sb.toString().trimEnd())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun handleFileSelected(uri: Uri) {
        if (!isEngineReady) {
            Toast.makeText(this, getString(R.string.error_engine_not_ready), Toast.LENGTH_SHORT).show()
            return
        }

        val fileName = getFileName(uri)
        val mimeType = contentResolver.getType(uri)

        val isPdf = fileName.lowercase().endsWith(".pdf") || mimeType == "application/pdf"
        val isImage = SUPPORTED_IMAGE_TYPES.any { mimeType == it } ||
                fileName.lowercase().let { it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".webp") }

        if (!isPdf && !isImage) {
            Toast.makeText(this, getString(R.string.error_unsupported_file), Toast.LENGTH_LONG).show()
            return
        }

        binding.emptyStateLayout.visibility = View.GONE

        val userMsg = ChatMessage(
            type = ChatMessage.Type.USER,
            fileName = fileName,
            fileSizeMb = getFileSizeMb(uri)
        )
        chatAdapter.addMessage(userMsg)
        binding.chatRecyclerView.smoothScrollToPosition(chatAdapter.itemCount - 1)

        lifecycleScope.launch {
            val thumb = withContext(Dispatchers.IO) { loadThumbnail(uri, isPdf) }
            if (thumb != null) {
                // Save thumbnail to cache instead of holding memory
                val thumbFile = File(cacheDir, "thumb_${userMsg.id}.png")
                withContext(Dispatchers.IO) {
                    java.io.FileOutputStream(thumbFile).use { out ->
                        thumb.compress(Bitmap.CompressFormat.PNG, 90, out)
                    }
                }
                thumb.recycle() // Release memory early

                // Update User Message Thumbnail Path
                val index = chatAdapter.getItems().indexOfFirst { it.id == userMsg.id }
                if (index != -1) {
                    val updated = chatAdapter.getItems()[index].copy(thumbnailPath = thumbFile.absolutePath)
                    (chatAdapter.getItems() as MutableList)[index] = updated
                    chatAdapter.notifyItemChanged(index)
                }
            }
            processFile(uri, fileName, isPdf)
        }
    }

    private fun loadThumbnail(uri: Uri, isPdf: Boolean): Bitmap? = try {
        if (isPdf) {
            contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                PdfRenderer(fd).use { renderer ->
                    renderer.openPage(0).use { page ->
                        val scale = 200f / maxOf(page.width, page.height)
                        val bmp = Bitmap.createBitmap(
                            (page.width * scale).toInt().coerceAtLeast(1),
                            (page.height * scale).toInt().coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888
                        )
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }
            }
        } else {
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
    } catch (e: Exception) {
        null
    }

    private fun setProcessingUIVisible(processing: Boolean) {
        if (processing) {
            binding.addFileButton.isEnabled = false
            binding.chatInputHint.text = getString(R.string.hint_processing)
            binding.cancelButton.visibility = View.VISIBLE
            binding.qualityChip.isEnabled = false
        } else {
            binding.addFileButton.isEnabled = true
            binding.chatInputHint.text = getString(R.string.hint_select_file)
            binding.cancelButton.visibility = View.GONE
            binding.qualityChip.isEnabled = true
        }
    }

    private fun processFile(uri: Uri, fileName: String, isPdf: Boolean) {
        processingJob?.cancel()
        setProcessingUIVisible(true)
        startProcessingService()

        val botMsg = ChatMessage(type = ChatMessage.Type.BOT, state = ChatMessage.BotState.THINKING)
        chatAdapter.addMessage(botMsg)
        binding.chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)

        val processingStartMs = System.currentTimeMillis()
        val isFastMode = binding.qualityChip.text == getString(R.string.mode_fast)

        processingJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (isPdf) {
                    processPdfStreaming(uri, botMsg.id, isFastMode, processingStartMs)
                } else {
                    processImageStreaming(uri, botMsg.id, isFastMode, processingStartMs)
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "Processing cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Processing failed", e)
                withContext(Dispatchers.Main) {
                    chatAdapter.updateBotMessage(botMsg.id) {
                        it.state = ChatMessage.BotState.ERROR
                        it.errorMessage = getUserFriendlyError(e)
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    stopProcessingService()
                    setProcessingUIVisible(false)
                }
            }
        }
    }

    private suspend fun processPdfStreaming(uri: Uri, msgId: String, isFastMode: Boolean, startMs: Long) {
        val fd = contentResolver.openFileDescriptor(uri, "r")
            ?: throw Exception("Cannot open file")

        try {
            val renderer: PdfRenderer
            try {
                renderer = PdfRenderer(fd)
            } catch (e: SecurityException) {
                throw Exception(getString(R.string.error_pdf_password_protected))
            }

            try {
                val pageCount = renderer.pageCount
                if (pageCount == 0) throw Exception(getString(R.string.error_pdf_empty))

            val (defaultWidth, defaultHeight) = renderer.openPage(0).use { page ->
                Pair(page.width, page.height)
            }

            val availableMemMb = getAvailableMemoryMb()
            val maxDim = if (isFastMode) 1536 else Int.MAX_VALUE
            val baseScale = calculateSafeScale(defaultWidth, defaultHeight, availableMemMb, maxDim)

            var accumulatedPreviousPagesText = ""
            var currentTokPerSec: Double? = null

            val channel = kotlinx.coroutines.channels.Channel<Pair<Int, Bitmap>>(capacity = 2)

            val renderJob = launch(Dispatchers.Default) {
                try {
                    for (i in 0 until pageCount) {
                        currentCoroutineContext().ensureActive()
                        val bmp = renderer.openPage(i).use { page ->
                            val pageScale = if (page.width != defaultWidth) {
                                calculateSafeScale(page.width, page.height, availableMemMb, maxDim)
                            } else baseScale

                            val bitmap = Bitmap.createBitmap(
                                (page.width * pageScale).toInt().coerceAtLeast(1),
                                (page.height * pageScale).toInt().coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888
                            )
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            bitmap
                        }
                        channel.send(Pair(i, bmp))
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) Log.e(TAG, "Render exception", e)
                } finally {
                    channel.close()
                }
            }

            try {
                for (item in channel) {
                    val i = item.first
                    val bitmap = item.second

                    currentCoroutineContext().ensureActive()
                    val elapsedSec = ((System.currentTimeMillis() - startMs) / 1000).toInt()
                    var currentPageText = ""

                    withContext(Dispatchers.Main) {
                        chatAdapter.updateBotMessage(msgId) {
                            it.state = ChatMessage.BotState.STREAMING
                            it.currentPage = i + 1
                            it.totalPages = pageCount
                            it.elapsedSeconds = elapsedSec
                            it.streamedText = accumulatedPreviousPagesText
                        }
                        binding.chatRecyclerView.smoothScrollToPosition(chatAdapter.itemCount - 1)
                    }

                    Intent(this@MainActivity, OcrProcessingService::class.java).also { intent ->
                        intent.action = "UPDATE_PROGRESS"
                        intent.putExtra("CURRENT_PAGE", i + 1)
                        intent.putExtra("TOTAL_PAGES", pageCount)
                        startService(intent)
                    }

                    try {
                        var lastUiUpdate = 0L
                        ocrEngine.processImageStreaming(bitmap, maxDim)
                            .onCompletion { bitmap.recycle() }
                            .collect { token ->
                                currentCoroutineContext().ensureActive()
                                val nowMs = System.currentTimeMillis()
                                val nowSec = ((nowMs - startMs) / 1000).toInt()
                                
                                val isDone = token is OcrEngine.StreamToken.Done || token is OcrEngine.StreamToken.Error

                                when (token) {
                                    is OcrEngine.StreamToken.Text -> currentPageText += token.content
                                    is OcrEngine.StreamToken.Error -> currentPageText += "\n\n[Page Error: ${token.message}]\n\n"
                                    is OcrEngine.StreamToken.Done -> currentPageText = token.fullText
                                    else -> {}
                                }

                                if (nowMs - lastUiUpdate > 100 || isDone) {
                                    lastUiUpdate = nowMs
                                    withContext(Dispatchers.Main) {
                                        chatAdapter.updateBotMessage(msgId) {
                                            when (token) {
                                                is OcrEngine.StreamToken.Thinking -> {
                                                    it.state = ChatMessage.BotState.THINKING
                                                }
                                                is OcrEngine.StreamToken.Text -> {
                                                    it.state = ChatMessage.BotState.STREAMING
                                                    it.streamedText = accumulatedPreviousPagesText + currentPageText
                                                }
                                                is OcrEngine.StreamToken.Error -> {
                                                    it.state = ChatMessage.BotState.ERROR
                                                    it.errorMessage = token.message
                                                }
                                                is OcrEngine.StreamToken.Done -> {
                                                    accumulatedPreviousPagesText += currentPageText + "\n\n"
                                                    it.streamedText = accumulatedPreviousPagesText.trim()
                                                    currentTokPerSec = token.tokPerSec
                                                    it.pagesContent.add(currentPageText)
                                                }
                                            }
                                            it.elapsedSeconds = nowSec
                                        }
                                        binding.chatRecyclerView.smoothScrollToPosition(chatAdapter.itemCount - 1)
                                    }
                                }
                            }
                    } catch (e: Exception) {
                        bitmap.recycle()
                        if (e is CancellationException) throw e
                        val errorStr = "\n\n[Page ${i+1} Failed: ${e.message}]\n\n"
                        currentPageText += errorStr
                        accumulatedPreviousPagesText += errorStr
                        withContext(Dispatchers.Main) {
                            chatAdapter.updateBotMessage(msgId) {
                                it.pagesContent.add(errorStr)
                            }
                        }
                    }
                }
            } finally {
                renderJob.cancel()
                while (true) {
                    val residual = channel.tryReceive().getOrNull() ?: break
                    residual.second.recycle()
                }
            }

            withContext(Dispatchers.Main) {
                chatAdapter.updateBotMessage(msgId) {
                    it.state = ChatMessage.BotState.DONE
                    it.streamedText = accumulatedPreviousPagesText.trim()
                    it.tokPerSec = currentTokPerSec
                    it.elapsedSeconds = ((System.currentTimeMillis() - startMs) / 1000).toInt()
                }
            }

            try { renderer.close() } catch (_: Exception) {}

        } finally {
            try { fd.close() } catch (_: Exception) {}
        }
    }

    private suspend fun processImageStreaming(uri: Uri, msgId: String, isFastMode: Boolean, startMs: Long) {
        val maxDim = if (isFastMode) 1536 else Int.MAX_VALUE

        val dimensions = getImageDimensions(uri)
        if (dimensions != null) {
            val (width, height) = dimensions
            val pixelCount = width.toLong() * height
            if (pixelCount > MAX_IMAGE_PIXELS) {
                throw Exception(getString(R.string.error_image_too_large, "${width}x${height}", MAX_IMAGE_PIXELS / 1000000))
            }
        }

        val inputStream = contentResolver.openInputStream(uri)
            ?: throw Exception("Cannot open input stream")

        val bitmap: Bitmap
        try {
            var rawBitmap = BitmapFactory.decodeStream(inputStream)
                ?: throw Exception(getString(R.string.error_decode_failed))
            try {
                contentResolver.openInputStream(uri)?.use { exifStream ->
                    rawBitmap = correctBitmapRotation(rawBitmap, exifStream)
                }
            } catch (e: Exception) {
                Log.w(TAG, "EXIF rotation failed", e)
            }
            bitmap = rawBitmap
        } finally {
            inputStream.close()
        }

        var accumulatedText = ""
        var tokPerSec: Double? = null

        ocrEngine.processImageStreaming(bitmap, maxDim)
            .onCompletion { bitmap.recycle() }
            .collect { token ->
                currentCoroutineContext().ensureActive()
                val nowSec = ((System.currentTimeMillis() - startMs) / 1000).toInt()

                withContext(Dispatchers.Main) {
                    chatAdapter.updateBotMessage(msgId) {
                        when (token) {
                            is OcrEngine.StreamToken.Thinking -> {
                                it.state = ChatMessage.BotState.THINKING
                                it.elapsedSeconds = nowSec
                            }
                            is OcrEngine.StreamToken.Text -> {
                                it.state = ChatMessage.BotState.STREAMING
                                accumulatedText += token.content
                                it.streamedText = accumulatedText
                                it.elapsedSeconds = nowSec
                            }
                            is OcrEngine.StreamToken.Done -> {
                                it.state = ChatMessage.BotState.DONE
                                it.streamedText = token.fullText
                                it.tokPerSec = token.tokPerSec
                                it.elapsedSeconds = nowSec
                            }
                            is OcrEngine.StreamToken.Error -> {
                                it.state = ChatMessage.BotState.ERROR
                                it.errorMessage = token.message
                                it.elapsedSeconds = nowSec
                            }
                        }
                    }
                    if (token !is OcrEngine.StreamToken.Thinking) {
                        binding.chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
                    }
                }
            }
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
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
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

    private fun getAvailableMemoryMb(): Long {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.availMem / (1024 * 1024)
    }

    private fun calculateSafeScale(pageWidth: Int, pageHeight: Int, availableMemMb: Long, maxDim: Int = Int.MAX_VALUE): Float {
        val desiredScale = when {
            availableMemMb < 512 -> 0.75f
            availableMemMb < 1024 -> 1.0f
            availableMemMb < 2048 -> 1.5f
            else -> 2.0f
        }
        val maxPixels = 50L * 1024L * 1024L / 4L
        val memoryMaxScale = Math.sqrt(maxPixels.toDouble() / (pageWidth.toLong() * pageHeight)).toFloat()
        var finalScale = minOf(desiredScale, memoryMaxScale).coerceAtLeast(0.5f)
        
        if (maxDim != Int.MAX_VALUE) {
            val currentMaxDim = maxOf(pageWidth, pageHeight) * finalScale
            if (currentMaxDim > maxDim) {
                finalScale = maxDim.toFloat() / maxOf(pageWidth, pageHeight)
            }
        }
        return finalScale
    }

    private fun getFileSizeMb(uri: Uri): Float {
        return try {
            contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                fd.statSize / (1024f * 1024f)
            } ?: 0f
        } catch (e: Exception) {
            0f
        }
    }

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
            else -> e.message ?: getString(R.string.error_unknown)
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
            result = try { uri.path?.let { Uri.decode(it) } } catch (e: Exception) { uri.path }
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) result = result?.substring(cut + 1)
        }
        return result ?: "document"
    }

    private fun runBenchmark() {
        if (!isEngineReady) {
            Toast.makeText(this, getString(R.string.benchmark_engine_not_ready), Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, getString(R.string.status_benchmark_running), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val bitmap = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
            try {
                var sec: Double? = null
                ocrEngine.processImageStreaming(bitmap, 1536)
                    .onCompletion { bitmap.recycle() }
                    .collect { t -> if (t is OcrEngine.StreamToken.Done) sec = t.tokPerSec }
                Toast.makeText(this@MainActivity, "Benchmark: $sec tok/s", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Benchmark failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        processingJob?.cancel()
        engineLoadingJob?.cancel()
        downloadJob?.cancel()
        stopProcessingService()
        if (isFinishing) {
            llamaServerManager.stopServer()
        }
    }
}
