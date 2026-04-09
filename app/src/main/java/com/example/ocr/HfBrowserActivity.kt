package com.example.ocr

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import java.io.File

class HfBrowserActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hf_browser)

        val toolbar = findViewById<MaterialToolbar>(R.id.hfToolbar)
        toolbar.setNavigationOnClickListener { finish() }

        progressBar = findViewById(R.id.progressBar)
        webView = findViewById(R.id.webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Fix T6-SEC-03: Disable local file access
            allowFileAccess = false
            allowContentAccess = false
            // Fix T6-PERF-01: Enable caching for faster repeat loads
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        }
        
        // Fix CRITICAL-05: Strict domain whitelist
        webView.webViewClient = object : WebViewClient() {
            private val ALLOWED_HOSTS = setOf(
                "huggingface.co",
                "cdn-lfs.huggingface.co",
                "cdn-lfs-us-1.huggingface.co"
            )
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val host = request?.url?.host ?: return true
                val isAllowed = ALLOWED_HOSTS.any { host.endsWith(it) }
                if (!isAllowed) {
                    Toast.makeText(this@HfBrowserActivity, "URL blocked: $host", Toast.LENGTH_SHORT).show()
                    return true // Block
                }
                return false // Allow loading
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress == 100) {
                    progressBar.visibility = android.view.View.GONE
                } else {
                    progressBar.visibility = android.view.View.VISIBLE
                    progressBar.progress = newProgress
                }
            }
        }

        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            if (url.endsWith(".gguf") || url.contains("resolve/main") || url.contains("download=true")) {
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
                if (!fileName.endsWith(".gguf")) {
                    Toast.makeText(this, "Only .gguf files are supported for download", Toast.LENGTH_SHORT).show()
                    return@DownloadListener
                }

                val sizeText = if (contentLength > 0) "${contentLength / (1024 * 1024)} MB" else "Unknown Size"
                
                // Fix MEDIUM-15: Check if file already exists
                val baseDir = getExternalFilesDir(null) ?: filesDir
                val modelDir = File(baseDir, "models")
                val targetFile = File(modelDir, fileName)
                
                val msg = if (targetFile.exists()) {
                    "$fileName already exists. Do you want to overwrite it and download again? ($sizeText)"
                } else {
                    "Do you want to download $fileName? ($sizeText)"
                }

                AlertDialog.Builder(this)
                    .setTitle("Download Model")
                    .setMessage(msg)
                    .setPositiveButton("Download") { _, _ ->
                        if (targetFile.exists()) targetFile.delete()
                        downloadFile(url, userAgent, contentDisposition, mimetype, fileName)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })

        webView.loadUrl("https://huggingface.co/models?search=gguf")
        
        // Fix CRITICAL-04: Use OnBackPressedCallback instead of deprecated onBackPressed()
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun downloadFile(url: String, userAgent: String, contentDisposition: String, mimeType: String, fileName: String) {
        val request = DownloadManager.Request(Uri.parse(url))
        request.setMimeType(mimeType)
        
        val cookies = CookieManager.getInstance().getCookie(url)
        request.addRequestHeader("cookie", cookies)
        request.addRequestHeader("User-Agent", userAgent)
        
        request.setDescription("Downloading Model")
        request.setTitle(fileName)
        
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        
        // Save to app's external files dir (models)
        val baseDir = getExternalFilesDir(null) ?: filesDir
        val modelDir = File(baseDir, "models")
        if (!modelDir.exists()) modelDir.mkdirs()
        
        // Workaround: DownloadManager needs a Uri destination, we can set it to a path
        request.setDestinationUri(Uri.fromFile(File(modelDir, fileName)))

        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.enqueue(request)
        
        Toast.makeText(this, "Downloading $fileName...", Toast.LENGTH_LONG).show()
    }

}
