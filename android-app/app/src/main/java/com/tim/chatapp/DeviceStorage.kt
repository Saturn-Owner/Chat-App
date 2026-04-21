package com.tim.chatapp

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object DeviceStorage {
    private const val FILE = "device_prefs"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_TOKEN = "token"
    private const val KEY_DISPLAY_NAME = "display_name"

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        FILE,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun save(context: Context, deviceId: String, token: String, displayName: String) {
        prefs(context).edit()
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_TOKEN, token)
            .putString(KEY_DISPLAY_NAME, displayName)
            .apply()
    }

    fun getDeviceId(context: Context) = prefs(context).getString(KEY_DEVICE_ID, null)
    fun getToken(context: Context) = prefs(context).getString(KEY_TOKEN, null)
    fun getDisplayName(context: Context) = prefs(context).getString(KEY_DISPLAY_NAME, null)

    fun updateToken(context: Context, newToken: String) {
        prefs(context).edit().putString(KEY_TOKEN, newToken).apply()
    }

    fun savePeerIksPub(context: Context, contactId: String, iksPubB64: String) {
        prefs(context).edit().putString("peer_iks_$contactId", iksPubB64).apply()
    }

    fun getPeerIksPub(context: Context, contactId: String): String? =
        prefs(context).getString("peer_iks_$contactId", null)

    fun isRegistered(context: Context) = getToken(context) != null

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
