package com.tim.chatapp

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class StoredMessage(
    val messageId: String,
    val isMine: Boolean,
    val timestamp: String,
    val text: String?,           // null = noch nicht entschlüsselt
    val senderDeviceId: String?,
    val contactDeviceId: String = "",  // der Gesprächspartner (recipient wenn isMine, sender wenn nicht)
    val groupId: String? = null,       // gesetzt wenn Gruppennachricht
    val senderName: String? = null,    // Anzeigename des Senders (für Gruppen)
    // Rohdaten für spätere Entschlüsselung (nur bei empfangenen Nachrichten)
    val ciphertext: String?,
    val nonce: String?,
    val ratchetPub: String?,
    val messageIndex: Int = 0,
    val prevSendIndex: Int = 0,
    val x3dhIkdPub: String? = null,
    val x3dhEkPub: String? = null,
    val x3dhIksPub: String? = null,
    val x3dhOpkId: Int = -1,
    val messageType: String = "text",
)

object MessageStore {

    private val gson = Gson()
    private val listType = object : TypeToken<MutableList<StoredMessage>>() {}.type

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context, "message_history",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun loadAll(context: Context): List<StoredMessage> {
        val json = prefs(context).getString("msgs", null) ?: return emptyList()
        return runCatching { gson.fromJson<MutableList<StoredMessage>>(json, listType) }
            .getOrDefault(mutableListOf())
    }

    fun save(context: Context, msg: StoredMessage) {
        val list = loadAll(context).toMutableList()
        val idx = list.indexOfFirst { it.messageId == msg.messageId }
        if (idx >= 0) list[idx] = msg else list.add(msg)
        prefs(context).edit().putString("msgs", gson.toJson(list)).apply()
    }

    fun updateText(context: Context, messageId: String, text: String) {
        val list = loadAll(context).toMutableList()
        val idx = list.indexOfFirst { it.messageId == messageId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(text = text)
            prefs(context).edit().putString("msgs", gson.toJson(list)).apply()
        }
    }

    fun getText(context: Context, messageId: String): String? =
        loadAll(context).firstOrNull { it.messageId == messageId }?.text

    fun loadByContact(context: Context, contactDeviceId: String): List<StoredMessage> =
        loadAll(context).filter { it.contactDeviceId == contactDeviceId }

    fun loadByGroup(context: Context, groupId: String): List<StoredMessage> =
        loadAll(context).filter { it.groupId == groupId }

    fun getEncryptedItem(context: Context, messageId: String): StoredMessage? =
        loadAll(context).firstOrNull { it.messageId == messageId && it.ciphertext != null }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
