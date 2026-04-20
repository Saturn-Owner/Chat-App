# E2E Verschlüsselung — Maximale Sicherheit

## Ziel

- Nachrichten werden **Ende-zu-Ende verschlüsselt** — der Server sieht niemals Plaintext
- Jeder Message-Key gilt nur für **eine einzige Nachricht** (Forward Secrecy + Break-in Recovery)
- Der Private Key verlässt das Gerät **niemals**
- Eingehende Nachrichten erscheinen als Kauderwelsch und werden durch **Antippen + Scramble-Animation** entschlüsselt
- Kein fixer Key, kein wiederverwendbarer Key, kein Pre-Shared Secret

---

## Crypto-Stack

| Schicht | Algorithmus | Bibliothek |
|---|---|---|
| Key Exchange (Initial) | X3DH (Extended Triple Diffie-Hellman) | libsodium |
| Ongoing Encryption | Double Ratchet Algorithm | libsodium (manuell) |
| Symmetric Cipher | XSalsa20-Poly1305 | libsodium `crypto_secretbox_easy` |
| KDF | HKDF-SHA256 | libsodium `crypto_kdf_derive_from_key` |
| Key Storage | Android Keystore (Hardware-backed) | Android Security API |
| Ratchet State Storage | EncryptedSharedPreferences | Jetpack Security |

**Warum X3DH statt einfachem ECDH:**
Einfaches ECDH erfordert dass beide User gleichzeitig online sind. X3DH erlaubt Offline-Key-Exchange über vorberechnete Prekeys — wie Signal es macht.

**Warum Double Ratchet statt nur HKDF-Kette:**
Ein reiner Symmetric Ratchet (`neuer_key = HKDF(alter_key + counter)`) bietet Forward Secrecy aber keine **Break-in Recovery**. Wenn jemand den aktuellen Ratchet-State stiehlt, kann er alle zukünftigen Nachrichten entschlüsseln. Der Double Ratchet kombiniert Symmetric Ratchet + DH Ratchet: nach jeder gesendeten Nachricht wird ein neues DH-Keypair generiert, das den State regelmäßig neu durchmischt — der Angreifer verliert den Zugriff wieder.

---

## Key-Typen pro User

```
Identity Key (IK)       → langlebig, im Android Keystore, verlässt das Gerät nie
Signed PreKey (SPK)     → mittelfristig (30 Tage), vom IK signiert, rotiert automatisch
One-Time PreKeys (OPK)  → einmalig verwendbar, Bündel von 10–20 Stück
Ephemeral Key (EK)      → einmalig pro Session, generiert beim ersten Senden
```

Alle Keys: Curve25519 via `crypto_kx_keypair()` in libsodium.

---

## Phase 1 — Initial Key Exchange (X3DH)

### Registrierung (einmalig)

```
1. Generiere Identity Key (IK) → privater Teil in Android Keystore
2. Generiere Signed PreKey (SPK) → signiert mit IK via crypto_sign
3. Generiere 10 One-Time PreKeys (OPK[1..10])
4. Schicke ans Backend: { IK.public, SPK.public, SPK.signature, OPK[1..10].public }
```

Der Server speichert nur Public Keys. Der private Teil bleibt auf dem Gerät.

### Erster Kontakt (Sender)

```
1. Hole PreKey Bundle des Empfängers vom Server:
   { IK_B.pub, SPK_B.pub, SPK_B.sig, OPK_B.pub }

2. Verifiziere SPK_B.sig mit IK_B.pub
   → Wenn ungültig: Abbruch, Fehlermeldung

3. Generiere Ephemeral Key EK_A (einmalig für diese Session)

4. Berechne 4 DH-Operationen:
   DH1 = DH(IK_A.priv, SPK_B.pub)
   DH2 = DH(EK_A.priv, IK_B.pub)
   DH3 = DH(EK_A.priv, SPK_B.pub)
   DH4 = DH(EK_A.priv, OPK_B.pub)   ← entfällt wenn kein OPK mehr verfügbar

5. Master Secret = HKDF(DH1 || DH2 || DH3 || DH4)

6. Leite aus Master Secret ab:
   Root Key (RK)     → startet den Double Ratchet
   Chain Key (CK)    → erste Sending Chain

7. Schicke mit erster Nachricht: { IK_A.pub, EK_A.pub, OPK_B.id, ciphertext }
```

