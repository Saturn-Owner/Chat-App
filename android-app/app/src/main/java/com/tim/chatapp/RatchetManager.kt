package com.tim.chatapp

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson

// Serializable ratchet state (all ByteArrays stored as base64 strings)
data class RatchetState(
    val myRatchetPub: String,
    val myRatchetPriv: String,
    val theirRatchetPub: String?,
    val rootKey: String,
    val sendChainKey: String?,
    val recvChainKey: String?,
    val sendIndex: Int,
    val recvIndex: Int,
    val prevSendIndex: Int,
    val skippedKeys: Map<String, String> = emptyMap(),
)

object RatchetStateStore {

    private val gson = Gson()

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context, "ratchet_sessions",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun save(context: Context, contactId: String, state: RatchetState) {
        prefs(context).edit().putString("s_$contactId", gson.toJson(state)).apply()
    }

    fun load(context: Context, contactId: String): RatchetState? =
        prefs(context).getString("s_$contactId", null)
            ?.let { gson.fromJson(it, RatchetState::class.java) }

    fun hasSession(context: Context, contactId: String): Boolean =
        prefs(context).contains("s_$contactId")

    fun delete(context: Context, contactId: String) {
        prefs(context).edit().remove("s_$contactId").apply()
    }

    fun deleteAll(context: Context) {
        prefs(context).edit().clear().apply()
    }
}

object RatchetManager {

    private const val MAX_SKIP = 100

