package com.tim.chatapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.tim.chatapp.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val displayName = DeviceStorage.getDisplayName(this) ?: "–"
        val deviceId = DeviceStorage.getDeviceId(this) ?: "–"

        binding.textDisplayName.text = displayName
        binding.textDeviceId.text = deviceId.take(8) + "..."
        binding.textAvatarLarge.text = displayName.firstOrNull()?.uppercase() ?: "?"

        binding.buttonBack.setOnClickListener { finish() }

        binding.buttonSafetyNumber.setOnClickListener {
            val contactId = intent.getStringExtra("contact_id") ?: ""
            val myIksPub = PreKeyStore.getIKSPublicKeyBytes()
            val theirIksPubB64 = if (contactId.isNotEmpty()) DeviceStorage.getPeerIksPub(this, contactId) else null
            if (theirIksPubB64 == null) {
                AlertDialog.Builder(this)
                    .setTitle("Nicht verfügbar")
                    .setMessage("Sicherheitsnummer steht erst nach dem ersten Nachrichtenaustausch zur Verfügung.")
                    .setPositiveButton("OK", null)
                    .show()
                return@setOnClickListener
            }
            val theirIksPub = android.util.Base64.decode(theirIksPubB64, android.util.Base64.NO_WRAP)
            val number = PreKeyStore.computeSafetyNumber(myIksPub, theirIksPub)
            val formatted = buildString {
                for (i in number.indices) {
                    if (i > 0 && i % 5 == 0) append(" ")
                    if (i > 0 && i % 20 == 0) append("\n")
                    append(number[i])
                }
            }
            AlertDialog.Builder(this)
                .setTitle("Sicherheitsnummer")
                .setMessage("Vergleiche diese Zahl mit deinem Kontakt – z.B. per Telefonanruf.\n\n$formatted\n\nStimmen die Zahlen überein, ist die Verbindung sicher.")
                .setPositiveButton("Verstanden", null)
                .show()
        }

        binding.buttonLogout.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Abmelden?")
                .setMessage("Alle lokalen Daten werden gelöscht. Du brauchst einen neuen Aktivierungscode.")
                .setPositiveButton("Abmelden") { _, _ -> wipeAndRestart() }
                .setNegativeButton("Abbrechen", null)
                .show()
        }

        binding.buttonDeleteAccount.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Konto unwiderruflich löschen?")
                .setMessage("Alle Nachrichten, Kontakte, Schlüssel und Daten werden sofort und unwiderruflich gelöscht. Diese Aktion kann nicht rückgängig gemacht werden.")
                .setPositiveButton("Alles löschen") { _, _ -> wipeAndRestart() }
                .setNegativeButton("Abbrechen", null)
                .show()
        }
    }

    private fun wipeAndRestart() {
        // Clear in-memory via each store
        DeviceStorage.clear(this)
        PreKeyStore.deleteAllKeys(this)
        ContactStore.clear(this)
        GroupStore.clear(this)
        GroupKeyStore.clear(this)
        MessageStore.clear(this)
        RatchetStateStore.deleteAll(this)

        // Delete the actual SharedPreferences files so nothing lingers
        listOf(
            "device_prefs",
            "silentlink_prekeys",
            "contact_store",
            "group_store",
            "group_key_store",
            "message_history",
            "ratchet_sessions",
        ).forEach { deleteSharedPreferences(it) }

        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
    }
}