### Erster Kontakt (Empfänger)

```
1. Empfange: { IK_A.pub, EK_A.pub, OPK_B.id, ciphertext }
2. Führe dieselben 4 DH-Operationen durch (mit eigenen Private Keys)
3. Kommt auf exakt dasselbe Master Secret → selber Root Key, selbe Chain Key
4. Entschlüsselt die Nachricht
```

Der Server hat zu keinem Zeitpunkt die Information, das Master Secret zu berechnen — er kennt keine Private Keys.

---

## Phase 2 — Double Ratchet (laufende Kommunikation)

### Aufbau

```
Root Key (RK)
    │
    ├─→ Sending Chain Key (CK_s)
    │       │
    │       ├─→ Message Key 1 → verschlüsselt Nachricht 1 → wird sofort gelöscht
    │       ├─→ Message Key 2 → verschlüsselt Nachricht 2 → wird sofort gelöscht
    │       └─→ Message Key N → ...
    │
    └─→ Receiving Chain Key (CK_r)
            │
            └─→ Message Key 1, 2, N ...
```

### DH Ratchet Step (bei jeder gesendeten Nachricht)

```
1. Generiere neues DH Keypair (ratchet_pub, ratchet_priv)
2. DH_out = DH(ratchet_priv, their_ratchet_pub)
3. (neuer RK, neuer CK_s) = HKDF(alter_RK, DH_out)
4. Hänge ratchet_pub an die Nachricht
5. Lösche alten ratchet_priv sofort
```

**Effekt:** Selbst wenn ein Angreifer den aktuellen State kompromittiert, verliert er nach dem nächsten DH-Step wieder den Zugriff — Break-in Recovery.

### Symmetric Ratchet Step (Message Key ableiten)

```
Message Key  = HKDF(CK, "message")
neuer CK     = HKDF(CK, "chain")
alter CK     → sofort löschen
```

### Was mit jeder Nachricht mitgeschickt wird

```json
{
  "sender_device_id": "device_a",
  "recipient_device_id": "device_b",
  "ciphertext": "<base64>",
  "nonce": "<base64, 24 bytes>",
  "ratchet_pub": "<base64, aktueller DH Ratchet Public Key>",
  "message_index": 42,
  "timestamp": "2025-04-20T18:42:00Z"
}
```

**Niemals in der Payload:** Plaintext, Private Keys, Master Secret, Chain Keys, Message Keys.

---

## Key Storage — Android

### Identity Key (langlebig)

```kotlin
// Generierung — einmalig, Hardware-backed wenn verfügbar
val keyPairGenerator = KeyPairGenerator.getInstance(
    KeyProperties.KEY_ALGORITHM_EC,
    "AndroidKeyStore"
)
keyPairGenerator.initialize(
    KeyGenParameterSpec.Builder(
        "identity_key",
        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
    )
    .setDigests(KeyProperties.DIGEST_SHA256)
    .setUserAuthenticationRequired(false) // optional: auf true setzen für Biometrie
    .setIsStrongBoxBacked(true) // nutzt StrongBox/Titan M wenn verfügbar
    .build()
)
val keyPair = keyPairGenerator.generateKeyPair()
// keyPair.private verlässt den Keystore nie
// keyPair.public → an Server schicken
```

### Ratchet State (Session-Daten)

```kotlin
// EncryptedSharedPreferences — AES256-GCM, Key im Android Keystore
val masterKey = MasterKey.Builder(context)
    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
    .build()

val prefs = EncryptedSharedPreferences.create(
    context,
    "ratchet_state",
    masterKey,
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
)
// Speichert: Root Key, Chain Keys, Ratchet Keypairs, Message Counter
// Niemals im normalen SharedPreferences oder einer unverschlüsselten DB
```

