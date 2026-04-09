package com.example.ocr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.recyclerview.widget.RecyclerView
import io.noties.markwon.Markwon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatAdapter(
    private val context: Context,
    private val onExportClicked: (ChatMessage) -> Unit,
    private val onImageClicked: (String) -> Unit,
    private val onShareClicked: (ChatMessage) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()
    private val markwon: Markwon = Markwon.builder(context)
        .usePlugin(io.noties.markwon.ext.tables.TablePlugin.create(context))
        .build()

    // Fix HIGH-07: Background scope for async bitmap loading
    private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        const val VIEW_TYPE_USER = 1
        const val VIEW_TYPE_BOT = 2
    }

    // Fix MEDIUM-30: Enable stable IDs for smoother RecyclerView animations
    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return messages[position].id.hashCode().toLong()
    }

    fun addMessage(msg: ChatMessage) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }

    // Fix CRITICAL-01: Safe update method instead of `as MutableList` cast
    fun updateMessage(index: Int, msg: ChatMessage) {
        if (index in messages.indices) {
            messages[index] = msg
            notifyItemChanged(index)
        }
    }

    fun clearMessages() {
        val size = messages.size
        messages.clear()
        notifyItemRangeRemoved(0, size)
    }

    // Fix MEDIUM-29: @MainThread annotation to enforce thread safety
    @MainThread
    fun updateBotMessage(msgId: String, update: (ChatMessage) -> Unit) {
        val index = messages.indexOfFirst { it.id == msgId }
        if (index != -1) {
            update(messages[index])
            notifyItemChanged(index, "PAYLOAD_TEXT_UPDATE")
        }
    }

    fun getItems(): List<ChatMessage> = messages

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].type == ChatMessage.Type.USER) {
            VIEW_TYPE_USER
        } else {
            VIEW_TYPE_BOT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_USER) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_user, parent, false)
            UserViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_bot, parent, false)
            BotViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        onBindViewHolder(holder, position, mutableListOf())
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        val msg = messages[position]
        if (holder is UserViewHolder) {
            holder.bind(msg)
        } else if (holder is BotViewHolder) {
            // Partial bind for smooth streaming UI updates without flickering
            if (payloads.contains("PAYLOAD_TEXT_UPDATE")) {
                holder.bindText(msg)
            } else {
                holder.bind(msg)
            }
        }
    }

    override fun getItemCount() = messages.size

    inner class UserViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val fileNameText: TextView = view.findViewById(R.id.fileNameText)
        private val thumbnailImage: ImageView = view.findViewById(R.id.thumbnailImage)
        private val fileInfoText: TextView = view.findViewById(R.id.fileInfoText)

        fun bind(msg: ChatMessage) {
            fileNameText.text = msg.fileName ?: context.getString(R.string.default_document_name)
            val path = msg.thumbnailPath
            if (path != null) {
                thumbnailImage.visibility = View.VISIBLE
                // Fix HIGH-07: Decode bitmap asynchronously to avoid main thread jank
                val currentPosition = absoluteAdapterPosition
                adapterScope.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                        BitmapFactory.decodeFile(path, opts)
                    }
                    if (bitmap != null && currentPosition != RecyclerView.NO_POSITION) {
                        thumbnailImage.setImageBitmap(bitmap)
                        thumbnailImage.setOnClickListener {
                            onImageClicked(path)
                        }
                    }
                }
            } else {
                thumbnailImage.setImageDrawable(null)
                thumbnailImage.visibility = View.GONE
                thumbnailImage.setOnClickListener(null)
            }

            // Fix MEDIUM-27: Use string resource for file info
            val pageInfo = if (msg.pageCount > 1) "${msg.pageCount} pages • " else ""
            fileInfoText.text = context.getString(R.string.file_size_display, pageInfo, msg.fileSizeMb)
        }
    }

    inner class BotViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val botMessageText: TextView = view.findViewById(R.id.botMessageText)
        private val thinkingIndicator: LinearLayout = view.findViewById(R.id.thinkingIndicator)
        private val timingText: TextView = view.findViewById(R.id.timingText)
        private val actionButtonsContainer: LinearLayout = view.findViewById(R.id.actionButtonsContainer)
        private val errorText: TextView = view.findViewById(R.id.errorText)

        private val btnCopy: Button = view.findViewById(R.id.btnCopy)
        private val btnShare: Button = view.findViewById(R.id.btnShare)
        private val btnMarkdown: Button = view.findViewById(R.id.btnMarkdown)
        private val btnSave: Button = view.findViewById(R.id.btnSaveDocx)

        fun bind(msg: ChatMessage) {
            bindText(msg)

            btnCopy.setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("OCR Text", msg.streamedText)
                clipboard.setPrimaryClip(clip)
            }

            btnShare.setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onShareClicked(msg)
            }

            btnMarkdown.setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                msg.isMarkdownEnabled = !msg.isMarkdownEnabled
                btnMarkdown.text = if (msg.isMarkdownEnabled) context.getString(R.string.btn_markdown_raw) else context.getString(R.string.btn_markdown_formatted)
                bindText(msg) // Re-render text
            }

            btnSave.setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onExportClicked(msg)
            }
        }

        fun bindText(msg: ChatMessage) {
            // Handle states
            when (msg.state) {
                ChatMessage.BotState.THINKING -> {
                    thinkingIndicator.visibility = View.VISIBLE
                    botMessageText.visibility = View.GONE
                    timingText.visibility = View.GONE
                    actionButtonsContainer.visibility = View.GONE
                    errorText.visibility = View.GONE
                }
                ChatMessage.BotState.STREAMING -> {
                    thinkingIndicator.visibility = View.GONE
                    botMessageText.visibility = View.VISIBLE
                    renderText(msg)
                    timingText.visibility = View.VISIBLE
                    // Fix MEDIUM-27: Use string resource for timing
                    val progress = if (msg.totalPages > 1) context.getString(R.string.timing_page_progress, msg.currentPage, msg.totalPages) + " • " else ""
                    timingText.text = context.getString(R.string.timing_elapsed, progress, msg.elapsedSeconds)
                    actionButtonsContainer.visibility = View.GONE
                    errorText.visibility = View.GONE
                }
                ChatMessage.BotState.DONE -> {
                    thinkingIndicator.visibility = View.GONE
                    botMessageText.visibility = View.VISIBLE
                    renderText(msg)
                    timingText.visibility = View.VISIBLE
                    
                    val pages = if (msg.totalPages > 1) "✅ ${msg.totalPages} pages • " else "✅ "
                    val speed = msg.tokPerSec?.let { " • ${String.format("%.1f", it)} tok/s" } ?: ""
                    timingText.text = context.getString(R.string.timing_done, pages, msg.elapsedSeconds, speed)
                    
                    actionButtonsContainer.visibility = View.VISIBLE
                    btnMarkdown.text = if (msg.isMarkdownEnabled) context.getString(R.string.btn_markdown_raw) else context.getString(R.string.btn_markdown_formatted)
                    errorText.visibility = View.GONE
                }
                ChatMessage.BotState.ERROR -> {
                    thinkingIndicator.visibility = View.GONE
                    botMessageText.visibility = View.GONE // Keep hiding or show partial? Show partial
                    if (msg.streamedText.isNotEmpty()) {
                        botMessageText.visibility = View.VISIBLE
                        renderText(msg)
                    }
                    actionButtonsContainer.visibility = View.GONE
                    errorText.visibility = View.VISIBLE
                    // Fix MEDIUM-27: Use string resource for error prefix
                    errorText.text = context.getString(R.string.error_prefix, msg.errorMessage ?: "")
                    timingText.visibility = View.GONE
                }
            }
        }

        private fun renderText(msg: ChatMessage) {
            if (msg.isMarkdownEnabled && msg.state == ChatMessage.BotState.DONE) {
                markwon.setMarkdown(botMessageText, msg.streamedText)
            } else {
                botMessageText.text = msg.streamedText
            }
        }
    }
}
