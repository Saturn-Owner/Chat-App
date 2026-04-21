package com.tim.chatapp

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoManager {
    private const val KEY_ALIAS = "silentlink_rsa_key"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val RSA_TRANSFORM = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"

    fun ensureKeyPair() {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (ks.containsAlias(KEY_ALIAS)) return
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE)
        kpg.initialize(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                .build()
        )
        kpg.generateKeyPair()
    }

    fun getPublicKeyBase64(): String {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return Base64.getEncoder().encodeToString(ks.getCertificate(KEY_ALIAS).publicKey.encoded)
    }

    fun encrypt(plaintext: String, recipientPublicKeyBase64: String): String {
        val aesKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }

        val aesCipher = Cipher.getInstance("AES/GCM/NoPadding")
        aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        val aesCiphertext = aesCipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(recipientPublicKeyBase64)))
        val rsaCipher = Cipher.getInstance(RSA_TRANSFORM)
        rsaCipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encryptedKey = rsaCipher.doFinal(aesKey.encoded)

        val enc = Base64.getEncoder()
        return "${enc.encodeToString(encryptedKey)}:${enc.encodeToString(iv)}:${enc.encodeToString(aesCiphertext)}"
    }

    fun deleteKeyPair() {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
    }

    fun decrypt(combined: String): String {
        val parts = combined.split(":")
        if (parts.size != 3) return combined

        val dec = Base64.getDecoder()
        val encryptedKey = dec.decode(parts[0])
        val iv = dec.decode(parts[1])
        val aesCiphertext = dec.decode(parts[2])

        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val rsaCipher = Cipher.getInstance(RSA_TRANSFORM)
        rsaCipher.init(Cipher.DECRYPT_MODE, ks.getKey(KEY_ALIAS, null))
        val aesKey = SecretKeySpec(rsaCipher.doFinal(encryptedKey), "AES")

        val aesCipher = Cipher.getInstance("AES/GCM/NoPadding")
        aesCipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        return String(aesCipher.doFinal(aesCiphertext), Charsets.UTF_8)
    }
}
