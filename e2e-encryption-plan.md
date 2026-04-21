# E2E Verschlüsselung — Signal Protocol für Android (Multi-User)

## Ziel

- Nachrichten werden **Ende-zu-Ende verschlüsselt** — der Server sieht niemals Plaintext
- Jeder Message-Key gilt nur für **eine einzige Nachricht** (Forward Secrecy + Break-in Recovery)
- Jeder Nutzer hat **paarweise Sessions** mit jedem Kontakt (wie Signal)
- Funktioniert auch wenn der Empfänger offline ist (X3DH über PreKeys)
- Der Private Key verlässt das Gerät **niemals**
- MITM-Schutz via Safety Numbers

---

## Crypto-Stack

| Schicht | Algorithmus | Bibliothek |
|---|---|---|
| Identity Key Signing | EC P-256 (ECDSA, SHA-256) | Android Keystore (hardware-backed) |
| DH-Operationen | X25519 (Curve25519) | libsodium `crypto_kx_keypair` |
| Symmetric Cipher | XSalsa20-Poly1305 | libsodium `crypto_secretbox_easy` |
| KDF | HKDF-SHA256 | libsodium `crypto_kdf_derive_from_key` |
| Ratchet State / DH Private Keys | EncryptedSharedPreferences (AES-256-GCM) | Jetpack Security |

### Warum zwei getrennte Key-Typen für Identity Key?

Android Keystore unterstützt **Curve25519 erst ab API 31 (Android 12)** — für breite Kompatibilität
(Android 8+) wird der Identity Key daher aufgeteilt:

- **Signing** (SPK-Signatur, Safety Numbers): EC P-256 im Android Keystore → private Teil nie extrahierbar
- **DH** (X3DH-Handshake): X25519 via libsodium, privater Teil verschlüsselt in EncryptedSharedPreferences

Das ist derselbe Ansatz den auch WhatsApp und Threema auf älteren Android-Versionen verwenden.

---

## Key-Typen pro User

```
Identity Key Signing (IKS)  → EC P-256 im Android Keystore, verlässt Gerät nie (signiert SPK)
Identity Key DH (IKD)       → X25519, privater Teil in EncryptedSharedPreferences
Signed PreKey (SPK)         → X25519, 30 Tage, von IKS signiert, rotiert automatisch
One-Time PreKeys (OPK)      → X25519, einmalig verwendbar, Bündel von 20 Stück
Ephemeral Key (EK)          → X25519, einmalig pro Session, sofort nach X3DH löschen
```

---

## Phase 1 — Registrierung

```
1. Generiere IKS-Keypair im Android Keystore (EC P-256, StrongBox wenn verfügbar)
2. Generiere IKD-Keypair via libsodium (X25519)
3. Generiere SPK via libsodium (X25519)
4. Signiere SPK.pub mit IKS (ECDSA-SHA256 via Keystore)
5. Generiere 20 OPKs via libsodium (X25519)
6. Schicke ans Backend:
   {
     iks_pub: base64(P-256 public key),
     ikd_pub: base64(X25519 public key),
     spk_pub: base64,
     spk_sig: base64,
     spk_id:  int,
     opks:    [{id: int, pub: base64}, ...]   // 20 Stück
   }
```

Private Teile von IKD, SPK und OPKs werden in EncryptedSharedPreferences gespeichert.

---

## Phase 2 — X3DH Initial Handshake

### Sender (erster Kontakt mit User B)

