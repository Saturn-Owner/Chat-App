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
import com.tim.chatapp.databinding.ActivityChatBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var adapter: MessageAdapter
    private lateinit var deviceId: String
    private lateinit var recipientId: String
    private lateinit var token: String

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) lifecycleScope.launch { sendImage(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
        )
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceId = DeviceStorage.getDeviceId(this) ?: ""
        recipientId = intent.getStringExtra("contact_id") ?: ""
        token = DeviceStorage.getToken(this) ?: ""
        val contactName = intent.getStringExtra("contact_name") ?: "Kontakt"
        binding.textContactName.text = contactName
        binding.textAvatar.text = contactName.firstOrNull()?.uppercase() ?: "?"
        binding.buttonBack.setOnClickListener { finish() }

        adapter = MessageAdapter(
            onDecrypt = { contactId, item, callback ->
                lifecycleScope.launch {
                    val plaintext = decryptIncoming(item)
                    if (plaintext != null) {
                        MessageStore.updateText(this@ChatActivity, item.message_id, plaintext)
                    }
                    callback(plaintext)
                }
            },
            onLoadFromStore = { messageId, callback ->
                lifecycleScope.launch {
                    callback(MessageStore.getText(this@ChatActivity, messageId))
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

        binding.buttonSend.setOnClickListener { sendMessage() }
        binding.buttonAttach.setOnClickListener { pickImage.launch("image/*") }

        loadMessageHistory()
        refreshTokenIfNeeded()
        checkSPKRotation()
        startPolling()
        startPinging()
    }

    override fun onPause() {
        super.onPause()
        adapter.hideAllReceived()
    }

    // ── History ──────────────────────────────────────────────────────────────

    private fun loadMessageHistory() {
        MessageStore.loadByContact(this, recipientId).forEach { stored ->
            adapter.addMessage(ChatMessage(
                messageId = stored.messageId,
                isMine = stored.isMine,
                timestamp = stored.timestamp,
                text = if (stored.isMine) stored.text else null,
                isRevealed = false,
                wasDecrypted = stored.text != null,
                encryptedItem = stored.toMessageItem(),
                messageType = stored.messageType ?: "text",
            ))
        }
        if (adapter.itemCount > 0)
            binding.recyclerMessages.scrollToPosition(adapter.itemCount - 1)
    }

    // ── Token + Peer Refresh ─────────────────────────────────────────────────

    private fun refreshTokenIfNeeded() {
        lifecycleScope.launch {
            try {
                val resp = Api.service.refreshToken(deviceId, token)
                if (resp.isSuccessful) {
                    token = resp.body()?.token ?: return@launch
                    DeviceStorage.updateToken(this@ChatActivity, token)
                }
            } catch (_: Exception) {}
        }
    }

    private fun checkSPKRotation() {
        if (!PreKeyStore.needsSPKRotation(this)) return
        lifecycleScope.launch {
            try {
                val rotated = PreKeyStore.rotateSPK(this@ChatActivity)
                val enc = { b: ByteArray -> Base64.encodeToString(b, Base64.NO_WRAP) }
                Api.service.rotateSPK(
                    RotateSPKRequest(
                        device_id = deviceId,
                        spk = SPKData(id = rotated.spkId, pub = enc(rotated.spkPub), sig = enc(rotated.spkSig)),
                    ),
                    token,
                )
            } catch (_: Exception) {}
        }
    }

    // ── Send ─────────────────────────────────────────────────────────────────

    private fun sendMessage() {
        val text = binding.editMessage.text.toString().trim()
        if (text.isEmpty() || recipientId.isEmpty()) return
        binding.editMessage.text.clear()

        val tempId = "pending_${System.currentTimeMillis()}"
        adapter.addMessage(ChatMessage(
            messageId = tempId,
            text = text,
            isMine = true,
            timestamp = java.time.Instant.now().toString(),
            isRevealed = true,
        ))
        binding.recyclerMessages.scrollToPosition(adapter.itemCount - 1)

        lifecycleScope.launch {
            try {
                val hasSession = RatchetStateStore.hasSession(this@ChatActivity, recipientId)
                var x3dhHeader: X3DHHeader? = null

                if (!hasSession) {
                    val bundleResp = Api.service.getPreKeyBundle(recipientId, deviceId, token)
                    if (!bundleResp.isSuccessful || bundleResp.body() == null) {
                        Toast.makeText(this@ChatActivity, "Empfänger noch nicht registriert", Toast.LENGTH_SHORT).show()
                        adapter.removeMessage(tempId)
                        return@launch
                    }
                    val b = bundleResp.body()!!
                    val dec = { s: String -> Base64.decode(s, Base64.NO_WRAP) }
                    val bundle = RatchetManager.RecipientBundle(
                        iksPub = dec(b.iks_pub),
                        ikdPub = dec(b.ikd_pub),
                        spkId = b.spk.id,
                        spkPub = dec(b.spk.pub),
                        spkSig = dec(b.spk.sig),
                        opkId = b.opk?.id ?: -1,
                        opkPub = b.opk?.let { dec(it.pub) },
                    )
                    val ekPub = RatchetManager.initAsSender(this@ChatActivity, recipientId, bundle)
                    val enc = { by: ByteArray -> Base64.encodeToString(by, Base64.NO_WRAP) }
                    x3dhHeader = X3DHHeader(
                        ikd_pub = enc(PreKeyStore.getIKDPub(this@ChatActivity)),
                        ek_pub = enc(ekPub),
                        opk_id = bundle.opkId,
                        iks_pub = enc(PreKeyStore.getIKSPublicKeyBytes()),
                    )
                }

                val encrypted = RatchetManager.encrypt(this@ChatActivity, recipientId, text)
                Api.service.sendMessage(
                    SendRequest(
                        sender_device_id = deviceId,
                        recipient_device_id = recipientId,
                        ciphertext = encrypted.ciphertext,
                        nonce = encrypted.nonce,
                        ratchet_pub = encrypted.ratchetPub,
                        message_index = encrypted.messageIndex,
                        prev_send_index = encrypted.prevSendIndex,
                        x3dh_header = x3dhHeader,
                    ),
                    token,
                )

                MessageStore.save(this@ChatActivity, StoredMessage(
                    messageId = tempId,
                    isMine = true,
                    timestamp = java.time.Instant.now().toString(),
                    text = text,
                    senderDeviceId = deviceId,
                    contactDeviceId = recipientId,
                    ciphertext = null,
                    nonce = null,
                    ratchetPub = null,
                ))

                checkAndReplenishMyOPKs()
            } catch (e: Exception) {
                Toast.makeText(this@ChatActivity, "Senden fehlgeschlagen", Toast.LENGTH_SHORT).show()
                adapter.removeMessage(tempId)
            }
        }
    }

    private suspend fun sendImage(uri: Uri) {
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
            isRevealed = true, messageType = "image",
            imageBytes = compressedBytes,
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

            val hasSession = RatchetStateStore.hasSession(this, recipientId)
            var x3dhHeader: X3DHHeader? = null
            if (!hasSession) {
                val bundleResp = Api.service.getPreKeyBundle(recipientId, deviceId, token)
                if (!bundleResp.isSuccessful) throw Exception("Empfänger nicht registriert")
                val b = bundleResp.body()!!
                val dec = { s: String -> Base64.decode(s, Base64.NO_WRAP) }
                val bundle = RatchetManager.RecipientBundle(
                    iksPub = dec(b.iks_pub), ikdPub = dec(b.ikd_pub),
                    spkId = b.spk.id, spkPub = dec(b.spk.pub), spkSig = dec(b.spk.sig),
                    opkId = b.opk?.id ?: -1, opkPub = b.opk?.let { dec(it.pub) },
                )
                val ekPub = RatchetManager.initAsSender(this, recipientId, bundle)
                x3dhHeader = X3DHHeader(
                    ikd_pub = enc(PreKeyStore.getIKDPub(this)),
                    ek_pub = enc(ekPub),
                    opk_id = bundle.opkId,
                    iks_pub = enc(PreKeyStore.getIKSPublicKeyBytes()),
                )
            }

            val encrypted = RatchetManager.encrypt(this, recipientId, payload)
            Api.service.sendMessage(
                SendRequest(
                    sender_device_id = deviceId, recipient_device_id = recipientId,
                    ciphertext = encrypted.ciphertext, nonce = encrypted.nonce,
                    ratchet_pub = encrypted.ratchetPub,
                    message_index = encrypted.messageIndex, prev_send_index = encrypted.prevSendIndex,
                    x3dh_header = x3dhHeader, message_type = "image",
                ),
                token,
            )
            MessageStore.save(this, StoredMessage(
                messageId = tempId, isMine = true,
                timestamp = java.time.Instant.now().toString(),
                text = payload, senderDeviceId = deviceId, contactDeviceId = recipientId,
                ciphertext = null, nonce = null, ratchetPub = null, messageType = "image",
            ))
            checkAndReplenishMyOPKs()
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
            val nonce = raw.copyOfRange(0, 24)
            val ct = raw.copyOfRange(24, raw.size)
            SignalCrypto.decrypt(fileKey, ct, nonce)
        } catch (_: Exception) { null }
    }

    // ── Receive ──────────────────────────────────────────────────────────────

    private fun startPolling() {
        lifecycleScope.launch {
            while (isActive) {
                try {
                    val response = Api.service.pendingMessages(deviceId, token)
                    if (response.isSuccessful) {
                        response.body()?.messages?.forEach { item ->
                            // Group key distribution — store silently, don't show in chat
                            if (item.message_type == "group_key" && item.group_id != null) {
                                val keyPlaintext = decryptIncoming(item)
                                if (keyPlaintext != null) {
                                    val keyBytes = android.util.Base64.decode(keyPlaintext, android.util.Base64.NO_WRAP)
                                    GroupKeyStore.save(this@ChatActivity, item.group_id, keyBytes)
                                    // Fetch and store group info
                                    try {
                                        val gr = Api.service.getGroup(item.group_id, deviceId, token)
                                        gr.body()?.let { g ->
                                            GroupStore.addOrUpdate(this@ChatActivity, Group(
                                                groupId = g.group_id, name = g.name, adminId = g.admin_id,
                                                members = g.members.map { GroupMember(it.device_id, it.display_name) }
                                            ))
                                        }
                                    } catch (_: Exception) {}
                                }
                                Api.service.ackMessage(AckRequest(device_id = deviceId, message_id = item.message_id), token)
                                return@forEach
                            }

                            // Only show messages for current contact
                            if (item.sender_device_id != recipientId) {
                                MessageStore.save(this@ChatActivity, item.toStoredMessage())
                                Api.service.ackMessage(AckRequest(device_id = deviceId, message_id = item.message_id), token)
                                return@forEach
                            }

                            MessageStore.save(this@ChatActivity, item.toStoredMessage())
                            adapter.addMessage(ChatMessage(
                                messageId = item.message_id,
                                isMine = false,
                                timestamp = item.created_at,
                                isRevealed = false,
                                encryptedItem = item,
                                messageType = item.message_type ?: "text",
                            ))
                            binding.recyclerMessages.scrollToPosition(adapter.itemCount - 1)
                            Api.service.ackMessage(AckRequest(device_id = deviceId, message_id = item.message_id), token)
                        }
                    }
                } catch (_: Exception) {}
                delay(3000)
            }
        }
    }

    fun decryptIncoming(item: MessageItem): String? {
        val contactId = item.sender_device_id
        return try {
            if (!RatchetStateStore.hasSession(this, contactId)) {
                val h = item.x3dh_header ?: return null
                val dec = { s: String -> Base64.decode(s, Base64.NO_WRAP) }
                RatchetManager.initAsReceiver(
                    this, contactId,
                    X3DHHeaderData(
                        ikdPubA = dec(h.ikd_pub),
                        ekPubA = dec(h.ek_pub),
                        opkId = h.opk_id,
                        iksPubA = if (h.iks_pub.isNotEmpty()) dec(h.iks_pub) else null,
                    ),
                )
            }
            RatchetManager.decrypt(
                this, contactId,
                RatchetManager.EncryptedMessage(
                    ciphertext = item.ciphertext,
                    nonce = item.nonce,
                    ratchetPub = item.ratchet_pub,
                    messageIndex = item.message_index,
                    prevSendIndex = item.prev_send_index,
                ),
            )
        } catch (_: Exception) {
            null
        }
    }

    // ── Ping + Peer ───────────────────────────────────────────────────────────

    private fun startPinging() {
        lifecycleScope.launch {
            while (isActive) {
                try {
                    Api.service.ping(PingRequest(deviceId), token)

                    if (recipientId.isNotEmpty()) {
                        val resp = Api.service.onlineStatus(recipientId, deviceId, token)
                        val online = resp.body()?.online == true
                        binding.textOnlineStatus.text = if (online) "online" else "offline"
                        binding.textOnlineStatus.setTextColor(
                            if (online) 0xFF00CEC9.toInt() else 0xFF505068.toInt()
                        )
                    }
                } catch (_: Exception) {}
                delay(10000)
            }
        }
    }

    private suspend fun checkAndReplenishMyOPKs() {
        try {
            val countResp = Api.service.getOPKCount(deviceId, deviceId, token)
            val count = countResp.body()?.count ?: return
            if (count < 5) {
                val newOpks = PreKeyStore.generateNewOPKs(this@ChatActivity, 20)
                val enc = { b: ByteArray -> Base64.encodeToString(b, Base64.NO_WRAP) }
                Api.service.replenishOPKs(
                    deviceId,
                    ReplenishOPKsRequest(
                        device_id = deviceId,
                        opks = newOpks.map { (id, pub) -> OPKItem(id = id, pub = enc(pub)) },
                    ),
                    token,
                )
            }
        } catch (_: Exception) {}
    }
}

// ── Extensions ────────────────────────────────────────────────────────────────

fun MessageItem.toStoredMessage() = StoredMessage(
    messageId = message_id,
    isMine = false,
    timestamp = created_at,
    text = null,
    senderDeviceId = sender_device_id,
    contactDeviceId = sender_device_id,
    ciphertext = ciphertext,
    nonce = nonce,
    ratchetPub = ratchet_pub,
    messageIndex = message_index,
    prevSendIndex = prev_send_index,
    x3dhIkdPub = x3dh_header?.ikd_pub,
    x3dhEkPub = x3dh_header?.ek_pub,
    x3dhIksPub = x3dh_header?.iks_pub,
    x3dhOpkId = x3dh_header?.opk_id ?: -1,
    messageType = message_type,
)

fun StoredMessage.toMessageItem(): MessageItem? {
    if (ciphertext == null || nonce == null || ratchetPub == null) return null
    return MessageItem(
        message_id = messageId,
        sender_device_id = senderDeviceId ?: return null,
        ciphertext = ciphertext,
        nonce = nonce,
        ratchet_pub = ratchetPub,
        message_index = messageIndex,
        prev_send_index = prevSendIndex,
        created_at = timestamp,
        x3dh_header = if (x3dhIkdPub != null && x3dhEkPub != null)
            X3DHHeader(
                ikd_pub = x3dhIkdPub,
                ek_pub = x3dhEkPub,
                opk_id = x3dhOpkId,
                iks_pub = x3dhIksPub ?: "",
            ) else null,
        message_type = messageType,
    )
}
