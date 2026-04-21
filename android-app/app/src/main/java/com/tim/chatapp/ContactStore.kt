package com.tim.chatapp

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class Contact(
    val deviceId: String,
    val displayName: String,
    val iksPubB64: String? = null,
)

object ContactStore {

    private val gson = Gson()
    private val listType = object : TypeToken<MutableList<Contact>>() {}.type

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context, "contact_store",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun loadAll(context: Context): List<Contact> {
        val json = prefs(context).getString("contacts", null) ?: return emptyList()
        return runCatching { gson.fromJson<MutableList<Contact>>(json, listType) }
            .getOrDefault(mutableListOf())
    }

    fun saveAll(context: Context, contacts: List<Contact>) {
        prefs(context).edit().putString("contacts", gson.toJson(contacts)).apply()
    }

    fun addOrUpdate(context: Context, contact: Contact) {
        val list = loadAll(context).toMutableList()
        val idx = list.indexOfFirst { it.deviceId == contact.deviceId }
        if (idx >= 0) list[idx] = contact else list.add(contact)
        saveAll(context, list)
    }

    fun get(context: Context, deviceId: String): Contact? =
        loadAll(context).firstOrNull { it.deviceId == deviceId }

    fun updateIksPub(context: Context, deviceId: String, iksPubB64: String) {
        val contact = get(context, deviceId) ?: return
        addOrUpdate(context, contact.copy(iksPubB64 = iksPubB64))
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
