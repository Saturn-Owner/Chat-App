package com.tim.chatapp

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tim.chatapp.databinding.ActivityNameBinding
import kotlinx.coroutines.launch

class NameActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNameBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val code = intent.getStringExtra("code") ?: ""

        binding.editName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val filled = s?.trim()?.isNotEmpty() == true
                binding.buttonWeiter.isEnabled = filled
                binding.buttonWeiter.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (filled) 0xFFFFFFFF.toInt() else 0xFF444444.toInt()
                )
                binding.buttonWeiter.setTextColor(
                    if (filled) 0xFF0D0D0D.toInt() else 0xFF888888.toInt()
                )
            }
        })

        binding.buttonWeiter.setOnClickListener {
            val name = binding.editName.text.toString().trim()
            val deviceId = android.provider.Settings.Secure.getString(
                contentResolver, android.provider.Settings.Secure.ANDROID_ID
            )

            binding.buttonWeiter.isEnabled = false
            binding.buttonWeiter.text = "Wird geprüft..."

            lifecycleScope.launch {
                try {
                    val bundle = PreKeyStore.generateAllKeys(this@NameActivity)

                    val redeemResp = Api.service.redeemCode(
                        RedeemRequest(code = code, device_id = deviceId, display_name = name)
                    )
                    if (!redeemResp.isSuccessful) {
                        showError("Ungültiger Code")
                        return@launch
                    }
                    val body = redeemResp.body()!!
                    val token = body.token

                    val enc = { b: ByteArray -> Base64.encodeToString(b, Base64.NO_WRAP) }
                    val preKeyResp = Api.service.uploadPreKeys(
                        UploadPreKeysRequest(
                            device_id = deviceId,
                            iks_pub = enc(bundle.iksPub),
                            ikd_pub = enc(bundle.ikdPub),
                            spk = SPKData(id = bundle.spkId, pub = enc(bundle.spkPub), sig = enc(bundle.spkSig)),
                            opks = bundle.opks.map { (id, pub) -> OPKItem(id = id, pub = enc(pub)) },
                        ),
                        token,
                    )
                    if (!preKeyResp.isSuccessful) {
                        showError("PreKey-Upload fehlgeschlagen")
                        return@launch
                    }

                    DeviceStorage.save(
                        context = this@NameActivity,
                        deviceId = deviceId,
                        token = token,
                        displayName = name,
                    )
                    startActivity(Intent(this@NameActivity, ContactsActivity::class.java))
                    finish()
                } catch (e: Exception) {
                    showError("Server nicht erreichbar")
                }
            }
        }
    }

    private fun showError(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        binding.buttonWeiter.isEnabled = true
        binding.buttonWeiter.text = "Weiter"
    }
}
