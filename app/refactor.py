import os
import re

file_path = "c:\\Users\\zanna\\Documents 1\\ocr\\app\\src\\main\\java\\com\\example\\ocr\\MainActivity.kt"
with open(file_path, "r", encoding="utf-8") as f:
    text = f.read()

# 1. Imports and Variables
new_imports = """import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion"""
text = text.replace("import androidx.lifecycle.lifecycleScope", "import androidx.lifecycle.lifecycleScope\n" + new_imports)

# Replace variables
text = re.sub(
    r"private lateinit var llamaServerManager: LlamaServerManager.*?(?=private val saveDocumentLauncher)",
    """private lateinit var llamaServerManager: LlamaServerManager
    private lateinit var chatAdapter: ChatAdapter

    private var processingJob: Job? = null
    private var engineLoadingJob: Job? = null
    private var downloadJob: Job? = null
    private var isEngineReady: Boolean = false
    
""", text, flags=re.DOTALL | re.MULTILINE)

# 2. Extract and format onCreate
on_create_replacement = """        chatAdapter = ChatAdapter(this) { msg ->
            val baseDir = getExternalFilesDir(null) ?: filesDir
            val outFile = File(baseDir, "temp_export.docx")
            val success = ocrEngine.generateDocx(arrayOf(msg.streamedText), outFile.absolutePath, "Page")
            if (success) {
                // saveDocumentLauncher.launch(msg.fileName?.replace(".pdf", ".docx")?.replace(".jpg", ".docx") ?: "Export.docx")
            } else {
                Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show()
            }
        }
        val layoutManager = LinearLayoutManager(this)
        layoutManager.stackFromEnd = true
        binding.chatRecyclerView.layoutManager = layoutManager
        binding.chatRecyclerView.adapter = chatAdapter

        binding.qualityChip.text = getString(R.string.mode_fast)
"""
text = text.replace("setupBackPressHandler()", on_create_replacement + "\n        setupBackPressHandler()")

# 3. Handle checkStartupState and setupButtons
text = re.sub(
    r"private fun checkStartupState\(\) \{.*?(?=override fun onResume\(\))",
    """private fun setMainState(subtitle: String, loading: Boolean) {
        binding.topAppBar.subtitle = subtitle
        binding.mainProgressIndicator.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun checkStartupState() {
        if (modelsAlreadyDownloaded()) {
            binding.emptyStateLayout.visibility = View.VISIBLE
            binding.downloadModelButton.visibility = View.GONE
            binding.startEngineButton.visibility = View.VISIBLE
            binding.startEngineButton.text = getString(R.string.btn_initialize_engine)
            setMainState("Checking engine...", true)

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
        }
    }
    
""", text, flags=re.DOTALL)

# 4. Handle setupButtons
text = re.sub(
    r"private fun setupButtons\(\) \{.*?(?=private fun setupBackPressHandler\(\))",
    """private fun setupButtons() {
        binding.downloadModelButton.setOnClickListener { startModelDownload() }
        binding.startEngineButton.setOnClickListener { initEngine() }
        binding.addFileButton.setOnClickListener { selectFileLauncher.launch(SUPPORTED_MIME_TYPES) }
        binding.chatInputHint.setOnClickListener { selectFileLauncher.launch(SUPPORTED_MIME_TYPES) }
        binding.cancelButton.setOnClickListener { showCancelDialog() }
        
        binding.qualityChip.setOnClickListener {
            val isFast = binding.qualityChip.text == getString(R.string.mode_fast)
            if (isFast) {
                binding.qualityChip.text = getString(R.string.mode_hq)
                binding.qualityChip.setChipIconResource(R.drawable.ic_format)
            } else {
                binding.qualityChip.text = getString(R.string.mode_fast)
                binding.qualityChip.setChipIconResource(R.drawable.ic_benchmark)
            }
        }
    }
    
""", text, flags=re.DOTALL)


# 5. Overwrite the rest of specific UI code manually
# I'll output it below
with open(file_path, "w", encoding="utf-8") as f:
    f.write(text)

print("Check 1 done.")
