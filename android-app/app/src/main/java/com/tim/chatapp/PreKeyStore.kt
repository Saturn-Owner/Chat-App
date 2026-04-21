package com.tim.chatapp

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

object PreKeyStore {

    private const val IKS_ALIAS = "silentlink_identity_signing"

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun enc(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun dec(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context, "silentlink_prekeys",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    // ── IKS — Identity Key Signing (EC P-256, Android Keystore) ─────────────

    private fun generateIKS() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(IKS_ALIAS)) return

        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        val baseSpec = KeyGenParameterSpec.Builder(
            IKS_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        ).setDigests(KeyProperties.DIGEST_SHA256).build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                kpg.initialize(
                    KeyGenParameterSpec.Builder(
                        IKS_ALIAS,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                    ).setDigests(KeyProperties.DIGEST_SHA256)
                        .setIsStrongBoxBacked(true)
                        .build()
                )
                kpg.generateKeyPair()
                return
            } catch (_: StrongBoxUnavailableException) {}
        }
        kpg.initialize(baseSpec)
        kpg.generateKeyPair()
    }

    fun getIKSPublicKeyBytes(): ByteArray {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.getCertificate(IKS_ALIAS).publicKey.encoded
    }

    fun signWithIKS(data: ByteArray): ByteArray {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val priv = ks.getKey(IKS_ALIAS, null) as PrivateKey
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(priv)
            update(data)
            sign()
        }
    }

    fun verifySpkSignature(iksPub: ByteArray, spkPub: ByteArray, spkSig: ByteArray): Boolean =
        runCatching {
            val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(iksPub))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(pub)
                update(spkPub)
                verify(spkSig)
            }
        }.getOrDefault(false)

    fun deleteIKS() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(IKS_ALIAS)) ks.deleteEntry(IKS_ALIAS)
    }

    // ── All Keys (called once at registration) ───────────────────────────────

    data class GeneratedBundle(
        val iksPub: ByteArray,
        val ikdPub: ByteArray,
        val spkId: Int,
        val spkPub: ByteArray,
        val spkSig: ByteArray,
        val opks: List<Pair<Int, ByteArray>>,
    )

    fun generateAllKeys(context: Context, numOPKs: Int = 20): GeneratedBundle {
        generateIKS()
        val p = prefs(context)

        val (ikdPub, ikdPriv) = SignalCrypto.generateX25519()
        p.edit().putString("ikd_pub", enc(ikdPub)).putString("ikd_priv", enc(ikdPriv)).apply()

        val spkId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val (spkPub, spkPriv) = SignalCrypto.generateX25519()
        val spkSig = signWithIKS(spkPub)
        p.edit()
            .putString("spk_pub", enc(spkPub))
            .putString("spk_priv", enc(spkPriv))
            .putString("spk_sig", enc(spkSig))
            .putInt("spk_id", spkId)
            .putLong("spk_created_at", System.currentTimeMillis())
            .apply()

        val opks = (1..numOPKs).map { i ->
            val (opkPub, opkPriv) = SignalCrypto.generateX25519()
            val opkId = spkId + i
            p.edit().putString("opk_priv_$opkId", enc(opkPriv)).apply()
            opkId to opkPub
        }

        return GeneratedBundle(
            iksPub = getIKSPublicKeyBytes(),
            ikdPub = ikdPub,
            spkId = spkId,
            spkPub = spkPub,
            spkSig = spkSig,
            opks = opks,
        )
    }

    // ── Key Accessors ─────────────────────────────────────────────────────────

    fun getIKDPriv(context: Context): ByteArray = dec(prefs(context).getString("ikd_priv", null)!!)
    fun getIKDPub(context: Context): ByteArray = dec(prefs(context).getString("ikd_pub", null)!!)
    fun getSPKPriv(context: Context): ByteArray = dec(prefs(context).getString("spk_priv", null)!!)
    fun getSPKPub(context: Context): ByteArray = dec(prefs(context).getString("spk_pub", null)!!)
    fun getSPKId(context: Context): Int = prefs(context).getInt("spk_id", 0)

    fun getOPKPriv(context: Context, id: Int): ByteArray? =
        prefs(context).getString("opk_priv_$id", null)?.let { dec(it) }

    fun consumeOPK(context: Context, id: Int) {
        prefs(context).edit().remove("opk_priv_$id").apply()
    }

    fun generateNewOPKs(context: Context, count: Int = 20): List<Pair<Int, ByteArray>> {
        val base = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        return (1..count).map { i ->
            val (opkPub, opkPriv) = SignalCrypto.generateX25519()
            val opkId = base + i
            prefs(context).edit().putString("opk_priv_$opkId", enc(opkPriv)).apply()
            opkId to opkPub
        }
    }

    // ── SPK Rotation ──────────────────────────────────────────────────────────

    fun needsSPKRotation(context: Context): Boolean {
        val createdAt = prefs(context).getLong("spk_created_at", 0)
        return System.currentTimeMillis() - createdAt > 30L * 24 * 60 * 60 * 1000
    }

    data class RotatedSPK(val spkId: Int, val spkPub: ByteArray, val spkSig: ByteArray)

    fun rotateSPK(context: Context): RotatedSPK {
        val (spkPub, spkPriv) = SignalCrypto.generateX25519()
        val spkId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val spkSig = signWithIKS(spkPub)
        prefs(context).edit()
            .putString("spk_pub", enc(spkPub))
            .putString("spk_priv", enc(spkPriv))
            .putString("spk_sig", enc(spkSig))
            .putInt("spk_id", spkId)
            .putLong("spk_created_at", System.currentTimeMillis())
            .apply()
        return RotatedSPK(spkId, spkPub, spkSig)
    }

    // ── Safety Numbers ────────────────────────────────────────────────────────

    fun computeSafetyNumber(myIksPub: ByteArray, theirIksPub: ByteArray): String {
        val sorted = if (myIksPub.contentHashCode() < theirIksPub.contentHashCode())
            myIksPub + theirIksPub else theirIksPub + myIksPub
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(sorted)
        return hash.take(30).chunked(5).joinToString(" ") { bytes ->
            (bytes.fold(0L) { acc, b -> acc * 256 + (b.toLong() and 0xFF) } % 100000)
                .toString().padStart(5, '0')
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    fun deleteAllKeys(context: Context) {
        prefs(context).edit().clear().apply()
        RatchetStateStore.deleteAll(context)
        deleteIKS()
    }
}
