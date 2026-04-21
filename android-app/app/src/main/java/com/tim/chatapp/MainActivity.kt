package com.tim.chatapp

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tim.chatapp.databinding.ActivityMainBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val displayBoxes = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DeviceStorage.isRegistered(this)) {
            val lock = android.view.View(this).apply { setBackgroundColor(0xFF0D0D0D.toInt()) }
            setContentView(lock)
            showBiometricPrompt()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val groups = listOf(binding.group1, binding.group2, binding.group3, binding.group4)
        groups.forEach { group ->
            repeat(4) {
                val box = createDisplayBox()
                displayBoxes.add(box)
                group.addView(box)
            }
        }

        binding.codeContainer.setOnClickListener { showKeyboard() }

        binding.hiddenInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString()?.uppercase() ?: ""
                displayBoxes.forEachIndexed { i, box ->
                    box.text = if (i < text.length) text[i].toString() else ""
                    box.setBackgroundResource(
                        if (i == text.length) R.drawable.box_active else R.drawable.box_background
                    )
                }
                val complete = text.length == 16
                binding.buttonWeiter.isEnabled = complete
                binding.buttonWeiter.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (complete) 0xFFFFFFFF.toInt() else 0xFF444444.toInt()
                )
                binding.buttonWeiter.setTextColor(
                    if (complete) 0xFF0D0D0D.toInt() else 0xFF888888.toInt()
                )
            }
        })

        binding.root.postDelayed({ showKeyboard() }, 300)

        binding.buttonWeiter.setOnClickListener {
            val raw = binding.hiddenInput.text.toString()
            val code = raw.chunked(4).joinToString("-")
            if (raw.length == 16) {
                binding.buttonWeiter.isEnabled = false
                binding.buttonWeiter.text = "Code wird geprüft..."
                lifecycleScope.launch {
                    try {
                        val response = Api.service.checkCode(CheckRequest(code))
                        if (response.isSuccessful && response.body()?.valid == true) {
                            val intent = android.content.Intent(this@MainActivity, NameActivity::class.java)
                            intent.putExtra("code", code)
                            startActivity(intent)
                            finish()
                        } else {
                            Toast.makeText(this@MainActivity, "Ungültiger Code", Toast.LENGTH_LONG).show()
                            binding.buttonWeiter.isEnabled = true
                            binding.buttonWeiter.text = "Weiter"
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Server nicht erreichbar", Toast.LENGTH_LONG).show()
                        binding.buttonWeiter.isEnabled = true
                    }
                }
            }
        }
    }

    private fun showKeyboard() {
        binding.hiddenInput.requestFocus()
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(binding.hiddenInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun showBiometricPrompt() {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    startActivity(android.content.Intent(this@MainActivity, ContactsActivity::class.java))
                    finish()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Nutzer hat abgebrochen — App bleibt auf Sperrscreen
                }
            }
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("SilentLink")
            .setSubtitle("Identität bestätigen")
            .setAllowedAuthenticators(authenticators)
            .build()

        prompt.authenticate(info)
    }

    private suspend fun showAccessGranted(code: String) {
        binding.overlay.visibility = View.VISIBLE
        binding.overlay.alpha = 0f
        binding.overlay.animate().alpha(1f).setDuration(200).start()

        val lines = listOf(
            "> VERBINDUNG AUFGEBAUT",
            "> CODE WIRD GEPRÜFT...",
            "> IDENTITÄT BESTÄTIGT",
            "> ACCESS GRANTED",
        )
        for (line in lines) {
            typeText(line)
            delay(150)
        }
        delay(200)

        val intent = android.content.Intent(this@MainActivity, NameActivity::class.java)
        intent.putExtra("code", code)
        startActivity(intent)
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        finish()
    }

    private suspend fun showAccessDenied() {
        binding.overlay.visibility = View.VISIBLE
        binding.overlay.alpha = 0f
        binding.overlay.animate().alpha(1f).setDuration(200).start()

        val lines = listOf(
            "> CODE WIRD GEPRÜFT...",
            "> FEHLER: UNGÜLTIGER CODE",
            "> ZUGRIFF VERWEIGERT",
        )
        for (line in lines) {
            typeText(line)
            delay(150)
        }
        binding.overlayText.setTextColor(0xFFFF3333.toInt())
        delay(500)

        binding.overlay.animate().alpha(0f).setDuration(300).withEndAction {
            binding.overlay.visibility = View.GONE
            binding.overlayText.setTextColor(0xFF00FF41.toInt())
            binding.overlayText.text = ""
            binding.buttonWeiter.isEnabled = true
        }.start()
    }

    private suspend fun typeText(line: String) {
        val current = binding.overlayText.text.toString()
        val prefix = if (current.isEmpty()) "" else "$current\n"
        for (i in line.indices) {
            binding.overlayText.text = prefix + line.substring(0, i + 1)
            delay(8)
        }
    }

    private fun createDisplayBox(): TextView {
        val size = resources.getDimensionPixelSize(R.dimen.box_size)
        val margin = resources.getDimensionPixelSize(R.dimen.box_margin)
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).also {
                it.setMargins(margin, 0, margin, 0)
            }
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundResource(R.drawable.box_background)
        }
    }
}
