# Phase 7: Privacy and Security Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Phase 7 Privacy and Security for NoteFlowAI, including OS-level backup exclusions, SafeLogger secret redaction, Local-Only hard network isolation firewall, AES-256-GCM encrypted backup container export/import (`.enc.zip`), granular derived data deletion vs note cascade, and Privacy Dashboard UI.

**Architecture:** A multi-layered privacy architecture combining Android Keystore-backed SQLCipher encryption, an OkHttp network interceptor firewall enforcing local-only mode, PBKDF2 (100k iterations) + AES-256-GCM encrypted archive containers, granular Room database purge routines that safeguard raw user markdown notes, and comprehensive logging redaction.

**Tech Stack:** Kotlin, Android Keystore, SQLCipher, Room, Jetpack Compose, OkHttp Interceptors, Java Cryptography Architecture (`Cipher`, `SecretKeyFactory`, `GCMParameterSpec`, `PBEKeySpec`, `SecureRandom`), DataStore Preferences, JUnit4, Robolectric, MockK.

## Global Constraints

- Android Keystore MasterKey scheme: `AES256_GCM`.
- Database passphrase storage: `EncryptedSharedPreferences` with `PrefKeyEncryptionScheme.AES256_SIV` and `PrefValueEncryptionScheme.AES256_GCM`.
- Encrypted backup magic header: ASCII `"NFENC01"` (7 bytes).
- PBKDF2 parameters: `PBKDF2WithHmacSHA256`, 100,000 iterations, 16-byte random salt, 256-bit AES key.
- AES-GCM parameters: `AES/GCM/NoPadding`, 12-byte random IV, 128-bit authentication tag.
- Raw user markdown note files in `context.filesDir/notes/` must NEVER be modified or deleted during "Delete Derived Data" / "Purge Derived Memory Data".
- Plaintext database passphrases, API keys, or private tokens must NEVER be exported in backups, logged to logcat, or saved in unencrypted preferences.
- When `isLocalOnlyMode` is true, all external HTTP/WebSocket egress to cloud AI/TTS/Search providers must be blocked with `LocalOnlyBlockedException`. Local loopback hosts (`localhost`, `127.0.0.1`, `10.0.2.2`) remain permitted for local Ollama / LM Studio.

---

### Task 1: OS-Level Backup Rules and XML Hardening

**Files:**
- Modify: `app/src/main/res/xml/backup_rules.xml`
- Modify: `app/src/main/res/xml/data_extraction_rules.xml`
- Test: `app/src/test/java/com/noteflowai/app/data/security/BackupRulesXmlTest.kt`

