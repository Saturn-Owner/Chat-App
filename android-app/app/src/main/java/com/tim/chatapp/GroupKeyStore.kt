package com.tim.chatapp

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object GroupKeyStore {

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context, "group_key_store",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun save(context: Context, groupId: String, keyBytes: ByteArray) {
        prefs(context).edit()
            .putString("gk_$groupId", Base64.encodeToString(keyBytes, Base64.NO_WRAP))
            .apply()
    }

    fun get(context: Context, groupId: String): ByteArray? {
        val b64 = prefs(context).getString("gk_$groupId", null) ?: return null
        return Base64.decode(b64, Base64.NO_WRAP)
    }

    fun has(context: Context, groupId: String): Boolean =
        prefs(context).contains("gk_$groupId")

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