```
1. Hole PreKey Bundle von B:
   { iks_pub_B, ikd_pub_B, spk_pub_B, spk_sig_B, spk_id_B, opk_pub_B, opk_id_B }

2. Verifiziere: ECDSA.verify(spk_pub_B, spk_sig_B, iks_pub_B)
   → Wenn ungültig: Abbruch — möglicher MITM oder korrupter Server

3. Generiere Ephemeral Key EK_A (X25519)

4. Berechne 4 DH-Operationen (X25519):
   DH1 = DH(IKD_A.priv, SPK_B.pub)
   DH2 = DH(EK_A.priv,  IKD_B.pub)
   DH3 = DH(EK_A.priv,  SPK_B.pub)
   DH4 = DH(EK_A.priv,  OPK_B.pub)   ← entfällt wenn kein OPK verfügbar

5. Master Secret = HKDF(DH1 || DH2 || DH3 || DH4)

6. Leite ab:
   Root Key (RK)   → startet den Double Ratchet
   Chain Key (CK)  → erste Sending Chain

7. Schicke mit erster Nachricht:
   { ikd_pub_A, ek_pub_A, opk_id_B, ciphertext, ratchet_pub, message_index, nonce }

8. Lösche EK_A.priv sofort nach Berechnung
```

### Empfänger (B empfängt erste Nachricht)

```
1. Lese aus Header: ikd_pub_A, ek_pub_A, opk_id_B
2. Hole OPK_B.priv aus EncryptedSharedPreferences (via opk_id_B)
3. Führe dieselben 4 DH-Operationen durch → identisches Master Secret
4. Initialisiere Double Ratchet mit Root Key
5. Entschlüssele Nachricht
6. Lösche OPK_B.priv (einmalig verwendbar)
```

---

## Phase 3 — Double Ratchet (laufende Kommunikation)

### Aufbau

```
Root Key (RK)
    │
    ├─→ Sending Chain Key (CK_s)
    │       ├─→ Message Key 1 → Nachricht verschlüsseln → sofort löschen
    │       ├─→ Message Key 2 → ...
    │       └─→ Message Key N → ...
    │
    └─→ Receiving Chain Key (CK_r)
            └─→ Message Key 1, 2, N ...
```

### DH Ratchet Step (bei jeder gesendeten Nachricht)

```
1. Generiere neues X25519-Keypair (ratchet_pub, ratchet_priv)
2. DH_out = DH(ratchet_priv, their_ratchet_pub)
3. (neuer RK, neuer CK_s) = HKDF(alter_RK, DH_out)
4. ratchet_pub an Nachricht anhängen
5. Alten ratchet_priv sofort löschen
```

### Symmetric Ratchet Step (Message Key ableiten)

```
Message Key = HKDF(CK, "message")
neuer CK    = HKDF(CK, "chain")
alter CK    → sofort löschen
```

### Nachrichtenformat (Wire Format)

```json
{
  "sender_device_id":    "string",
  "recipient_device_id": "string",
  "ciphertext":          "base64 (XSalsa20-Poly1305)",
  "nonce":               "base64, 24 bytes",
  "ratchet_pub":         "base64, X25519 public key",
  "message_index":       42,
  "timestamp":           "ISO 8601"
}
```

**Niemals in der Payload:** Plaintext, Private Keys, Master Secret, Chain Keys, Message Keys.

---

## Android Keystore — Korrekte Implementierung

### Identity Key Signing (EC P-256, StrongBox-Fallback)

```kotlin
fun generateIdentitySigningKey(context: Context) {
    val keyPairGenerator = KeyPairGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore"
    )
    val spec = KeyGenParameterSpec.Builder(
        "silentlink_identity_signing",
        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
    )
        .setDigests(KeyProperties.DIGEST_SHA256)
        .setUserAuthenticationRequired(false)
        .build()

    // StrongBox (Titan M / dedicated HSM) wenn verfügbar, sonst TEE
    try {
        val strongBoxSpec = KeyGenParameterSpec.Builder(
            "silentlink_identity_signing",
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setIsStrongBoxBacked(true) // API 28+
            .build()
        keyPairGenerator.initialize(strongBoxSpec)
    } catch (e: StrongBoxUnavailableException) {
        keyPairGenerator.initialize(spec) // Fallback auf TEE
    }

    keyPairGenerator.generateKeyPair()
}

fun signWithIdentityKey(data: ByteArray): ByteArray {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val privateKey = keyStore.getKey("silentlink_identity_signing", null) as PrivateKey
    return Signature.getInstance("SHA256withECDSA").run {
        initSign(privateKey)
        update(data)
        sign()
    }
}
```

