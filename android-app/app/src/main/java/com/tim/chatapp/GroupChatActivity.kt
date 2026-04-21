package com.tim.chatapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.Gson
import com.tim.chatapp.databinding.ActivityGroupChatBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class GroupChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGroupChatBinding
    private lateinit var adapter: MessageAdapter
    private lateinit var deviceId: String
    private lateinit var token: String
    private lateinit var groupId: String
    private lateinit var groupName: String

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) lifecycleScope.launch { sendImage(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
        )
        binding = ActivityGroupChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceId = DeviceStorage.getDeviceId(this) ?: ""
        token = DeviceStorage.getToken(this) ?: ""
        groupId = intent.getStringExtra("group_id") ?: ""
        groupName = intent.getStringExtra("group_name") ?: "Gruppe"

        binding.textGroupName.text = groupName
        binding.buttonBack.setOnClickListener { finish() }

        val group = GroupStore.get(this, groupId)
        if (group != null) {
            val others = group.members.count { it.deviceId != deviceId }
            binding.textMemberCount.text = "${group.members.size} Mitglieder"
        }

        adapter = MessageAdapter(
            onDecrypt = { _, item, callback ->
                lifecycleScope.launch {
                    val result = decryptGroupMessage(item)
                    if (result != null) MessageStore.updateText(this@GroupChatActivity, item.message_id, result)
                    callback(result)
                }
            },
            onLoadFromStore = { messageId, callback ->
                lifecycleScope.launch {
                    callback(MessageStore.getText(this@GroupChatActivity, messageId))
                }
            },
            onDownloadImage = { payload, callback ->
                lifecycleScope.launch {
                    callback(downloadImage(payload))
                }
            },
        )
        binding.recyclerMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.recyclerMessages.adapter = adapter

        loadMessageHistory()
        binding.buttonSend.setOnClickListener { sendMessage() }
        binding.buttonAttach.setOnClickListener { pickImage.launch("image/*") }
        startPolling()
    }

    override fun onPause() {
        super.onPause()
        adapter.hideAllReceived()
    }

    private fun loadMessageHistory() {
        MessageStore.loadByGroup(this, groupId).forEach { stored ->
            val group = GroupStore.get(this, groupId)
            val senderName = if (!stored.isMine) {
                group?.members?.firstOrNull { it.deviceId == stored.senderDeviceId }?.displayName
            } else null
            adapter.addMessage(ChatMessage(
                messageId = stored.messageId,
                isMine = stored.isMine,
                timestamp = stored.timestamp,
                text = if (stored.isMine) stored.text else null,
                isRevealed = false,
                wasDecrypted = stored.text != null,
                encryptedItem = stored.toMessageItem(),
                senderName = senderName,
                messageType = stored.messageType ?: "text",
            ))
        }
        if (adapter.itemCount > 0)
            binding.recyclerMessages.scrollToPosition(adapter.itemCount - 1)
    }

    private fun sendMessage() {
        val text = binding.editMessage.text.toString().trim()
        if (text.isEmpty()) return
        if (!GroupKeyStore.has(this, groupId)) {
            Toast.makeText(this, "Kein Gruppenkey verfügbar", Toast.LENGTH_SHORT).show()
            return
        }
        binding.editMessage.text.clear()

        val tempId = "pending_${System.currentTimeMillis()}"
        adapter.addMessage(ChatMessage(
            messageId = tempId, text = text, isMine = true,
            timestamp = java.time.Instant.now().toString(), isRevealed = true,
        ))
        binding.recyclerMessages.scrollToPosition(adapter.itemCount - 1)

        lifecycleScope.launch {
            try {
                val groupKey = GroupKeyStore.get(this@GroupChatActivity, groupId)!!
                val (ct, nonce) = SignalCrypto.encrypt(groupKey, text.toByteArray(Charsets.UTF_8))
                val enc = { b: ByteArray -> Base64.encodeToString(b, Base64.NO_WRAP) }
                Api.service.sendMessage(
                    SendRequest(
                        sender_device_id = deviceId,
                        recipient_device_id = "",
                        ciphertext = enc(ct),
                        nonce = enc(nonce),
                        ratchet_pub = "",
                        message_index = 0,
                        prev_send_index = 0,
                        group_id = groupId,
                        message_type = "text",
                    ),
                    token,
                )
                MessageStore.save(this@GroupChatActivity, StoredMessage(
                    messageId = tempId, isMine = true,
                    timestamp = java.time.Instant.now().toString(),
                    text = text, senderDeviceId = deviceId,
                    contactDeviceId = groupId, groupId = groupId,
                    ciphertext = enc(ct), nonce = enc(nonce), ratchetPub = "",
                ))
            } catch (_: Exception) {
                Toast.makeText(this@GroupChatActivity, "Senden fehlgeschlagen", Toast.LENGTH_SHORT).show()
                adapter.removeMessage(tempId)
            }
        }
    }

    private suspend fun sendImage(uri: Uri) {
        if (!GroupKeyStore.has(this, groupId)) {
            Toast.makeText(this, "Kein Gruppenkey verfügbar", Toast.LENGTH_SHORT).show()
            return
        }
        val tempId = "pending_${System.currentTimeMillis()}"

        val compressedBytes = withContext(Dispatchers.IO) {
            val input = contentResolver.openInputStream(uri) ?: return@withContext null
            val bitmap = BitmapFactory.decodeStream(input)
            input.close()
            val scaled = scaleBitmap(bitmap, 1024)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
            out.toByteArray()
        } ?: return

        adapter.addMessage(ChatMessage(
            messageId = tempId, isMine = true,
            timestamp = java.time.Instant.now().toString(),
            isRevealed = true, messageType = "image", imageBytes = compressedBytes,
        ))
        binding.recyclerMessages.scrollToPosition(adapter.itemCount - 1)

        try {
            val fileKey = java.security.SecureRandom().generateSeed(32)
            val (ct, nonce) = SignalCrypto.encrypt(fileKey, compressedBytes)
            val enc = { b: ByteArray -> Base64.encodeToString(b, Base64.NO_WRAP) }

            val uploadResp = withContext(Dispatchers.IO) {
                Api.service.uploadFile((nonce + ct).toOctetStreamBody(), deviceId, token)
            }
            val fileId = uploadResp.body()?.file_id ?: throw Exception("Upload fehlgeschlagen")
            val payload = """{"fk":"${enc(fileKey)}","fid":"$fileId"}"""

            val groupKey = GroupKeyStore.get(this, groupId)!!
            val (msgCt, msgNonce) = SignalCrypto.encrypt(groupKey, payload.toByteArray(Charsets.UTF_8))
            Api.service.sendMessage(
                SendRequest(
                    sender_device_id = deviceId, recipient_device_id = "",
                    ciphertext = enc(msgCt), nonce = enc(msgNonce),
                    ratchet_pub = "", message_index = 0, prev_send_index = 0,
                    group_id = groupId, message_type = "image",
                ),
                token,
            )
            MessageStore.save(this, StoredMessage(
                messageId = tempId, isMine = true,
                timestamp = java.time.Instant.now().toString(),
                text = payload, senderDeviceId = deviceId,
                contactDeviceId = groupId, groupId = groupId,
                ciphertext = enc(msgCt), nonce = enc(msgNonce), ratchetPub = "",
                messageType = "image",
            ))
        } catch (_: Exception) {
            Toast.makeText(this, "Bild senden fehlgeschlagen", Toast.LENGTH_SHORT).show()
            adapter.removeMessage(tempId)
        }
    }

    private fun scaleBitmap(bitmap: Bitmap, maxPx: Int): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        if (w <= maxPx && h <= maxPx) return bitmap
        val ratio = minOf(maxPx.toFloat() / w, maxPx.toFloat() / h)
        return Bitmap.createScaledBitmap(bitmap, (w * ratio).toInt(), (h * ratio).toInt(), true)
    }

    private suspend fun downloadImage(payload: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            @Suppress("UNCHECKED_CAST")
            val json = Gson().fromJson(payload, Map::class.java) as Map<String, String>
            val fileKey = Base64.decode(json["fk"] ?: return@withContext null, Base64.NO_WRAP)
            val fileId = json["fid"] ?: return@withContext null
            val resp = Api.service.downloadFile(fileId, deviceId, token)
            val raw = resp.body()?.bytes() ?: return@withContext null
            val filNonce = raw.copyOfRange(0, 24)
            val filCt = raw.copyOfRange(24, raw.size)
            SignalCrypto.decrypt(fileKey, filCt, filNonce)
        } catch (_: Exception) { null }
    }

    private fun decryptGroupMessage(item: MessageItem): String? {
        if (item.group_id == null) return null
        val groupKey = GroupKeyStore.get(this, item.group_id) ?: return null
        return try {
            val ct = Base64.decode(item.ciphertext, Base64.NO_WRAP)
            val nonce = Base64.decode(item.nonce, Base64.NO_WRAP)
            String(SignalCrypto.decrypt(groupKey, ct, nonce), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    private fun startPolling() {
        lifecycleScope.launch {
            while (isActive) {
                try {
                    val response = Api.service.pendingMessages(deviceId, token)
                    if (response.isSuccessful) {
                        response.body()?.messages?.forEach { item ->
                            // Handle group key distribution
                            if (item.message_type == "group_key" && item.group_id != null) {
                                val decrypted = try {
                                    if (!RatchetStateStore.hasSession(this@GroupChatActivity, item.sender_device_id)) {
                                        val h = item.x3dh_header ?: return@forEach
                                        val dec = { s: String -> Base64.decode(s, Base64.NO_WRAP) }
                                        RatchetManager.initAsReceiver(this@GroupChatActivity, item.sender_device_id,
                                            X3DHHeaderData(dec(h.ikd_pub), dec(h.ek_pub), h.opk_id,
                                                if (h.iks_pub.isNotEmpty()) dec(h.iks_pub) else null))
                                    }
                                    RatchetManager.decrypt(this@GroupChatActivity, item.sender_device_id,
                                        RatchetManager.EncryptedMessage(item.ciphertext, item.nonce,
                                            item.ratchet_pub, item.message_index, item.prev_send_index))
                                } catch (_: Exception) { null }
                                if (decrypted != null) {
                                    GroupKeyStore.save(this@GroupChatActivity, item.group_id,
                                        Base64.decode(decrypted, Base64.NO_WRAP))
                                }
                                Api.service.ackMessage(AckRequest(deviceId, item.message_id), token)
                                return@forEach
                            }

                            // Only handle messages for this group
                            if (item.group_id != groupId) {
                                MessageStore.save(this@GroupChatActivity, item.toGroupStoredMessage())
                                Api.service.ackMessage(AckRequest(deviceId, item.message_id), token)
                                return@forEach
                            }

                            val group = GroupStore.get(this@GroupChatActivity, groupId)
                            val senderName = group?.members?.firstOrNull { it.deviceId == item.sender_device_id }?.displayName

                            MessageStore.save(this@GroupChatActivity, item.toGroupStoredMessage())
                            adapter.addMessage(ChatMessage(
                                messageId = item.message_id, isMine = false,
                                timestamp = item.created_at, isRevealed = false,
                                encryptedItem = item, senderName = senderName,
                                messageType = item.message_type ?: "text",
                            ))
                            binding.recyclerMessages.scrollToPosition(adapter.itemCount - 1)
                            Api.service.ackMessage(AckRequest(deviceId, item.message_id), token)
                        }
                    }
                } catch (_: Exception) {}
                delay(3000)
            }
        }
    }
}

fun MessageItem.toGroupStoredMessage() = StoredMessage(
    messageId = message_id,
    isMine = false,
    timestamp = created_at,
    text = null,
    senderDeviceId = sender_device_id,
    contactDeviceId = group_id ?: sender_device_id,
    groupId = group_id,
    ciphertext = ciphertext,
    nonce = nonce,
    ratchetPub = ratchet_pub,
    messageIndex = message_index,
    prevSendIndex = prev_send_index,
    messageType = message_type,
)
