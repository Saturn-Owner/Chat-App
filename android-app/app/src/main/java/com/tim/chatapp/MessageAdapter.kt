package com.tim.chatapp

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ChatMessage(
    val messageId: String,
    val isMine: Boolean,
    val timestamp: String,
    var text: String? = null,
    var delivered: Boolean = false,
    var isRevealed: Boolean = false,
    var wasDecrypted: Boolean = false,
    val encryptedItem: MessageItem? = null,
    val senderName: String? = null,
    val messageType: String = "text",
    var imageBytes: ByteArray? = null,
)

class MessageAdapter(
    private val onDecrypt: (contactId: String, item: MessageItem, callback: (String?) -> Unit) -> Unit,
    private val onLoadFromStore: (messageId: String, callback: (String?) -> Unit) -> Unit,
    private val onDownloadImage: (payload: String, callback: (ByteArray?) -> Unit) -> Unit = { _, cb -> cb(null) },
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()
    private val formatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    private val SCRAMBLE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#\$%"

    companion object {
        private const val VIEW_MINE = 0
        private const val VIEW_OTHER = 1
    }

    fun addMessage(msg: ChatMessage) {
        if (messages.none { it.messageId == msg.messageId }) {
            messages.add(msg)
            notifyItemInserted(messages.lastIndex)
        }
    }

    fun removeMessage(messageId: String) {
        val idx = messages.indexOfFirst { it.messageId == messageId }
        if (idx >= 0) { messages.removeAt(idx); notifyItemRemoved(idx) }
    }

    fun markDelivered(messageId: String) {
        val idx = messages.indexOfFirst { it.messageId == messageId }
        if (idx >= 0) { messages[idx].delivered = true; notifyItemChanged(idx) }
    }

    fun hideAllReceived() {
        messages.forEachIndexed { i, msg ->
            if (!msg.isMine && msg.isRevealed) {
                msg.isRevealed = false
                msg.text = null
                msg.imageBytes = null
                notifyItemChanged(i)
            }
        }
    }

    override fun getItemViewType(position: Int) =
        if (messages[position].isMine) VIEW_MINE else VIEW_OTHER

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_MINE)
            MineHolder(inflater.inflate(R.layout.item_message_mine, parent, false))
        else
            OtherHolder(inflater.inflate(R.layout.item_message_other, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        val time = try { formatter.format(Instant.parse(msg.timestamp)) }
        catch (_: Exception) { msg.timestamp.take(5) }

        when (holder) {
            is MineHolder -> bindMine(holder, msg, time)
            is OtherHolder -> bindOther(holder, msg, time)
        }
    }

    private fun bindMine(holder: MineHolder, msg: ChatMessage, time: String) {
        holder.textTime.text = time
        holder.textStatus.text = if (msg.delivered) " ✓✓" else " ✓"
        holder.textStatus.setTextColor(
            if (msg.delivered) 0xFF00FF41.toInt() else 0xFF444444.toInt()
        )

        if (msg.messageType?.equals("image") == true) {
            val bytes = msg.imageBytes
            if (bytes != null) {
                showImage(holder.imageMessage, holder.textMessage, bytes)
            } else {
                // Image not yet loaded (e.g. after restart) — tap to reload
                holder.imageMessage?.visibility = View.GONE
                holder.textMessage.visibility = View.VISIBLE
                holder.textMessage.text = "🖼 [Tippen zum laden]"
                holder.itemView.isClickable = true
                holder.itemView.setOnClickListener {
                    holder.itemView.isClickable = false
                    holder.textMessage.text = "Lade Bild…"
                    val payload = msg.text ?: return@setOnClickListener
                    onDownloadImage(payload) { imageBytes ->
                        val idx = messages.indexOf(msg)
                        if (imageBytes != null && idx >= 0) {
                            msg.imageBytes = imageBytes
                            notifyItemChanged(idx)
                        } else {
                            holder.textMessage.text = "🖼 [Tippen zum laden]"
                            holder.itemView.isClickable = true
                        }
                    }
                }
            }
        } else {
            holder.imageMessage?.visibility = View.GONE
            holder.textMessage.visibility = View.VISIBLE
            holder.textMessage.text = msg.text ?: "…"
            holder.itemView.isClickable = false
            holder.itemView.setOnClickListener(null)
        }
    }

    private fun bindOther(holder: OtherHolder, msg: ChatMessage, time: String) {
        holder.textTime.text = time
        if (msg.senderName != null) {
            holder.textSenderName.text = msg.senderName
            holder.textSenderName.visibility = View.VISIBLE
        } else {
            holder.textSenderName.visibility = View.GONE
        }

        if (msg.messageType?.equals("image") == true) {
            bindOtherImage(holder, msg)
            return
        }

        // Normal text message
        if (msg.isRevealed && msg.text != null) {
            holder.imageMessage?.visibility = View.GONE
            holder.textMessage.visibility = View.VISIBLE
            holder.textMessage.text = msg.text
            holder.itemView.isClickable = false
            holder.itemView.setOnClickListener(null)
        } else if (msg.wasDecrypted) {
            holder.imageMessage?.visibility = View.GONE
            holder.textMessage.visibility = View.VISIBLE
            holder.textMessage.text = ciphertextPreview(msg.encryptedItem)
            holder.itemView.isClickable = true
            holder.itemView.setOnClickListener {
                holder.itemView.isClickable = false
                holder.textMessage.text = "Lade…"
                onLoadFromStore(msg.messageId) { stored ->
                    val idx = messages.indexOf(msg)
                    if (stored != null && idx >= 0) {
                        msg.text = stored
                        msg.isRevealed = true
                        animateDecrypt(holder.textMessage, stored) {}
                    } else {
                        holder.textMessage.text = ciphertextPreview(msg.encryptedItem)
                        holder.itemView.isClickable = true
                    }
                }
            }
        } else {
            holder.imageMessage?.visibility = View.GONE
            holder.textMessage.visibility = View.VISIBLE
            holder.textMessage.text = ciphertextPreview(msg.encryptedItem)
            holder.itemView.isClickable = true
            holder.itemView.setOnClickListener {
                holder.itemView.isClickable = false
                holder.textMessage.text = "Entschlüssele…"
                val contactId = msg.encryptedItem?.sender_device_id ?: return@setOnClickListener
                onDecrypt(contactId, msg.encryptedItem) { plaintext ->
                    val idx = messages.indexOf(msg)
                    if (plaintext != null && idx >= 0) {
                        msg.text = plaintext
                        msg.isRevealed = true
                        msg.wasDecrypted = true
                        animateDecrypt(holder.textMessage, plaintext) {}
                        notifyItemChanged(idx)
                    } else {
                        holder.textMessage.text = ciphertextPreview(msg.encryptedItem)
                        holder.itemView.isClickable = true
                    }
                }
            }
        }
    }

    private fun bindOtherImage(holder: OtherHolder, msg: ChatMessage) {
        if (msg.isRevealed && msg.imageBytes != null) {
            showImage(holder.imageMessage, holder.textMessage, msg.imageBytes!!)
            holder.itemView.isClickable = false
            holder.itemView.setOnClickListener(null)
        } else if (msg.wasDecrypted) {
            // Payload bekannt (aus Store), Bild muss neu geladen werden
            holder.imageMessage?.visibility = View.GONE
            holder.textMessage.visibility = View.VISIBLE
            holder.textMessage.text = "🖼 [Tippen zum laden]"
            holder.itemView.isClickable = true
            holder.itemView.setOnClickListener {
                holder.itemView.isClickable = false
                holder.textMessage.text = "Lade Bild…"
                onLoadFromStore(msg.messageId) { payload ->
                    if (payload != null) {
                        onDownloadImage(payload) { imageBytes ->
                            val idx = messages.indexOf(msg)
                            if (imageBytes != null && idx >= 0) {
                                msg.text = payload
                                msg.isRevealed = true
                                msg.imageBytes = imageBytes
                                notifyItemChanged(idx)
                            } else {
                                holder.textMessage.text = "🖼 [Tippen zum laden]"
                                holder.itemView.isClickable = true
                            }
                        }
                    } else {
                        holder.textMessage.text = "🖼 [Tippen zum laden]"
                        holder.itemView.isClickable = true
                    }
                }
            }
        } else {
            // Noch nicht entschlüsselt
            holder.imageMessage?.visibility = View.GONE
            holder.textMessage.visibility = View.VISIBLE
            holder.textMessage.text = "🔒 [Bild]"
            holder.itemView.isClickable = true
            holder.itemView.setOnClickListener {
                holder.itemView.isClickable = false
                holder.textMessage.text = "Entschlüssele…"
                val contactId = msg.encryptedItem?.sender_device_id ?: return@setOnClickListener
                onDecrypt(contactId, msg.encryptedItem!!) { payload ->
                    if (payload != null) {
                        msg.wasDecrypted = true
                        onDownloadImage(payload) { imageBytes ->
                            val idx = messages.indexOf(msg)
                            if (imageBytes != null && idx >= 0) {
                                msg.text = payload
                                msg.isRevealed = true
                                msg.imageBytes = imageBytes
                                notifyItemChanged(idx)
                            } else {
                                holder.textMessage.text = "🖼 [Tippen zum laden]"
                                holder.itemView.isClickable = true
                            }
                        }
                    } else {
                        holder.textMessage.text = "🔒 [Bild]"
                        holder.itemView.isClickable = true
                    }
                }
            }
        }
    }

    private fun showImage(imageView: ImageView?, textView: TextView, bytes: ByteArray) {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bitmap != null && imageView != null) {
            imageView.setImageBitmap(bitmap)
            imageView.visibility = View.VISIBLE
            textView.visibility = View.GONE
        } else {
            imageView?.visibility = View.GONE
            textView.visibility = View.VISIBLE
            textView.text = if (bitmap == null) "🖼 [Bild konnte nicht geladen werden]" else "🖼 [Bild]"
        }
    }

    override fun getItemCount() = messages.size

    private fun ciphertextPreview(item: MessageItem?): String {
        val raw = item?.ciphertext ?: return "🔒 [verschlüsselt]"
        return "🔒 $raw"
    }

    private fun animateDecrypt(textView: TextView, plaintext: String, onDone: () -> Unit) {
        val maxAnim = minOf(plaintext.length, 60)
        val duration = 20L
        for (index in 0 until maxAnim) {
            repeat(4) { tick ->
                textView.postDelayed({
                    if (textView.isAttachedToWindow) {
                        textView.text = buildString {
                            for (i in plaintext.indices) {
                                append(if (i < index) plaintext[i] else SCRAMBLE.random())
                            }
                        }
                    }
                }, index * duration + tick * 5L)
            }
            textView.postDelayed({
                if (textView.isAttachedToWindow) {
                    val revealed = plaintext.substring(0, index + 1)
                    val rest = if (index + 1 < plaintext.length)
                        buildString { repeat(plaintext.length - index - 1) { append(SCRAMBLE.random()) } }
                    else ""
                    textView.text = revealed + rest
                    if (index == maxAnim - 1) {
                        textView.text = plaintext
                        onDone()
                    }
                }
            }, index * duration + 18L)
        }
        if (maxAnim == 0) { textView.text = plaintext; onDone() }
    }

    class MineHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageMessage: ImageView? = view.findViewById(R.id.imageMessage)
        val textMessage: TextView = view.findViewById(R.id.textMessage)
        val textTime: TextView = view.findViewById(R.id.textTime)
        val textStatus: TextView = view.findViewById(R.id.textStatus)
    }

    class OtherHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageMessage: ImageView? = view.findViewById(R.id.imageMessage)
        val textMessage: TextView = view.findViewById(R.id.textMessage)
        val textTime: TextView = view.findViewById(R.id.textTime)
        val textSenderName: TextView = view.findViewById(R.id.textSenderName)
    }
}
