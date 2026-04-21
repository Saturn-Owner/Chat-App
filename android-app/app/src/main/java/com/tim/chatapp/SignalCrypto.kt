package com.tim.chatapp

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object SignalCrypto {

    val sodium: LazySodiumAndroid by lazy { LazySodiumAndroid(SodiumAndroid()) }

    // ── HKDF-SHA256 ─────────────────────────────────────────────────────────

    private fun hmac256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val effectiveSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val prk = hmac256(effectiveSalt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var i = 1
        while (pos < length) {
            t = hmac256(prk, t + info + byteArrayOf(i.toByte()))
            val n = minOf(t.size, length - pos)
            t.copyInto(out, pos, 0, n)
            pos += n
            i++
        }
        return out
    }

    // ── X25519 ───────────────────────────────────────────────────────────────

    fun generateX25519(): Pair<ByteArray, ByteArray> {
        val priv = sodium.randomBytesBuf(32)
        priv[0] = (priv[0].toInt() and 248).toByte()
        priv[31] = (priv[31].toInt() and 127 or 64).toByte()
        val pub = ByteArray(32)
        sodium.cryptoScalarMultBase(pub, priv)
        return pub to priv
    }

    fun dh(myPriv: ByteArray, theirPub: ByteArray): ByteArray {
        val out = ByteArray(32)
        check(sodium.cryptoScalarMult(out, myPriv, theirPub)) { "DH operation failed" }
        return out
    }

    // ── X3DH ─────────────────────────────────────────────────────────────────
    // Signal X3DH spec: IKM = 0xFF×32 || DH1 || DH2 || DH3 [|| DH4]
    // Returns (RootKey, ChainKey)

    private val F32 = ByteArray(32) { 0xFF.toByte() }
    private val X3DH_INFO = "SilentLinkX3DH".toByteArray()

    fun x3dhSender(
        ikdPrivA: ByteArray,
        spkPubB: ByteArray,
        ikdPubB: ByteArray,
        opkPubB: ByteArray?,
        ekPriv: ByteArray,
    ): Pair<ByteArray, ByteArray> {
        val dh1 = dh(ikdPrivA, spkPubB)
        val dh2 = dh(ekPriv, ikdPubB)
        val dh3 = dh(ekPriv, spkPubB)
        val ikm = if (opkPubB != null)
            F32 + dh1 + dh2 + dh3 + dh(ekPriv, opkPubB)
        else
            F32 + dh1 + dh2 + dh3
        val out = hkdf(ByteArray(32), ikm, X3DH_INFO, 64)
        return out.copyOf(32) to out.copyOfRange(32, 64)
    }

    fun x3dhRecipient(
        ikdPrivB: ByteArray,
        spkPrivB: ByteArray,
        opkPrivB: ByteArray?,
        ikdPubA: ByteArray,
        ekPubA: ByteArray,
    ): Pair<ByteArray, ByteArray> {
        val dh1 = dh(spkPrivB, ikdPubA)
        val dh2 = dh(ikdPrivB, ekPubA)
        val dh3 = dh(spkPrivB, ekPubA)
        val ikm = if (opkPrivB != null)
            F32 + dh1 + dh2 + dh3 + dh(opkPrivB, ekPubA)
        else
            F32 + dh1 + dh2 + dh3
        val out = hkdf(ByteArray(32), ikm, X3DH_INFO, 64)
        return out.copyOf(32) to out.copyOfRange(32, 64)
    }

    // ── Double Ratchet KDFs ──────────────────────────────────────────────────

    fun kdfRK(rootKey: ByteArray, dhOutput: ByteArray): Pair<ByteArray, ByteArray> {
        val out = hkdf(rootKey, dhOutput, "SilentLinkRatchet".toByteArray(), 64)
        return out.copyOf(32) to out.copyOfRange(32, 64)
    }

    // Returns (messageKey, newChainKey)
    fun kdfCK(chainKey: ByteArray): Pair<ByteArray, ByteArray> =
        hmac256(chainKey, byteArrayOf(0x01)) to hmac256(chainKey, byteArrayOf(0x02))

    // ── XSalsa20-Poly1305 ────────────────────────────────────────────────────
    // Returns (ciphertext, nonce)

    fun encrypt(key: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val nonce = sodium.randomBytesBuf(24)
        val ct = ByteArray(plaintext.size + 16)
        check(sodium.cryptoSecretBoxEasy(ct, plaintext, plaintext.size.toLong(), nonce, key)) {
            "Encryption failed"
        }
        return ct to nonce
    }

    fun decrypt(key: ByteArray, ciphertext: ByteArray, nonce: ByteArray): ByteArray {
        val pt = ByteArray(ciphertext.size - 16)
        check(sodium.cryptoSecretBoxOpenEasy(pt, ciphertext, ciphertext.size.toLong(), nonce, key)) {
            "Decryption or authentication failed"
        }
        return pt
    }
}