**Interfaces:**
- Consumes: Android XML resource specifications for Android 12+ data extraction and legacy backup rules.
- Produces: Hardened XML configuration preventing cloud backup, ADB extraction, or device-to-device transfers of databases, shared preferences, and files.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/noteflowai/app/data/security/BackupRulesXmlTest.kt`:

```kotlin
package com.noteflowai.app.data.security

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackupRulesXmlTest {

    @Test
    fun `backup_rules xml excludes sharedpref, database, file, and root`() {
        val file = File("src/main/res/xml/backup_rules.xml")
        assertTrue("backup_rules.xml must exist", file.exists())
        val content = file.readText()
        assertTrue(content.contains("""<exclude domain="sharedpref" path="." />"""))
        assertTrue(content.contains("""<exclude domain="database" path="." />"""))
        assertTrue(content.contains("""<exclude domain="file" path="." />"""))
        assertTrue(content.contains("""<exclude domain="root" path="." />"""))
    }

    @Test
    fun `data_extraction_rules xml excludes sharedpref, database, file, and root for cloud and device-transfer`() {
        val file = File("src/main/res/xml/data_extraction_rules.xml")
        assertTrue("data_extraction_rules.xml must exist", file.exists())
        val content = file.readText()
        assertTrue(content.contains("<cloud-backup>"))
        assertTrue(content.contains("<device-transfer>"))
        assertTrue(content.contains("""<exclude domain="sharedpref" path="." />"""))
        assertTrue(content.contains("""<exclude domain="database" path="." />"""))
        assertTrue(content.contains("""<exclude domain="file" path="." />"""))
        assertTrue(content.contains("""<exclude domain="root" path="." />"""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.security.BackupRulesXmlTest"`
Expected: FAIL due to missing `<exclude domain="database" path="." />` and `<exclude domain="root" path="." />` in `backup_rules.xml`.

- [ ] **Step 3: Update backup_rules.xml and data_extraction_rules.xml**

Update `app/src/main/res/xml/backup_rules.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<full-backup-content>
    <!-- Exclude all private data from ADB and cloud backups -->
    <exclude domain="sharedpref" path="." />
    <exclude domain="database" path="." />
    <exclude domain="file" path="." />
    <exclude domain="root" path="." />
</full-backup-content>
```

Update `app/src/main/res/xml/data_extraction_rules.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="sharedpref" path="." />
        <exclude domain="database" path="." />
        <exclude domain="file" path="." />
        <exclude domain="root" path="." />
    </cloud-backup>
    <device-transfer>
        <exclude domain="sharedpref" path="." />
        <exclude domain="database" path="." />
        <exclude domain="file" path="." />
        <exclude domain="root" path="." />
    </device-transfer>
</data-extraction-rules>
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.security.BackupRulesXmlTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/xml/backup_rules.xml app/src/main/res/xml/data_extraction_rules.xml app/src/test/java/com/noteflowai/app/data/security/BackupRulesXmlTest.kt
git commit -m "feat(security): harden os-level backup and data extraction rules"
```

---

### Task 2: SafeLogger Sensitive Data Redaction Utility

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/util/SafeLogger.kt`
- Test: `app/src/test/java/com/noteflowai/app/util/SafeLoggerTest.kt`

**Interfaces:**
- Consumes: String logging inputs and throwable stack traces.
- Produces: Redacted log strings preventing API keys (`sk-...`, Bearer tokens, Deepgram keys), passwords, and auth headers from leaking into Android logcat.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/noteflowai/app/util/SafeLoggerTest.kt`:

```kotlin
package com.noteflowai.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SafeLoggerTest {

    @Test
    fun `redact removes openai api keys`() {
        val input = "Request to OpenAI with key sk-abcdef1234567890abcdef123456 failed"
        val result = SafeLogger.redact(input)
        assertFalse(result.contains("sk-abcdef1234567890abcdef123456"))
        assertEquals("Request to OpenAI with key [REDACTED_API_KEY] failed", result)
    }

    @Test
    fun `redact removes bearer tokens`() {
        val input = "Header Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.doNotLeakThis"
        val result = SafeLogger.redact(input)
        assertFalse(result.contains("doNotLeakThis"))
        assertEquals("Header Authorization: Bearer [REDACTED_TOKEN]", result)
    }

    @Test
    fun `redact removes deepgram 40-char hex keys`() {
        val input = "Deepgram client initialized with key 1234567890abcdef1234567890abcdef12345678"
        val result = SafeLogger.redact(input)
        assertFalse(result.contains("1234567890abcdef1234567890abcdef12345678"))
        assertEquals("Deepgram client initialized with key [REDACTED_API_KEY]", result)
    }

    @Test
    fun `redact removes password and passphrase json fields`() {
        val input = """{"username":"user1", "password":"superSecretPassword123", "passphrase":"dbPassphrase456"}"""
        val result = SafeLogger.redact(input)
        assertFalse(result.contains("superSecretPassword123"))
        assertFalse(result.contains("dbPassphrase456"))
        assertEquals("""{"username":"user1", "password":"[REDACTED_SECRET]", "passphrase":"[REDACTED_SECRET]"}""", result)
    }

    @Test
    fun `redact leaves non-sensitive messages untouched`() {
        val input = "Retrieved 15 notes for query 'project meeting'"
        val result = SafeLogger.redact(input)
        assertEquals(input, result)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.util.SafeLoggerTest"`
Expected: FAIL with Unresolved reference: SafeLogger

- [ ] **Step 3: Implement SafeLogger**

Create `app/src/main/java/com/noteflowai/app/util/SafeLogger.kt`:

```kotlin
package com.noteflowai.app.util

import android.util.Log

/**
 * Privacy-preserving logging utility that automatically redacts API keys,
 * bearer tokens, credentials, and passwords before logging to logcat.
 */
object SafeLogger {

    private val OPENAI_KEY_PATTERN = Regex("""sk-[A-Za-z0-9-_]{20,}""")
    private val BEARER_TOKEN_PATTERN = Regex("""Bearer\s+[A-Za-z0-9-_.]+(?=[^A-Za-z0-9-_.]|$)""")
    private val HEX_KEY_PATTERN = Regex("""(?<=\s|^)[a-f0-9]{40}(?=\s|$)""")
    private val PASSWORD_JSON_PATTERN = Regex("""("password"|"passphrase")\s*:\s*"[^"]+"""")

    fun redact(message: String?): String {
        if (message.isNullOrEmpty()) return ""
        var sanitized = message
        sanitized = OPENAI_KEY_PATTERN.replace(sanitized, "[REDACTED_API_KEY]")
        sanitized = BEARER_TOKEN_PATTERN.replace(sanitized, "Bearer [REDACTED_TOKEN]")
        sanitized = HEX_KEY_PATTERN.replace(sanitized, "[REDACTED_API_KEY]")
        sanitized = PASSWORD_JSON_PATTERN.replace(sanitized) { matchResult ->
            val key = matchResult.groupValues[1]
            """$key:"[REDACTED_SECRET]""""
        }
        return sanitized
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, redact(msg))
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, redact(msg))
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) {
            Log.w(tag, redact(msg), tr)
        } else {
            Log.w(tag, redact(msg))
        }
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) {
            Log.e(tag, redact(msg), tr)
        } else {
            Log.e(tag, redact(msg))
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.util.SafeLoggerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/util/SafeLogger.kt app/src/test/java/com/noteflowai/app/util/SafeLoggerTest.kt
git commit -m "feat(security): add SafeLogger with secret and key redaction"
```

---

### Task 3: Local-Only Mode Network Firewall Gate & Interceptor

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/network/LocalOnlyBlockedException.kt`
- Create: `app/src/main/java/com/noteflowai/app/data/network/LocalOnlyNetworkInterceptor.kt`
- Modify: `app/src/main/java/com/noteflowai/app/data/settings/SettingsManager.kt`
- Modify: `app/src/main/java/com/noteflowai/app/data/NetworkModule.kt`
- Test: `app/src/test/java/com/noteflowai/app/data/network/LocalOnlyNetworkInterceptorTest.kt`

**Interfaces:**
- Consumes: `SettingsManager.isLocalOnlyMode` state / supplier.
- Produces: `LocalOnlyNetworkInterceptor` registered in `NetworkModule.newClientBuilder()` that blocks any egress to external cloud hosts when Local-Only mode is enabled.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/noteflowai/app/data/network/LocalOnlyNetworkInterceptorTest.kt`:

```kotlin
package com.noteflowai.app.data.network

import okhttp3.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class LocalOnlyNetworkInterceptorTest {

    private fun createChain(url: String, interceptor: LocalOnlyNetworkInterceptor): Response {
        val request = Request.Builder().url(url).build()
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(null, "success"))
                    .build()
            })
            .build()
        return client.newCall(request).execute()
    }

    @Test
    fun `external cloud api is blocked when local-only mode is enabled`() {
        val interceptor = LocalOnlyNetworkInterceptor(isLocalOnlyProvider = { true })
        assertThrows(LocalOnlyBlockedException::class.java) {
            createChain("https://api.openai.com/v1/chat/completions", interceptor)
        }
    }

    @Test
    fun `anthropic and deepgram endpoints are blocked when local-only mode is enabled`() {
        val interceptor = LocalOnlyNetworkInterceptor(isLocalOnlyProvider = { true })
        assertThrows(LocalOnlyBlockedException::class.java) {
            createChain("https://api.anthropic.com/v1/messages", interceptor)
        }
        assertThrows(LocalOnlyBlockedException::class.java) {
            createChain("https://api.deepgram.com/v1/speak", interceptor)
        }
    }

    @Test
    fun `local loopback host 10_0_2_2 is permitted even when local-only mode is enabled`() {
        val interceptor = LocalOnlyNetworkInterceptor(isLocalOnlyProvider = { true })
        val response = createChain("http://10.0.2.2:11434/api/chat", interceptor)
        assertEquals(200, response.code)
    }

    @Test
    fun `local localhost and 127_0_0_1 are permitted when local-only mode is enabled`() {
        val interceptor = LocalOnlyNetworkInterceptor(isLocalOnlyProvider = { true })
        val response1 = createChain("http://127.0.0.1:11434/api/tags", interceptor)
        val response2 = createChain("http://localhost:8080/completion", interceptor)
        assertEquals(200, response1.code)
        assertEquals(200, response2.code)
    }

    @Test
    fun `all endpoints are permitted when local-only mode is disabled`() {
        val interceptor = LocalOnlyNetworkInterceptor(isLocalOnlyProvider = { false })
        val response = createChain("https://api.openai.com/v1/chat/completions", interceptor)
        assertEquals(200, response.code)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.network.LocalOnlyNetworkInterceptorTest"`
Expected: FAIL with Unresolved reference: LocalOnlyNetworkInterceptor

- [ ] **Step 3: Implement LocalOnlyBlockedException and LocalOnlyNetworkInterceptor**

Create `app/src/main/java/com/noteflowai/app/data/network/LocalOnlyBlockedException.kt`:

```kotlin
package com.noteflowai.app.data.network

import java.io.IOException

/**
 * Thrown when an outbound network call is blocked by Local-Only Mode isolation.
 */
class LocalOnlyBlockedException(
    val host: String,
    message: String = "Outbound network call to '$host' blocked: Local-Only mode is enabled."
) : IOException(message)
```

Create `app/src/main/java/com/noteflowai/app/data/network/LocalOnlyNetworkInterceptor.kt`:

```kotlin
package com.noteflowai.app.data.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Interceptor that enforces hard network isolation when Local-Only mode is active.
 * Permits only local loopback endpoints (127.0.0.1, localhost, 10.0.2.2) for on-device/local servers.
 */
class LocalOnlyNetworkInterceptor(
    private val isLocalOnlyProvider: () -> Boolean
) : Interceptor {

    companion object {
        private val LOCAL_HOSTS = setOf("127.0.0.1", "localhost", "10.0.2.2")
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val isLocalOnly = isLocalOnlyProvider()
        val request = chain.request()
        val host = request.url.host.lowercase()

        if (isLocalOnly && !LOCAL_HOSTS.contains(host)) {
            throw LocalOnlyBlockedException(host)
        }

        return chain.proceed(request)
    }
}
```

- [ ] **Step 4: Update SettingsManager and NetworkModule**

In `app/src/main/java/com/noteflowai/app/data/settings/SettingsManager.kt`, add `IS_LOCAL_ONLY_MODE`:
```kotlin
        val IS_LOCAL_ONLY_MODE = booleanPreferencesKey("is_local_only_mode")
```
Add property accessors:
```kotlin
    val isLocalOnlyMode: Flow<Boolean> = dataStore.data.map { it[IS_LOCAL_ONLY_MODE] ?: false }
    val isLocalOnlyModeBlocking: Boolean
        get() = runBlocking { dataStore.data.first()[IS_LOCAL_ONLY_MODE] ?: false }

    suspend fun setLocalOnlyMode(enabled: Boolean) {
        dataStore.edit { it[IS_LOCAL_ONLY_MODE] = enabled }
    }
```

In `app/src/main/java/com/noteflowai/app/data/NetworkModule.kt`, add global interceptor support:
```kotlin
package com.noteflowai.app.data

import com.noteflowai.app.data.network.LocalOnlyNetworkInterceptor
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object NetworkModule {
    private const val CONNECT_TIMEOUT_SEC = 30L

    private val sharedPool = ConnectionPool(5, 5, TimeUnit.MINUTES)

    @Volatile
    private var localOnlyChecker: (() -> Boolean)? = null

    fun setLocalOnlyChecker(checker: () -> Boolean) {
        localOnlyChecker = checker
    }

    fun newClientBuilder(): OkHttpClient.Builder {
        val builder = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .connectionPool(sharedPool)

        localOnlyChecker?.let { checker ->
            builder.addInterceptor(LocalOnlyNetworkInterceptor(checker))
        }

        return builder
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.network.LocalOnlyNetworkInterceptorTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/network/LocalOnlyBlockedException.kt app/src/main/java/com/noteflowai/app/data/network/LocalOnlyNetworkInterceptor.kt app/src/main/java/com/noteflowai/app/data/settings/SettingsManager.kt app/src/main/java/com/noteflowai/app/data/NetworkModule.kt app/src/test/java/com/noteflowai/app/data/network/LocalOnlyNetworkInterceptorTest.kt
git commit -m "feat(security): implement Local-Only network isolation firewall interceptor"
```

---

### Task 4: AES-256-GCM Encrypted Backup Container (`.enc.zip`) in BackupManager

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/data/BackupManager.kt`
- Test: `app/src/test/java/com/noteflowai/app/data/EncryptedBackupContainerTest.kt`

**Interfaces:**
- Consumes: User passphrase, `PBKDF2WithHmacSHA256` key derivation, `AES/GCM/NoPadding` cipher.
- Produces: `BackupManager.createBackup(..., password = ...)` returning `.enc.zip` (or `.zip`), and `BackupManager.restoreBackup(..., password = ...)` with authenticated GCM verification.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/noteflowai/app/data/EncryptedBackupContainerTest.kt`:

```kotlin
package com.noteflowai.app.data

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

class EncryptedBackupContainerTest {

    private val testPayload = "Hello NoteFlowAI Encrypted Container Content!".toByteArray(Charsets.UTF_8)
    private val correctPassword = "CorrectPassword123!"
    private val wrongPassword = "WrongPassword456!"

    @Test
    fun `encrypt and decrypt round trip with valid password succeeds`() {
        val encryptedOut = ByteArrayOutputStream()
        BackupManager.encryptContainer(
            inputStream = ByteArrayInputStream(testPayload),
            outputStream = encryptedOut,
            password = correctPassword
        )

        val ciphertextWithHeader = encryptedOut.toByteArray()
        assertTrue("Ciphertext must be larger than payload + header", ciphertextWithHeader.size > testPayload.size + 35)

        // Verify magic header "NFENC01"
        val magic = String(ciphertextWithHeader.copyOfRange(0, 7), Charsets.US_ASCII)
        assertEquals("NFENC01", magic)

        val decryptedOut = ByteArrayOutputStream()
        BackupManager.decryptContainer(
            inputStream = ByteArrayInputStream(ciphertextWithHeader),
            outputStream = decryptedOut,
            password = correctPassword
        )

        assertArrayEquals(testPayload, decryptedOut.toByteArray())
    }

    @Test
    fun `decrypt with wrong password throws exception or returns failure`() {
        val encryptedOut = ByteArrayOutputStream()
        BackupManager.encryptContainer(
            inputStream = ByteArrayInputStream(testPayload),
            outputStream = encryptedOut,
            password = correctPassword
        )

        val ciphertextWithHeader = encryptedOut.toByteArray()
        val decryptedOut = ByteArrayOutputStream()

        assertThrows(Exception::class.java) {
            BackupManager.decryptContainer(
                inputStream = ByteArrayInputStream(ciphertextWithHeader),
                outputStream = decryptedOut,
                password = wrongPassword
            )
        }
    }

    @Test
    fun `decrypt with invalid magic header throws IllegalArgumentException`() {
        val invalidHeaderData = "NOTENCO".toByteArray(Charsets.US_ASCII) + ByteArray(50)
        val decryptedOut = ByteArrayOutputStream()

        assertThrows(IllegalArgumentException::class.java) {
            BackupManager.decryptContainer(
                inputStream = ByteArrayInputStream(invalidHeaderData),
                outputStream = decryptedOut,
                password = correctPassword
            )
        }
    }

    @Test
    fun `tampered ciphertext fails authenticated GCM tag verification`() {
        val encryptedOut = ByteArrayOutputStream()
        BackupManager.encryptContainer(
            inputStream = ByteArrayInputStream(testPayload),
            outputStream = encryptedOut,
            password = correctPassword
        )

        val tamperedData = encryptedOut.toByteArray()
        // Flip one byte in the ciphertext payload
        tamperedData[tamperedData.size - 1] = (tamperedData[tamperedData.size - 1].toInt() xor 0xFF).toByte()

        val decryptedOut = ByteArrayOutputStream()
        assertThrows(Exception::class.java) {
            BackupManager.decryptContainer(
                inputStream = ByteArrayInputStream(tamperedData),
                outputStream = decryptedOut,
                password = correctPassword
            )
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.EncryptedBackupContainerTest"`
Expected: FAIL with Unresolved reference: encryptContainer / decryptContainer in BackupManager

- [ ] **Step 3: Implement Encrypted Backup Container in BackupManager**

Update `app/src/main/java/com/noteflowai/app/data/BackupManager.kt`:

Add constants and static crypto helper methods in companion object:
```kotlin
    companion object {
        const val MAGIC_HEADER = "NFENC01"
        private const val SALT_LENGTH_BYTES = 16
        private const val IV_LENGTH_BYTES = 12
        private const val KEY_LENGTH_BITS = 256
        private const val PBKDF2_ITERATIONS = 100_000
        private const val GCM_TAG_LENGTH_BITS = 128

        fun encryptContainer(
            inputStream: InputStream,
            outputStream: OutputStream,
            password: String
        ) {
            val random = SecureRandom()
            val salt = ByteArray(SALT_LENGTH_BYTES).also { random.nextBytes(it) }
            val iv = ByteArray(IV_LENGTH_BYTES).also { random.nextBytes(it) }

            val keySpec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
            val secretKeyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val keyBytes = secretKeyFactory.generateSecret(keySpec).encoded
            val secretKey = SecretKeySpec(keyBytes, "AES")

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)

            // Write Header: MAGIC (7 bytes) + Salt (16 bytes) + IV (12 bytes)
            outputStream.write(MAGIC_HEADER.toByteArray(Charsets.US_ASCII))
            outputStream.write(salt)
            outputStream.write(iv)

            // Encrypt and write payload
            CipherOutputStream(outputStream, cipher).use { cos ->
                inputStream.copyTo(cos)
            }
        }

        fun decryptContainer(
            inputStream: InputStream,
            outputStream: OutputStream,
            password: String
        ) {
            val magicBytes = ByteArray(7)
            val readMagic = inputStream.read(magicBytes)
            if (readMagic != 7 || String(magicBytes, Charsets.US_ASCII) != MAGIC_HEADER) {
                throw IllegalArgumentException("Invalid encrypted backup archive header")
            }

            val salt = ByteArray(SALT_LENGTH_BYTES)
            if (inputStream.read(salt) != SALT_LENGTH_BYTES) {
                throw IllegalArgumentException("Corrupted backup header: incomplete salt")
            }

            val iv = ByteArray(IV_LENGTH_BYTES)
            if (inputStream.read(iv) != IV_LENGTH_BYTES) {
                throw IllegalArgumentException("Corrupted backup header: incomplete IV")
            }

            val keySpec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
            val secretKeyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val keyBytes = secretKeyFactory.generateSecret(keySpec).encoded
            val secretKey = SecretKeySpec(keyBytes, "AES")

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

            CipherInputStream(inputStream, cipher).use { cis ->
                cis.copyTo(outputStream)
            }
        }
    }
```
Update `createBackup` to accept `password: String? = null`. If password is provided, stream intermediate zip into encrypted container `.enc.zip`.
Update `restoreBackup` to accept `password: String? = null`. If input file starts with `NFENC01`, decrypt into a temporary zip file first using `decryptContainer`, restore data, and securely delete temporary file.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.EncryptedBackupContainerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/BackupManager.kt app/src/test/java/com/noteflowai/app/data/EncryptedBackupContainerTest.kt
git commit -m "feat(security): add AES-256-GCM PBKDF2 encrypted backup container support"
```

---

### Task 5: Granular Data Deletion Controls (Derived Data vs Source Note Cascade)

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/data/memory/repository/MemoryRepository.kt`
- Modify: `app/src/main/java/com/noteflowai/app/data/memory/repository/SourceSegmentManager.kt`
- Modify: `app/src/main/java/com/noteflowai/app/data/NoteRepository.kt`
- Test: `app/src/test/java/com/noteflowai/app/data/security/GranularDeletionTest.kt`

**Interfaces:**
- Consumes: Room database DAOs, note storage directories.
- Produces:
  - `purgeDerivedMemoryData()`: purges all derived segments, entities, mentions, timeline entries, citations, relations, reviews, conflicts, and processing status while preserving raw markdown files in `filesDir/notes/`.
  - `deleteSourceNoteWithCascade(fileName)`: deletes the note file and cascades database deletions.
  - `clearTtsCache(context)`: deletes cached TTS audio.
  - `clearTempFiles(context)`: deletes scratchpad audio and temp files.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/noteflowai/app/data/security/GranularDeletionTest.kt`:

```kotlin
package com.noteflowai.app.data.security

import com.noteflowai.app.data.memory.db.MemoryDatabase
import com.noteflowai.app.data.memory.repository.MemoryRepository
import com.noteflowai.app.data.memory.repository.SourceSegmentManager
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GranularDeletionTest {

    @Test
    fun `purgeDerivedMemoryData clears room tables but preserves raw note files`() = runBlocking {
        val db = mockk<MemoryDatabase>(relaxed = true)
        val memRepo = MemoryRepository(db)
        
        // Mock clear methods
        coEvery { db.sourceSegmentDao().clearAll() } just Runs
        coEvery { db.entityMentionDao().clearAll() } just Runs
        coEvery { db.timelineEntryDao().clearAll() } just Runs
        coEvery { db.memoryObjectDao().clearAll() } just Runs
        coEvery { db.decisionDao().clearAll() } just Runs
        coEvery { db.commitmentDao().clearAll() } just Runs
        coEvery { db.memoryRelationDao().clearAll() } just Runs
        coEvery { db.memoryReviewItemDao().clearAll() } just Runs
        coEvery { db.answerCitationDao().clearAll() } just Runs
        coEvery { db.conflictDao().clearAll() } just Runs
        coEvery { db.processingStatusDao().clearAll() } just Runs

        memRepo.purgeDerivedMemoryData()

        coVerify { db.sourceSegmentDao().clearAll() }
        coVerify { db.timelineEntryDao().clearAll() }
        coVerify { db.memoryObjectDao().clearAll() }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.security.GranularDeletionTest"`
Expected: FAIL with Unresolved reference: purgeDerivedMemoryData

- [ ] **Step 3: Implement purgeDerivedMemoryData and deletion helpers**

In `app/src/main/java/com/noteflowai/app/data/memory/repository/MemoryRepository.kt`:
```kotlin
    suspend fun purgeDerivedMemoryData() {
        db.sourceSegmentDao().clearAll()
        db.entityMentionDao().clearAll()
        db.timelineEntryDao().clearAll()
        db.memoryObjectDao().clearAll()
        db.decisionDao().clearAll()
        db.commitmentDao().clearAll()
        db.memoryRelationDao().clearAll()
        db.memoryReviewItemDao().clearAll()
        db.answerCitationDao().clearAll()
        db.conflictDao().clearAll()
        db.processingStatusDao().clearAll()
    }
```
Add `clearAll()` methods to DAOs where missing, or execute transaction across DAOs.
In `app/src/main/java/com/noteflowai/app/data/NoteRepository.kt`, add `deleteNoteWithCascade(fileName: String, memoryRepository: MemoryRepository)`.
Add cache clearing helper `PrivacyCacheUtils` (or in `MemoryRepository` / `SettingsManager`):
```kotlin
object PrivacyCacheUtils {
    fun clearTtsCache(context: Context): Long {
        var deletedBytes = 0L
        val ttsCacheDir = File(context.cacheDir, "tts_cache")
        if (ttsCacheDir.exists()) {
            ttsCacheDir.walkBottomUp().forEach {
                deletedBytes += it.length()
                it.delete()
            }
        }
        return deletedBytes
    }

    fun clearTempFiles(context: Context): Long {
        var deletedBytes = 0L
        context.cacheDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("temp_") || file.name.endsWith(".tmp") || file.name.endsWith(".wav")) {
                deletedBytes += file.length()
                file.deleteRecursively()
            }
        }
        return deletedBytes
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.security.GranularDeletionTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/memory/repository/MemoryRepository.kt app/src/main/java/com/noteflowai/app/data/NoteRepository.kt app/src/test/java/com/noteflowai/app/data/security/GranularDeletionTest.kt
git commit -m "feat(security): implement granular derived data purge and cascading deletion"
```

---

### Task 6: Privacy Dashboard UI & Navigation Integration

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/ui/screens/privacy/PrivacyDashboardScreen.kt`
- Modify: `app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt`
- Modify: `app/src/main/java/com/noteflowai/app/ui/NoteFlowApp.kt`
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/MemoryHubScreen.kt`
- Test: `app/src/test/java/com/noteflowai/app/viewmodel/PrivacyDashboardViewModelTest.kt`

**Interfaces:**
- Consumes: `MainViewModel` state flows (`isLocalOnlyMode`, cache sizes, encrypted backup triggers, derived data purge).
- Produces: Composable `PrivacyDashboardScreen` navigable via `Screen.PRIVACY_DASHBOARD` from Memory Hub and Settings.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/noteflowai/app/viewmodel/PrivacyDashboardViewModelTest.kt`:

```kotlin
package com.noteflowai.app.viewmodel

import com.noteflowai.app.data.settings.SettingsManager
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivacyDashboardViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `toggleLocalOnlyMode updates settings manager`() = runTest {
        val settingsManager = mockk<SettingsManager>(relaxed = true)
        coEvery { settingsManager.setLocalOnlyMode(true) } just Runs

        settingsManager.setLocalOnlyMode(true)

        coVerify { settingsManager.setLocalOnlyMode(true) }
    }
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.viewmodel.PrivacyDashboardViewModelTest"`
Expected: PASS

- [ ] **Step 3: Implement PrivacyDashboardScreen and wire navigation**

Create `app/src/main/java/com/noteflowai/app/ui/screens/privacy/PrivacyDashboardScreen.kt`:
- Security Status Card (Hardware-backed SQLCipher encryption active badge, OS backup excluded badge, Local-only mode toggle).
- Cache & Storage Card (TTS cache size + clear button, Temporary files size + clear button).
- Data Lifecycle Card (Purge Derived Data button with safety warning dialog, Create Encrypted Backup button with password prompt, Restore Backup button).

Update `app/src/main/java/com/noteflowai/app/ui/NoteFlowApp.kt`:
- Add `PRIVACY_DASHBOARD` to `Screen` enum.
- Add `when (currentScreen)` branch rendering `PrivacyDashboardScreen`.

Update `app/src/main/java/com/noteflowai/app/ui/screens/MemoryHubScreen.kt`:
- Add Privacy & Security Hub card with lock icon navigating to `Screen.PRIVACY_DASHBOARD`.

- [ ] **Step 4: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/ui/screens/privacy/PrivacyDashboardScreen.kt app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt app/src/main/java/com/noteflowai/app/ui/NoteFlowApp.kt app/src/main/java/com/noteflowai/app/ui/screens/MemoryHubScreen.kt app/src/test/java/com/noteflowai/app/viewmodel/PrivacyDashboardViewModelTest.kt
git commit -m "feat(ui): add Privacy Dashboard screen and navigation wiring"
```

---

### Task 7: Strings, Full Privacy Suite Regression, Build & Device Verification

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Test: All Unit Tests in project

**Interfaces:**
- Consumes: All Phase 7 components and string resources.
- Produces: Complete passing test suite, assembled APK, and deployed build.

- [ ] **Step 1: Add string resources**

In `app/src/main/res/values/strings.xml`, add all privacy-related strings:
- `privacy_dashboard_title`: "Privacy & Security"
- `privacy_local_only_title`: "Local-Only Mode"
- `privacy_local_only_desc`: "Block all outbound cloud requests. AI and TTS run 100% on-device."
- `privacy_encryption_status`: "Database Encryption"
- `privacy_encryption_desc`: "AES-256 SQLCipher backed by Android Keystore"
- `privacy_purge_derived_title`: "Purge Derived Data"
- `privacy_purge_derived_desc`: "Deletes embeddings, entity mentions, and timeline entries. Raw notes remain completely safe."
- `privacy_clear_tts`: "Clear TTS Audio Cache"
- `privacy_clear_temp`: "Clear Temporary Files"
- `privacy_create_encrypted_backup`: "Export Encrypted Backup (.enc.zip)"
- `privacy_password_prompt`: "Enter Backup Password"

- [ ] **Step 2: Run complete unit test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL with 100% passing tests (all ~300+ unit tests green).

- [ ] **Step 3: Assemble debug APK and verify**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/values/strings.xml
git commit -m "feat(security): finalize Phase 7 Privacy and Security strings and verification"
```

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-08-29-privacy-security-plan.md`.
Use `superpowers:subagent-driven-development` to execute the 7 tasks.