### DH Keys (X25519) via libsodium + EncryptedSharedPreferences

```kotlin
// EncryptedSharedPreferences Setup
fun createSecurePrefs(context: Context): SharedPreferences {
    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    return EncryptedSharedPreferences.create(
        context, "silentlink_keys", masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}

// Identity DH Key generieren und speichern
fun generateIdentityDHKey(prefs: SharedPreferences) {
    val sodium = LazySodiumAndroid(SodiumAndroid())
    val keyPair = sodium.cryptoKxKeypair()
    prefs.edit()
        .putString("ikd_pub",  Base64.encode(keyPair.publicKey.asBytes))
        .putString("ikd_priv", Base64.encode(keyPair.secretKey.asBytes))
        .apply()
}

// OPK nach Verwendung löschen
fun consumeOPK(prefs: SharedPreferences, opkId: Int) {
    prefs.edit()
        .remove("opk_priv_$opkId")
        .apply()
}
```

### Ratchet State pro Session

```kotlin
// Ein separates EncryptedSharedPreferences File pro Session (pro Kontakt)
fun getRatchetPrefs(context: Context, contactId: String): SharedPreferences {
    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    return EncryptedSharedPreferences.create(
        context, "ratchet_$contactId", masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    // Speichert: root_key, ck_send, ck_recv, ratchet_pub, ratchet_priv, send_index, recv_index
}
```

---

## Backend — Minimale Datenhaltung

### Neue Datenstruktur

```python
# Device (in codes.json oder separater Datei)
{
    "device_id":    str,
    "display_name": str,
    "iks_pub":      str,   # base64, EC P-256 (für Signaturprüfung)
    "ikd_pub":      str,   # base64, X25519 (für X3DH)
    "spk": {
        "id":  int,
        "pub": str,        # base64, X25519
        "sig": str,        # base64, ECDSA-SHA256 über spk.pub mit iks
    },
    "opks": [              # Liste, nach Gebrauch sofort löschen
        {"id": int, "pub": str},
        ...
    ]
}

# Message (nach ACK sofort löschen — wie bisher)
{
    "message_id":          str,
    "sender_device_id":    str,
    "recipient_device_id": str,
    "ciphertext":          str,   # base64
    "nonce":               str,   # base64
    "ratchet_pub":         str,   # base64
    "message_index":       int,
    "timestamp":           datetime,
    # Nur bei erster Nachricht einer Session:
    "x3dh_header": {
        "ikd_pub_sender": str,
        "ek_pub":         str,
        "opk_id":         int    # -1 wenn keine OPK verwendet
    }
}
```

### Backend-Endpoints

```
POST /devices/register              → PreKey Bundle hochladen (IKS, IKD, SPK, OPKs)
GET  /devices/{id}/prekey_bundle    → PreKey Bundle abrufen + 1 OPK verbrauchen + löschen
POST /devices/{id}/prekeys          → Neue OPKs nachliefern (wenn < 5 übrig)
GET  /devices/{id}/opk_count        → Anzahl verbleibender OPKs abfragen
POST /messages/send                 → Verschlüsselte Nachricht senden
GET  /messages/pending/{id}         → Ausstehende Nachrichten abrufen
POST /messages/ack                  → Nachricht bestätigen → sofort löschen
POST /keys/spk_rotate               → Neues Signed PreKey hochladen (alle 30 Tage)
```

**Wichtig bei `/devices/{id}/prekey_bundle`:** Server gibt genau eine OPK heraus und löscht sie atomisch.
Sind keine OPKs mehr vorhanden, wird der Bundle ohne OPK zurückgegeben (X3DH ohne OPK = leicht schwächer aber funktional).

---

## Multi-User: Paarweise Sessions

Für mehrere Nutzer (wie bei Signal): **jedes Nutzerpaar hat eine eigene, unabhängige Double-Ratchet-Session.**

