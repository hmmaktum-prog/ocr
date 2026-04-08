package com.example.ocr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import io.noties.markwon.Markwon

class ChatAdapter(
    private val context: Context,
    private val onExportClicked: (ChatMessage) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()
    private val markwon: Markwon = Markwon.create(context)

    companion object {
        const val VIEW_TYPE_USER = 1
        const val VIEW_TYPE_BOT = 2
    }

    fun addMessage(msg: ChatMessage) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }

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
            fileNameText.text = msg.fileName ?: "Document"
            val path = msg.thumbnailPath
            if (path != null) {
                val bitmap = android.graphics.BitmapFactory.decodeFile(path)
                thumbnailImage.setImageBitmap(bitmap)
                thumbnailImage.visibility = View.VISIBLE
            } else {
                thumbnailImage.visibility = View.GONE
            }

            val pageInfo = if (msg.pageCount > 1) "${msg.pageCount} pages • " else ""
            fileInfoText.text = "$pageInfo${String.format("%.1f", msg.fileSizeMb)} MB"
        }
    }

    inner class BotViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val botMessageText: TextView = view.findViewById(R.id.botMessageText)
        private val thinkingIndicator: LinearLayout = view.findViewById(R.id.thinkingIndicator)
        private val timingText: TextView = view.findViewById(R.id.timingText)
        private val actionButtonsContainer: LinearLayout = view.findViewById(R.id.actionButtonsContainer)
        private val errorText: TextView = view.findViewById(R.id.errorText)

        private val btnCopy: Button = view.findViewById(R.id.btnCopy)
        private val btnMarkdown: Button = view.findViewById(R.id.btnMarkdown)
        private val btnSave: Button = view.findViewById(R.id.btnSaveDocx)

        fun bind(msg: ChatMessage) {
            bindText(msg)

            btnCopy.setOnClickListener {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("OCR Text", msg.streamedText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }

            btnMarkdown.setOnClickListener {
                msg.isMarkdownEnabled = !msg.isMarkdownEnabled
                btnMarkdown.text = if (msg.isMarkdownEnabled) "Raw" else "Markdown"
                bindText(msg) // Re-render text
            }

            btnSave.setOnClickListener {
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
                    val progress = if (msg.totalPages > 1) "Page ${msg.currentPage}/${msg.totalPages} • " else ""
                    timingText.text = "${progress}${msg.elapsedSeconds}s elapsed"
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
                    timingText.text = "${pages}${msg.elapsedSeconds}s $speed"
                    
                    actionButtonsContainer.visibility = View.VISIBLE
                    btnMarkdown.text = if (msg.isMarkdownEnabled) "Raw" else "Markdown"
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
                    errorText.text = "Error: ${msg.errorMessage}"
                    timingText.visibility = View.GONE
                }
            }
        }

        private fun renderText(msg: ChatMessage) {
            if (msg.isMarkdownEnabled) {
                markwon.setMarkdown(botMessageText, msg.streamedText)
            } else {
                botMessageText.text = msg.streamedText
            }
        }
    }
}
