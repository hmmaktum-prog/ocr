package com.example.ocr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import java.io.File

class ModelManagerActivity : AppCompatActivity() {

    private lateinit var rvModels: RecyclerView
    private lateinit var btnBrowseHf: ExtendedFloatingActionButton
    private lateinit var modelsAdapter: ModelsAdapter
    private lateinit var tvEmptyModels: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_manager)

        val toolbar = findViewById<MaterialToolbar>(R.id.modelManagerToolbar)
        toolbar.setNavigationOnClickListener { finish() }

        rvModels = findViewById(R.id.modelsRecyclerView)
        btnBrowseHf = findViewById(R.id.btnBrowseHf)
        tvEmptyModels = findViewById(R.id.tvEmptyModels)

        rvModels.layoutManager = LinearLayoutManager(this)
        modelsAdapter = ModelsAdapter(this, getModelsDir(), lifecycleScope) { file ->
            showSetModelDialog(file)
        }
        rvModels.adapter = modelsAdapter

        btnBrowseHf.setOnClickListener {
            startActivity(Intent(this, HfBrowserActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        modelsAdapter.refresh()
        updateEmptyState()
    }

    private fun updateEmptyState() {
        val isEmpty = modelsAdapter.itemCount == 0
        tvEmptyModels.visibility = if (isEmpty) View.VISIBLE else View.GONE
        rvModels.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun getModelsDir(): File {
        val baseDir = getExternalFilesDir(null) ?: filesDir
        val dir = File(baseDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun showSetModelDialog(file: File) {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val currentMain = prefs.getString("main_model_path", "")
        val currentVision = prefs.getString("vision_model_path", "")

        val isMain = file.absolutePath == currentMain
        val isVision = file.absolutePath == currentVision

        val options = arrayOf(
            getString(R.string.dialog_set_model_main),
            getString(R.string.dialog_set_model_vision)
        )

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_set_model_title))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> prefs.edit().putString("main_model_path", file.absolutePath).apply()
                    1 -> prefs.edit().putString("vision_model_path", file.absolutePath).apply()
                }
                modelsAdapter.refresh()
                // Fix T5-USE-01: Show restart engine snackbar
                Snackbar.make(findViewById(android.R.id.content), getString(R.string.snack_restart_engine), Snackbar.LENGTH_LONG).show()
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    class ModelsAdapter(
        private val context: Context,
        private val modelsDir: File,
        private val scope: CoroutineScope,
        private val onClick: (File) -> Unit
    ) : RecyclerView.Adapter<ModelsAdapter.ViewHolder>() {

        private var modelFiles: List<File> = emptyList()

        init {
            refresh()
        }

        fun refresh() {
            scope.launch {
                val files = withContext(Dispatchers.IO) {
                    modelsDir.listFiles { _, name -> name.endsWith(".gguf") }?.toList() ?: emptyList()
                }
                modelFiles = files
                notifyDataSetChanged()
                
                if (context is ModelManagerActivity) {
                    context.updateEmptyState(files.isEmpty())
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_model, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val file = modelFiles[position]
            holder.tvModelName.text = file.name
            
            val sizeMb = file.length() / (1024 * 1024)
            if (sizeMb > 1000) {
                holder.tvModelSize.text = String.format("%.2f GB", sizeMb / 1024f)
            } else {
                holder.tvModelSize.text = "${sizeMb} MB"
            }

            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val currentMain = prefs.getString("main_model_path", "")
            val currentVision = prefs.getString("vision_model_path", "")

            when (file.absolutePath) {
                currentMain -> {
                    holder.tvModelStatus.text = context.getString(R.string.model_status_active_main)
                    holder.tvModelStatus.setTextColor(ContextCompat.getColor(context, R.color.colorOnPrimaryContainer))
                }
                currentVision -> {
                    holder.tvModelStatus.text = context.getString(R.string.model_status_active_vision)
                    holder.tvModelStatus.setTextColor(ContextCompat.getColor(context, R.color.colorOnPrimaryContainer))
                }
                else -> {
                    holder.tvModelStatus.text = context.getString(R.string.model_status_inactive)
                    holder.tvModelStatus.setTextColor(ContextCompat.getColor(context, R.color.colorOnSurfaceVariant))
                }
            }

            holder.itemView.setOnClickListener { onClick(file) }
            
            holder.btnDelete.setOnClickListener {
                AlertDialog.Builder(context)
                    .setTitle(context.getString(R.string.dialog_delete_model_title))
                    .setMessage(context.getString(R.string.dialog_delete_model_msg))
                    .setPositiveButton(context.getString(R.string.dialog_delete)) { _, _ ->
                        if (file.absolutePath == currentMain) prefs.edit().remove("main_model_path").apply()
                        if (file.absolutePath == currentVision) prefs.edit().remove("vision_model_path").apply()
                        file.delete()
                        refresh()
                    }
                    .setNegativeButton(context.getString(R.string.dialog_cancel), null)
                    .show()
            }
        }

        override fun getItemCount(): Int = modelFiles.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvModelName: TextView = view.findViewById(R.id.tvModelName)
            val tvModelSize: TextView = view.findViewById(R.id.tvModelSize)
            val tvModelStatus: TextView = view.findViewById(R.id.tvModelStatus)
            val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
        }
    }
}