```
Tim  ←→  Max    → eigener Root Key, eigene Chain Keys, eigener Ratchet State
Tim  ←→  Lisa   → eigener Root Key, eigene Chain Keys, eigener Ratchet State
Max  ←→  Lisa   → eigener Root Key, eigene Chain Keys, eigener Ratchet State
```

Der Ratchet State wird pro Kontakt in einer eigenen EncryptedSharedPreferences-Datei gespeichert
(`ratchet_<contactId>`). Sessions werden automatisch initiiert beim ersten Schreiben an einen Kontakt.

**Keine Gruppen-Verschlüsselung in diesem Plan** — das erfordert einen separaten Ansatz
(Sender Keys wie WhatsApp oder MLS). Vorerst: Gruppen = mehrere paarweise Sessions (Broadcast an alle).

---

## Safety Numbers (MITM-Schutz)

```kotlin
fun computeSafetyNumber(myIKS: ByteArray, theirIKS: ByteArray): String {
    // Beide Keys sortiert hashen → Reihenfolge egal, gleiches Ergebnis auf beiden Geräten
    val sorted = if (myIKS.contentHashCode() < theirIKS.contentHashCode())
        myIKS + theirIKS else theirIKS + myIKS
    val hash = MessageDigest.getInstance("SHA-256").digest(sorted)
    // 12 Blöcke à 5 Ziffern (wie Signal)
    return hash.take(30).chunked(5).joinToString(" ") { bytes ->
        (bytes.fold(0L) { acc, b -> acc * 256 + (b.toLong() and 0xFF) } % 100000)
            .toString().padStart(5, '0')
    }
}
```

**UX:**
- In Chat-Settings: "Sicherheitsnummer vergleichen"
- Zeigt QR-Code + 60-stellige Zahl
- User vergleichen live oder per Telefonat
- Bei IKS-Änderung des Kontakts: Warnung "Sicherheitsnummer hat sich geändert — bitte verifizieren"

---

## UX — Decrypt-on-Tap Animation

### Verhalten
- Eingehende Nachrichten werden **sofort nach Empfang entschlüsselt** (im Hintergrund)
- In der Bubble: Kauderwelsch-Platzhalter (`▒▒▒▒▒ ▒▒▒▒▒▒`)
- Antippen → Scramble-Animation → Klartext sichtbar
- **App geht in Hintergrund → alle Nachrichten wieder verbergen** (re-scramble)

### Implementierung

```kotlin
fun animateDecrypt(textView: TextView, plaintext: String, onDone: () -> Unit) {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%"
    val duration = 25L

    plaintext.indices.forEach { index ->
        repeat(4) { tick ->
            textView.postDelayed({
                textView.text = buildString {
                    plaintext.indices.forEach { i ->
                        append(if (i < index) plaintext[i] else chars.random())
                    }
                }
            }, index * duration + tick * 6)
        }
        textView.postDelayed({
            textView.text = plaintext.substring(0, index + 1) +
                buildString { repeat(plaintext.length - index - 1) { append(chars.random()) } }
            if (index == plaintext.lastIndex) onDone()
        }, index * duration + 24)
    }
}

// In ChatActivity — bei onPause alle Nachrichten wieder verbergen:
override fun onPause() {
    super.onPause()
    messageAdapter.hideAllDecrypted()
}
```

---

## Skipped Messages (Out-of-Order Delivery)

Wenn Nachrichten in falscher Reihenfolge ankommen (z.B. Message 5 vor Message 4):

```kotlin
// Skipped Message Keys zwischenspeichern — max. 1000 Keys, dann verwerfen
data class SkippedKey(val ratchetPub: ByteArray, val index: Int)
val skippedMessageKeys: MutableMap<SkippedKey, ByteArray> = mutableMapOf()

// Bei Entschlüsselung: zuerst in skippedMessageKeys schauen
// Key nach Verwendung sofort löschen
// Nach MAX_SKIP (1000) Keys ohne Match: Session als kompromittiert betrachten
```

---

## SPK-Rotation (alle 30 Tage)