### One-Time PreKeys

```kotlin
// Generiert mit libsodium, privater Teil in EncryptedSharedPreferences
// Wird nach einmaligem Gebrauch sofort gelöscht:
prefs.edit().remove("opk_${opkId}").apply()
```

---

## Backend — Minimale Datenhaltung

Der Server ist ein **dummer Postbote** — er versteht den Inhalt nicht.

### Was der Server speichert

```python
# Device Registration
{
    "device_id": str,
    "display_name": str,
    "identity_key_pub": str,       # base64 Curve25519
    "signed_prekey_pub": str,      # base64
    "signed_prekey_sig": str,      # base64, signiert mit identity_key
    "signed_prekey_id": int,
    "one_time_prekeys": [          # Liste, wird nach Gebrauch gelöscht
        {"id": int, "pub": str},
        ...
    ]
}

# Message (nach Delivery sofort löschen)
{
    "message_id": str,
    "sender_device_id": str,
    "recipient_device_id": str,
    "ciphertext": str,             # base64
    "nonce": str,                  # base64
    "ratchet_pub": str,            # base64
    "message_index": int,
    "timestamp": datetime,
    "delivered": bool
}
```

### Neue Backend-Endpoints

```
POST /devices/register          → speichert PreKey Bundle
GET  /devices/{id}/prekeys      → gibt PreKey Bundle zurück (holt 1 OPK und löscht sie)
POST /prekeys/replenish         → Client schickt neue OPKs wenn Vorrat niedrig
POST /messages/send             → nimmt verschlüsselte Nachricht an
GET  /messages/pending/{id}     → gibt ausstehende Nachrichten zurück
POST /messages/ack              → löscht Nachricht vom Server nach Empfang
```

**Wichtig:** `GET /devices/{id}/prekeys` verbraucht genau eine OPK und löscht sie serverseitig. Sind keine OPKs mehr da, wird nur `{IK, SPK}` zurückgegeben (X3DH ohne OPK — etwas schwächer aber funktional).

---

## Key Verification — Safety Numbers

Ohne Verifikation der Identity Keys ist ein kompromittierter Server in der Lage, MITM-Angriffe durchzuführen.

### Implementierung

```kotlin
// Safety Number = SHA256(IK_A.pub || IK_B.pub), dargestellt als lesbare Zahl
fun computeSafetyNumber(myIdentityKey: ByteArray, theirIdentityKey: ByteArray): String {
    val input = myIdentityKey + theirIdentityKey
    val hash = MessageDigest.getInstance("SHA-256").digest(input)
    // In 12 Blöcke à 5 Ziffern aufteilen (wie Signal)
    return hash.take(30).chunked(5).joinToString(" ") { bytes ->
        (bytes.fold(0L) { acc, b -> acc * 256 + (b.toLong() and 0xFF) } % 100000)
            .toString().padStart(5, '0')
    }
}
```

### UX

- In den Chat-Settings: **"Sicherheitsnummer vergleichen"**
- Zeigt einen QR-Code und eine 60-stellige Zahl
- User vergleichen live oder via anderem Kanal (Telefon, persönlich)
- Wenn die Zahlen übereinstimmen: **"Verifiziert ✓"** — MITM ausgeschlossen
- Optional: bei Keychange des Partners warnen ("Sicherheitsnummer hat sich geändert")

---

## UX — Entschlüsselungs-Animation

### Flow

```
Nachricht kommt an
        ↓
Bubble zeigt zufälligen Kauderwelsch (gleiche Länge wie Ciphertext-Preview)
z.B.: "▒▒▒▒▒ ▒▒▒▒▒▒▒▒▒ ▒▒▒▒"
        ↓
User tippt auf die Nachricht
        ↓
decrypt() wird aufgerufen → < 1ms (AES ist hardware-accelerated)
        ↓
Scramble-Animation startet mit dem echten Plaintext
Zeichen für Zeichen links→rechts aufgedeckt, ~30ms pro Zeichen
Noch nicht aufgedeckte Zeichen = zufällige ASCII-Zeichen die "flackern"
        ↓
Vollständiger Text erscheint — Nachricht bleibt entschlüsselt bis App minimiert
```

