# Design Specification: Phase 7 Privacy and Security

**Date:** 2026-08-29
**Status:** Approved
**Target Phase:** Phase 7 of NoteFlowAI Agentic Guide (lines 1119-1227)

---

## 1. Overview and Objectives

NoteFlowAI is a privacy-first personal intelligence and second-brain app. Phase 7 establishes comprehensive data protection across storage, processing, network communication, export, and deletion.

The 5 foundational security pillars in this design are:
1. **Hardware-Backed Database Encryption (SQLCipher & Android Keystore):** Secures all structured Room database records with AES-256 ciphering using a 32-byte cryptographic random passphrase stored in `EncryptedSharedPreferences` protected by Android Keystore MasterKey.
2. **Hard Local-Only Network Isolation & Firewall Gate:** An interceptor-level network firewall enforcing zero external HTTP/WebSocket egress when Local-Only mode is enabled.
3. **AES-256-GCM Password-Protected Backup Container (`.enc.zip`):** Approach 1 standard container using PBKDF2WithHmacSHA256 (100,000 iterations, 16-byte salt, 12-byte IV, 256-bit key) with authenticated GCM tag verification, guaranteeing zero leakage of credentials or keys.
4. **OS-Level Backup & Data Extraction Hardening:** Strict `backup_rules.xml` and `data_extraction_rules.xml` configurations preventing cloud, ADB, and device-to-device leaks.
5. **Granular Data Deletion Controls, Cache Purging, & Safe Logging:** Distinct "Delete Derived Data" (rebuildable indices/embeddings/entities/timeline) vs "Delete Source Note and Cascade" operations, one-tap cache purges, and automated PII/key redaction in logging.

---

## 2. Pillar 1: Hardware-Backed Database Encryption

### 2.1 Key Architecture

```
┌─────────────────────────────────────────────────────────────┐
│ Android Keystore Hardware Security Module (TEE / StrongBox) │
└──────────────────────────────┬──────────────────────────────┘
                               │ protects (AES256-GCM)
                               ▼
┌─────────────────────────────────────────────────────────────┐
│ EncryptedSharedPreferences ("noteflow_secure_prefs")         │
│   Key: ENC_MEMORY_DB_PASSPHRASE                             │
│   Val: Base64-encoded 32-byte Cryptographic Passphrase      │
└──────────────────────────────┬──────────────────────────────┘
                               │ supplies
                               ▼
┌─────────────────────────────────────────────────────────────┐
│ net.zetetic.database.sqlcipher.SupportOpenHelperFactory     │
│   Passphrase (ByteArray)                                    │
└──────────────────────────────┬──────────────────────────────┘
                               │ encrypts (AES-256-CBC)
                               ▼
┌─────────────────────────────────────────────────────────────┐
│ SQLite Room Database ("memory_database.db")                 │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 Security Invariants
- Plaintext database passphrases and encryption keys MUST NEVER be stored in standard SharedPreferences, DataStore protobufs, source code constants, assets, log outputs, or unencrypted backup files.
- The passphrase is generated using `SecureRandom().nextBytes(ByteArray(32))` and persisted in `EncryptedSharedPreferences` encrypted with `MasterKey.KeyScheme.AES256_GCM`.
- If the Keystore is invalidated or key retrieval fails, database access fails safely without leaking raw key material.

---

## 3. Pillar 2: Hard Local-Only Network Isolation & Firewall Gate

### 3.1 Architecture

```
                        Outbound HTTP / WebSocket Request
                                       │
                                       ▼
                       ┌───────────────────────────────┐
                       │  LocalOnlyNetworkInterceptor  │
                       └───────────────┬───────────────┘
                                       │
                         Is Local-Only Mode Enabled?
                                       │
                     ┌─────────────────┴─────────────────┐
                    YES                                  NO
                     │                                   │
         Is target host local?              Proceed with network call
     (localhost, 127.0.0.1, 10.0.2.2)                    │
                     │                                   ▼
          ┌──────────┴──────────┐                 Standard Request
         YES                    NO
          │                      │
       Proceed        THROW LocalOnlyBlockedException
  (Local Ollama only)  (HTTP 403 / Egress Blocked)