```kotlin
// Beim App-Start prüfen:
val spkAge = System.currentTimeMillis() - prefs.getLong("spk_created_at", 0)
if (spkAge > 30L * 24 * 60 * 60 * 1000) {
    val newSpk = sodium.cryptoKxKeypair()
    val sig = signWithIdentityKey(newSpk.publicKey.asBytes)
    api.rotateSPK(newSpkPub = newSpk.publicKey.asBytes, sig = sig)
    prefs.edit()
        .putString("spk_pub",        Base64.encode(newSpk.publicKey.asBytes))
        .putString("spk_priv",       Base64.encode(newSpk.secretKey.asBytes))
        .putLong("spk_created_at",   System.currentTimeMillis())
        .apply()
    // Alten SPK-Private Key erst nach 7 Tagen löschen
    // (für laufende Sessions die noch den alten SPK verwenden könnten)
}
```

---

## OPK Replenishment

```kotlin
// Nach jeder gesendeten Nachricht: OPK-Vorrat prüfen
suspend fun checkAndReplenishOPKs() {
    val remaining = api.getOPKCount(deviceId)
    if (remaining < 5) {
        val newOPKs = (1..20).map {
            val kp = sodium.cryptoKxKeypair()
            val id = generateOPKId()
            prefs.edit().putString("opk_priv_$id", Base64.encode(kp.secretKey.asBytes)).apply()
            OPKUpload(id = id, pub = Base64.encode(kp.publicKey.asBytes))
        }
        api.replenishOPKs(deviceId, newOPKs)
    }
}
```

---

## Sicherheits-Zusammenfassung

| Szenario | Schutz |
|---|---|
| Server kompromittiert | Nur Ciphertext — ohne Private Keys nutzlos |
| Vergangene Nachrichten nach Key-Diebstahl | Sicher — Forward Secrecy durch Ratchet |
| Zukünftige Nachrichten nach Key-Diebstahl | Sicher nach nächstem DH Ratchet Step (Break-in Recovery) |
| MITM beim Key Exchange | Erkennbar via Safety Numbers |
| Replay-Attacke | Message Index + Nonce verhindern Wiederholung |
| OPKs aufgebraucht | X3DH funktioniert auch ohne — leicht schwächer |
| Gerät verloren/gestohlen | IKS im Hardware-Keystore, nicht extrahierbar |
| App-Screenshot | FLAG_SECURE in Chat + Settings Activity |
| Schulter-Surfen | Decrypt-on-Tap + re-scramble bei onPause |

---

## Implementierungs-Reihenfolge

1. **libsodium einbinden** — `lazysodium-android` als Gradle-Dependency
2. **IKS im Android Keystore** — EC P-256, StrongBox-Fallback
3. **IKD + SPK + OPKs** — X25519 via libsodium, privat in EncryptedSharedPreferences
4. **Backend-Endpoints** — PreKey Bundle speichern/abrufen, OPK-Management
5. **X3DH Handshake** — Sender + Empfänger-Seite implementieren
6. **Double Ratchet** — paarweise Sessions, State in EncryptedSharedPreferences
7. **Skipped Messages** — Out-of-Order Handling
8. **SPK-Rotation** — automatisch alle 30 Tage beim App-Start
9. **OPK Replenishment** — automatisch wenn < 5 übrig
10. **Safety Numbers UI** — Verifikations-Screen in Chat-Settings
11. **Scramble-Animation** — decrypt-on-tap + re-scramble bei onPause

---

## Abhängigkeiten (Gradle)

```groovy
// libsodium für Android
implementation 'com.goterl:lazysodium-android:5.1.0@aar'
implementation 'net.java.dev.jna:jna:5.13.0@aar'

// Encrypted Storage
implementation 'androidx.security:security-crypto:1.1.0-alpha06'

// Biometrie (bereits vorhanden)
implementation 'androidx.biometric:biometric:1.2.0-alpha05'
```

**Minimale Android API:** 23 (Android 6.0) — durch Android Keystore EC P-256 Anforderung.
StrongBox (Titan M) setzt API 28 voraus, wird aber automatisch mit Fallback gehandhabt.