### Android-Implementierung (Kotlin)

```kotlin
fun animateDecrypt(textView: TextView, plaintext: String) {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%"
    val duration = 30L // ms pro Zeichen
    
    plaintext.indices.forEach { index ->
        // Scramble-Phase für dieses Zeichen
        repeat(5) { tick ->
            textView.postDelayed({
                val current = buildString {
                    plaintext.indices.forEach { i ->
                        append(when {
                            i < index -> plaintext[i]           // bereits aufgedeckt
                            i == index -> chars.random()        // flackert
                            else -> chars.random()              // noch verborgen
                        })
                    }
                }
                textView.text = current
            }, index * duration + tick * 6)
        }
        // Finales Zeichen setzen
        textView.postDelayed({
            // setzt das finale Zeichen an Position index
        }, index * duration + 30)
    }
}
```

---

## Forward Secrecy — Zusammenfassung

| Szenario | Schutz |
|---|---|
| Server wird gehackt | Nur Ciphertext — nutzlos ohne Private Keys |
| Heutiger Key wird gestohlen | Vergangene Nachrichten sicher (Forward Secrecy) |
| Zukünftige Nachrichten nach Key-Diebstahl | Sicher nach nächstem DH Ratchet Step (Break-in Recovery) |
| MITM beim Key Exchange | Erkennbar via Safety Numbers |
| Replay-Attacke | Message Index + Nonce verhindern Wiederholung |
| OPK aufgebraucht | X3DH funktioniert auch ohne — leicht schwächer |
| Gerät verloren/gestohlen | Private Keys in Hardware-Keystore, nicht extrahierbar |

---

## Implementierungs-Reihenfolge

1. **libsodium einbinden** — `libsodium-jni` als Gradle-Dependency
2. **Android Keystore** — Identity Key generieren und sicher speichern
3. **PreKey Bundle System** — Generierung, Signierung, Upload ans Backend
4. **Backend-Endpoints** — PreKey Bundle abrufen, OPKs verwalten
5. **X3DH Initial Handshake** — erster Kontakt zwischen zwei Usern
6. **Double Ratchet** — laufende Verschlüsselung nach Handshake
7. **EncryptedSharedPreferences** — Ratchet State persistieren
8. **Safety Numbers UI** — Verifikations-Screen in Chat-Settings
9. **Scramble-Animation** — decrypt-on-tap mit Animation
10. **OPK Replenishment** — Client schickt automatisch neue OPKs wenn < 5 übrig

---

## Abhängigkeiten (Gradle)

```groovy
// libsodium für Android
implementation 'com.goterl:lazysodium-android:5.1.0@aar'
implementation 'net.java.dev.jna:jna:5.13.0@aar'

// Encrypted Storage
implementation 'androidx.security:security-crypto:1.1.0-alpha06'

// Optional: Biometrie für Key-Freigabe
implementation 'androidx.biometric:biometric:1.2.0-alpha05'
```

---

## Was dieser Plan gegenüber dem Original verbessert

| Original | Dieser Plan |
|---|---|
| Einfaches ECDH | X3DH — funktioniert auch wenn Empfänger offline ist |
| Symmetric Ratchet (HKDF-Kette) | Double Ratchet — zusätzlich Break-in Recovery |
| Kein MITM-Schutz | Safety Numbers zur Key-Verifikation |
| libsodium-Snippet ohne Detail | Vollständiger Implementierungsplan |
| Kein OPK-Management | OPK Replenishment-System |
| Allgemeine Key Storage Erwähnung | Konkreter Android Keystore + EncryptedSharedPreferences Code |
| XSalsa20 als AES-256-GCM bezeichnet | Korrekt: XSalsa20-Poly1305 via `crypto_secretbox_easy` |
