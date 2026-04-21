package com.tim.chatapp

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.tim.chatapp.databinding.ActivityAddContactBinding
import kotlinx.coroutines.launch

class AddContactActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddContactBinding
    private lateinit var deviceId: String
    private lateinit var token: String

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { scanned ->
            binding.editCode.setText(scanned.trim().uppercase())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddContactBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceId = DeviceStorage.getDeviceId(this) ?: ""
        token = DeviceStorage.getToken(this) ?: ""

        binding.buttonBack.setOnClickListener { finish() }

        binding.buttonGenerateCode.setOnClickListener { generateCode() }

        binding.buttonScanQr.setOnClickListener {
            val options = ScanOptions().apply {
                setPrompt("QR-Code scannen")
                setBeepEnabled(false)
                setOrientationLocked(true)
            }
            scanLauncher.launch(options)
        }

        binding.buttonAcceptCode.setOnClickListener {
            val code = binding.editCode.text.toString().trim().uppercase()
            if (code.length != 8) {
                Toast.makeText(this, "Code muss 8 Zeichen lang sein", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            acceptCode(code)
        }
    }

    private fun generateCode() {
        binding.buttonGenerateCode.isClickable = false
        lifecycleScope.launch {
            try {
                val resp = Api.service.createInvite(deviceId, token)
                if (!resp.isSuccessful || resp.body() == null) {
                    Toast.makeText(this@AddContactActivity, "Fehler beim Erstellen", Toast.LENGTH_SHORT).show()
                    binding.buttonGenerateCode.isClickable = true
                    return@launch
                }
                val code = resp.body()!!.code
                binding.textMyCode.text = code
                binding.layoutMyCode.visibility = android.view.View.VISIBLE

                try {
                    val encoder = BarcodeEncoder()
                    val bitmap: Bitmap = encoder.encodeBitmap(code, BarcodeFormat.QR_CODE, 400, 400)
                    binding.imageQrCode.setImageBitmap(bitmap)
                } catch (_: Exception) {}
            } catch (_: Exception) {
                Toast.makeText(this@AddContactActivity, "Server nicht erreichbar", Toast.LENGTH_SHORT).show()
                binding.buttonGenerateCode.isClickable = true
            }
        }
    }

    private fun acceptCode(code: String) {
        binding.buttonAcceptCode.isClickable = false
        lifecycleScope.launch {
            try {
                val resp = Api.service.acceptInvite(AcceptInviteRequest(code), deviceId, token)
                if (!resp.isSuccessful || resp.body() == null) {
                    Toast.makeText(this@AddContactActivity, "Ungültiger oder abgelaufener Code", Toast.LENGTH_SHORT).show()
                    binding.buttonAcceptCode.isClickable = true
                    return@launch
                }
                val body = resp.body()!!
                ContactStore.addOrUpdate(
                    this@AddContactActivity,
                    Contact(deviceId = body.contact_device_id, displayName = body.display_name)
                )
                Toast.makeText(this@AddContactActivity, "${body.display_name} hinzugefügt", Toast.LENGTH_SHORT).show()
                finish()
            } catch (_: Exception) {
                Toast.makeText(this@AddContactActivity, "Server nicht erreichbar", Toast.LENGTH_SHORT).show()
                binding.buttonAcceptCode.isClickable = true
            }
        }
    }
}