```

### 3.2 Firewall Implementation
- `LocalOnlyNetworkInterceptor`: Registered in `NetworkModule.newClientBuilder()` before any network calls execute.
- When `isLocalOnlyMode` is active in `SettingsManager`:
  - Outbound requests to OpenAI (`api.openai.com`), Anthropic (`api.anthropic.com`), Google Gemini (`generativelanguage.googleapis.com`), Deepgram (`api.deepgram.com`), DeepSeek (`api.deepseek.com`), Perplexity, or Web Search endpoints are intercepted and aborted immediately with `LocalOnlyBlockedException`.
  - Only local loopback endpoints (`127.0.0.1`, `localhost`, `10.0.2.2` for local Ollama / LM Studio) are permitted if the user has explicitly enabled local server access.
- UI Indicator:
  - `PrivacyRoutingIndicator` Composable displayed across Chat, Note Detail, and Settings screens showing real-time status: `LOCAL_ONLY` (Green lock badge) vs `CLOUD_CONNECTED` (Cloud icon with active provider name).

---

## 4. Pillar 3: AES-256-GCM Encrypted Backup Container (`.enc.zip`)

### 4.1 Container Format Specification (Approach 1)

Encrypted backups use the `.enc.zip` file extension. The binary format layout is:

```
+-------------------+-------------------+-------------------+-----------------------------------+
| Magic Header      | Salt              | IV (Nonce)        | Ciphertext + GCM Tag              |
| 7 bytes (ASCII)   | 16 bytes (Random) | 12 bytes (Random) | N bytes (Encrypted Standard Zip)  |
| "NFENC01"         |                   |                   | [16-byte Auth Tag at end]         |
+-------------------+-------------------+-------------------+-----------------------------------+
```

### 4.2 Cryptographic Parameters
- **Cipher:** `AES/GCM/NoPadding`
- **Key Derivation Function:** `PBKDF2WithHmacSHA256`
- **Iterations:** `100,000`
- **Salt Length:** `16 bytes` (generated per backup using `SecureRandom`)
- **IV Length:** `12 bytes` (generated per backup using `SecureRandom`)
- **Key Length:** `256 bits` (`32 bytes`)
- **Authentication Tag Length:** `128 bits` (`16 bytes`)

### 4.3 Backup & Restore Lifecycle
1. **Export Workflow:**
   - User initiates backup and provides an optional passphrase.
   - If passphrase is provided -> creates `NoteFlowAI_Backup_YYYYMMDD_HHmmss.enc.zip`.
   - If passphrase is empty -> creates unencrypted `NoteFlowAI_Backup_YYYYMMDD_HHmmss.zip`.
   - Before packaging: `collectSettings()` explicitly strips all API keys (`enc_ai_api_key`, `enc_deepgram_api_key`, `enc_ai_search_api_key`), database passphrases, and AI presets with embedded secrets.
   - Standard zip contents: `settings.json`, `notes/`, `recordings/`, `chats/`.
   - If encrypted: Stream zip through `CipherOutputStream` with PBKDF2-derived AES-GCM key and prepended magic/salt/iv header.

2. **Import Workflow:**
   - App inspects magic bytes:
     - If `NFENC01` -> prompts for passphrase.
     - If PK zip magic (`PK\x03\x04`) -> processes as standard zip.
   - For `NFENC01`: Reads 16-byte salt, 12-byte IV, derives AES-GCM key via PBKDF2 (100,000 iterations), and decrypts payload via `CipherInputStream`.
   - If passphrase is wrong or ciphertext is tampered with: GCM tag validation fails (`AEADBadTagException`), aborting the restore immediately with `BackupResult(false, "Invalid password or corrupted backup archive")`.

---

## 5. Pillar 4: OS-Level Backup & Data Extraction Protections

### 5.1 Rules Configuration
- `app/src/main/res/xml/backup_rules.xml`:
  ```xml
  <?xml version="1.0" encoding="utf-8"?>
  <full-backup-content>
      <exclude domain="sharedpref" path="." />
      <exclude domain="database" path="." />
      <exclude domain="file" path="." />
      <exclude domain="root" path="." />
  </full-backup-content>
  ```
- `app/src/main/res/xml/data_extraction_rules.xml`:
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
- `AndroidManifest.xml`:
  - `android:allowBackup="false"`
  - `android:fullBackupContent="@xml/backup_rules"`
  - `android:dataExtractionRules="@xml/data_extraction_rules"`

---

## 6. Pillar 5: Granular Deletion Controls, Cache Purging, & Safe Logging

### 6.1 Granular Deletion Operations

1. **Delete Derived Data (`purgeDerivedMemoryData()`):**
   - Clears derived and indexed data across Room DB tables:
     - `source_segments`
     - `entity_mentions`
     - `entities` (where confirmation != CONFIRMED or all derived entities)
     - `timeline_entries`
     - `memory_objects`
     - `decisions`
     - `commitments`
     - `memory_relations`
     - `memory_review_items`
     - `answer_citations`
     - `conflicts`
     - `processing_status`
   - **Crucial Invariant:** Raw user markdown note files in `context.filesDir/notes/` and audio recordings in `context.filesDir/recordings/` ARE NEVER MODIFIED OR DELETED.
   - User can re-run memory extraction / indexing at any time to rebuild.

2. **Delete Source Note and Cascade (`deleteSourceNoteWithCascade(fileName)`):**
   - Deletes the markdown file `notes/$fileName`.
   - Cascades deletion to Room tables by `sourceId == fileName` (or `sourceNoteId == fileName`).
   - Removes associated `raw_captures` for that file.

3. **Cache Purge Actions:**
   - `clearTtsCache(context)`: Deletes `context.cacheDir/tts_cache` and `context.filesDir/tts`.
   - `clearLlmCache()`: Resets `LlamaInferenceManager` KV cache and temporary prompt buffers.
   - `clearTempFiles(context)`: Purges temporary audio slices, OCR scratchpads, and imported zip extractions from `context.cacheDir`.

### 6.2 SafeLogger

All app logging routes through `com.noteflowai.app.util.SafeLogger`:
- Automatically redacts sensitive patterns before delegating to `android.util.Log`:
  - API Keys: `sk-[A-Za-z0-9]{20,}`, `Bearer\s+[A-Za-z0-9-_.]+`, `deepgram\s+[a-f0-9]{40}` -> `[REDACTED_API_KEY]`
  - Passwords & Tokens: `"password"\s*:\s*"[^"]+"`, `"passphrase"\s*:\s*"[^"]+"` -> `[REDACTED_SECRET]`
  - Auth Headers: `Authorization: .*` -> `Authorization: [REDACTED]`

### 6.3 Privacy Dashboard UI

Located in `com.noteflowai.app.ui.screens.privacy.PrivacyDashboardScreen`:
- **Security Status Card:**
  - Database Encryption: Active (Hardware-backed SQLCipher AES-256)
  - Cloud Isolation: Local-Only Mode toggle
  - OS Backup Protection: Active (Excluded from cloud & ADB backups)
- **Storage & Cache Card:**
  - TTS Audio Cache: Size in MB + "Clear TTS Cache" button
  - Temporary Files: Size in MB + "Clear Temp Files" button
  - LLM Memory Cache: "Reset KV Cache" button
- **Data Lifecycle Card:**
  - "Purge Derived Memory Data" (with warning dialog explaining raw notes are safe)
  - "Create Encrypted Backup" (password prompt + `.enc.zip` export)
  - "Restore Backup" (auto-detects `.enc.zip` vs `.zip`)

---

## 7. Verification & Testing Matrix

| Test Component | Target Behaviors |
|----------------|------------------|
| `EncryptedBackupTest` | PBKDF2 key derivation (100k iters), AES-256-GCM container round-trip, incorrect password failure (`AEADBadTagException`), header tampering rejection, zero credential leakage in `settings.json`. |
| `LocalOnlyNetworkInterceptorTest` | Local-Only mode enabled blocks external HTTP/WebSocket egress; allows localhost/127.0.0.1; disabled mode passes through. |
| `SafeLoggerTest` | Redacts OpenAI keys, Bearer tokens, Deepgram keys, JSON password fields, and authorization headers from log messages. |
| `GranularDeletionTest` | `deleteDerivedData` clears segments/entities/timeline/citations while preserving markdown files; `deleteSourceNoteWithCascade` removes note and cascades all database references. |
| `BackupRulesXmlTest` | Verifies `backup_rules.xml` and `data_extraction_rules.xml` contain required exclusions for database, sharedpref, file, and root. |

---

## 8. Spec Self-Review Checklist

- [x] **Placeholder scan:** No TBD, TODO, or vague statements. Complete crypto specs, iteration counts, header bytes, and class names provided.
- [x] **Internal consistency:** Matches NoteFlowAI architecture (Room DB v8, EncryptedSharedPreferences, SettingsManager DataStore, BackupManager).
- [x] **Scope check:** Strictly covers Phase 7 Privacy & Security requirements.
- [x] **Ambiguity check:** Approach 1 (PBKDF2 100k + AES-256-GCM `.enc.zip`) specified explicitly with byte layout.