    private fun enc(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun dec(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    // ── Init from X3DH ───────────────────────────────────────────────────────

    data class RecipientBundle(
        val iksPub: ByteArray,
        val ikdPub: ByteArray,
        val spkId: Int,
        val spkPub: ByteArray,
        val spkSig: ByteArray,
        val opkId: Int,
        val opkPub: ByteArray?,
    )

    // Called by sender before first message. Returns EK pub for x3dh_header.
    fun initAsSender(context: Context, contactId: String, bundle: RecipientBundle): ByteArray {
        check(PreKeyStore.verifySpkSignature(bundle.iksPub, bundle.spkPub, bundle.spkSig)) {
            "SPK signature verification failed — possible MITM"
        }
        val (ekPub, ekPriv) = SignalCrypto.generateX25519()
        val myIkdPriv = PreKeyStore.getIKDPriv(context)

        val (rk, _) = SignalCrypto.x3dhSender(
            ikdPrivA = myIkdPriv,
            spkPubB = bundle.spkPub,
            ikdPubB = bundle.ikdPub,
            opkPubB = bundle.opkPub,
            ekPriv = ekPriv,
        )
        ekPriv.fill(0)  // EK private key no longer needed

        // Alice's initial ratchet: generate DHs, compute first chain key
        val (myRatchetPub, myRatchetPriv) = SignalCrypto.generateX25519()
        val (newRk, sendCk) = SignalCrypto.kdfRK(rk, SignalCrypto.dh(myRatchetPriv, bundle.spkPub))

        RatchetStateStore.save(context, contactId, RatchetState(
            myRatchetPub = enc(myRatchetPub),
            myRatchetPriv = enc(myRatchetPriv),
            theirRatchetPub = enc(bundle.spkPub),
            rootKey = enc(newRk),
            sendChainKey = enc(sendCk),
            recvChainKey = null,
            sendIndex = 0,
            recvIndex = 0,
            prevSendIndex = 0,
        ))
        DeviceStorage.savePeerIksPub(context, contactId, enc(bundle.iksPub))
        return ekPub
    }

    // Called by recipient when first message arrives with x3dh_header.
    fun initAsReceiver(context: Context, contactId: String, header: X3DHHeaderData) {
        val myIkdPriv = PreKeyStore.getIKDPriv(context)
        val mySpkPriv = PreKeyStore.getSPKPriv(context)
        val mySpkPub = PreKeyStore.getSPKPub(context)
        val opkPriv = if (header.opkId != -1) {
            val k = PreKeyStore.getOPKPriv(context, header.opkId)
            if (k != null) PreKeyStore.consumeOPK(context, header.opkId)
            k
        } else null

        val (rk, _) = SignalCrypto.x3dhRecipient(
            ikdPrivB = myIkdPriv,
            spkPrivB = mySpkPriv,
            opkPrivB = opkPriv,
            ikdPubA = header.ikdPubA,
            ekPubA = header.ekPubA,
        )

        RatchetStateStore.save(context, contactId, RatchetState(
            myRatchetPub = enc(mySpkPub),
            myRatchetPriv = enc(mySpkPriv),
            theirRatchetPub = null,
            rootKey = enc(rk),
            sendChainKey = null,
            recvChainKey = null,
            sendIndex = 0,
            recvIndex = 0,
            prevSendIndex = 0,
        ))
        header.iksPubA?.let { DeviceStorage.savePeerIksPub(context, contactId, enc(it)) }
    }

    // ── Encrypt ──────────────────────────────────────────────────────────────

    data class EncryptedMessage(
        val ciphertext: String,
        val nonce: String,
        val ratchetPub: String,
        val messageIndex: Int,
        val prevSendIndex: Int,
    )

    fun encrypt(context: Context, contactId: String, plaintext: String): EncryptedMessage {
        val state = RatchetStateStore.load(context, contactId)
            ?: error("No ratchet session for $contactId")
        val (msgKey, newCK) = SignalCrypto.kdfCK(dec(state.sendChainKey!!))
        val (ct, nonce) = SignalCrypto.encrypt(msgKey, plaintext.toByteArray(Charsets.UTF_8))
        msgKey.fill(0)

        RatchetStateStore.save(context, contactId, state.copy(
            sendChainKey = enc(newCK),
            sendIndex = state.sendIndex + 1,
        ))

        return EncryptedMessage(
            ciphertext = enc(ct),
            nonce = enc(nonce),
            ratchetPub = state.myRatchetPub,
            messageIndex = state.sendIndex,
            prevSendIndex = state.prevSendIndex,
        )
    }

    // ── Decrypt ──────────────────────────────────────────────────────────────

    fun decrypt(context: Context, contactId: String, msg: EncryptedMessage): String {
        var state = RatchetStateStore.load(context, contactId)
            ?: error("No ratchet session for $contactId")

        // 1. Try skipped message keys
        val skipKey = "${msg.ratchetPub}:${msg.messageIndex}"
        state.skippedKeys[skipKey]?.let { skippedMK ->
            val pt = SignalCrypto.decrypt(dec(skippedMK), dec(msg.ciphertext), dec(msg.nonce))
            val newSkipped = state.skippedKeys.toMutableMap().also { it.remove(skipKey) }
            RatchetStateStore.save(context, contactId, state.copy(skippedKeys = newSkipped))
            return String(pt, Charsets.UTF_8)
        }

        // 2. DH ratchet step if new ratchet key
        if (state.theirRatchetPub == null || msg.ratchetPub != state.theirRatchetPub) {
            val newSkipped = state.skippedKeys.toMutableMap()

            // Save skipped keys from current recv chain
            if (state.recvChainKey != null) {
                var ck = dec(state.recvChainKey)
                var idx = state.recvIndex
                while (idx < msg.prevSendIndex && newSkipped.size < MAX_SKIP) {
                    val (mk, newCk) = SignalCrypto.kdfCK(ck)
                    newSkipped["${state.theirRatchetPub}:$idx"] = enc(mk)
                    mk.fill(0)
                    ck = newCk
                    idx++
                }
            }

            val theirNewPub = dec(msg.ratchetPub)
            val myRatchetPriv = dec(state.myRatchetPriv)
            val (newRk1, recvCk) = SignalCrypto.kdfRK(
                dec(state.rootKey), SignalCrypto.dh(myRatchetPriv, theirNewPub)
            )
            val (myNewPub, myNewPriv) = SignalCrypto.generateX25519()
            val (newRk2, sendCk) = SignalCrypto.kdfRK(
                newRk1, SignalCrypto.dh(myNewPriv, theirNewPub)
            )
            myRatchetPriv.fill(0)

            state = state.copy(
                myRatchetPub = enc(myNewPub),
                myRatchetPriv = enc(myNewPriv),
                theirRatchetPub = msg.ratchetPub,
                rootKey = enc(newRk2),
                sendChainKey = enc(sendCk),
                recvChainKey = enc(recvCk),
                sendIndex = 0,
                recvIndex = 0,
                prevSendIndex = state.sendIndex,
                skippedKeys = newSkipped,
            )
        }

        // 3. Skip messages in current recv chain up to msg.messageIndex
        val newSkipped2 = state.skippedKeys.toMutableMap()
        var ck = dec(state.recvChainKey!!)
        var idx = state.recvIndex
        while (idx < msg.messageIndex && newSkipped2.size < MAX_SKIP) {
            val (mk, newCk) = SignalCrypto.kdfCK(ck)
            newSkipped2["${msg.ratchetPub}:$idx"] = enc(mk)
            mk.fill(0)
            ck = newCk
            idx++
        }

        // 4. Decrypt
        val (msgKey, newCK) = SignalCrypto.kdfCK(ck)
        val pt = SignalCrypto.decrypt(msgKey, dec(msg.ciphertext), dec(msg.nonce))
        msgKey.fill(0)

        RatchetStateStore.save(context, contactId, state.copy(
            recvChainKey = enc(newCK),
            recvIndex = msg.messageIndex + 1,
            skippedKeys = newSkipped2,
        ))

        return String(pt, Charsets.UTF_8)
    }
}

data class X3DHHeaderData(
    val ikdPubA: ByteArray,
    val ekPubA: ByteArray,
    val opkId: Int,
    val iksPubA: ByteArray? = null,
)
