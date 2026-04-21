package com.tim.chatapp

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class GroupMember(val deviceId: String, val displayName: String)

data class Group(
    val groupId: String,
    val name: String,
    val adminId: String,
    val members: List<GroupMember>,
)

object GroupStore {

    private val gson = Gson()
    private val listType = object : TypeToken<MutableList<Group>>() {}.type

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context, "group_store",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun loadAll(context: Context): List<Group> {
        val json = prefs(context).getString("groups", null) ?: return emptyList()
        return runCatching { gson.fromJson<MutableList<Group>>(json, listType) }
            .getOrDefault(mutableListOf())
    }

    fun saveAll(context: Context, groups: List<Group>) {
        prefs(context).edit().putString("groups", gson.toJson(groups)).apply()
    }

    fun addOrUpdate(context: Context, group: Group) {
        val list = loadAll(context).toMutableList()
        val idx = list.indexOfFirst { it.groupId == group.groupId }
        if (idx >= 0) list[idx] = group else list.add(group)
        saveAll(context, list)
    }

    fun get(context: Context, groupId: String): Group? =
        loadAll(context).firstOrNull { it.groupId == groupId }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
