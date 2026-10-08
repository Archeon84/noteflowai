# NoteFlow AI — Incident Recovery Playbook & Bad States Runbook

This runbook outlines immediate remediation steps for unexpected runtime failures, model corruption, index desynchronization, and memory pressure in development and production testing.

---

## 🚨 Quick Incident Triaging Guide

| Symptom | Primary Failure Area | Recovery Playbook |
| :--- | :--- | :--- |
| Model download hangs at 99% or fails on initialization | LiteRT Model Storage | [Playbook 1: Corrupted Model Downloads](#playbook-1-partial-or-corrupted-model-downloads) |
| RAG search returns 0 results or `JsonSyntaxException` | Vector & FTS5 Index | [Playbook 2: Vector Index Desync](#playbook-2-vector-or-segment-index-desynchronization) |
| App silently disappears during token generation (`lmkd`) | Low-Memory Killer (LMK) | [Playbook 3: Low-Memory Killer Aborts](#playbook-3-low-memory-killer-lmk-aborts-on-devices-with--6gb-ram) |
| Voice recording crash (`SIGSEGV` / `whisper_full failed`) | Native Audio JNI | [Playbook 4: Native JNI Audio / Whisper Crash](#playbook-4-native-jni-audio--whisper-crash) |
| Database access error / SQLCipher decryption failure | Encryption & Keystore | [Playbook 5: Database Key Decryption Failure](#playbook-5-database-key-decryption-failure) |

---

## 🛠️ Detailed Playbooks

### Playbook 1: Partial or Corrupted Model Downloads
- **Symptom**: Model download hangs near completion, or LiteRT throws `Model initialization failed: invalid header / truncated file`.
- **Root Cause**: Network interrupted during download, or the user backgrounded the app during non-atomic file move operations.
- **Recovery Procedure**:
  1. Via ADB terminal:
     ```bash
     adb shell rm -rf /sdcard/Android/data/com.noteflowai.app/cache/models/temp_*
     adb shell rm -f /sdcard/Android/data/com.noteflowai.app/files/models/*.litertlm
     ```
  2. In-App: Go to **Settings > AI Engine**, tap **Delete Model**, then restart download over stable Wi-Fi.

---

### Playbook 2: Vector or Segment Index Desynchronization
- **Symptom**: RAG search yields 0 results even when relevant notes exist, or vector index throws `JsonSyntaxException`.
- **Root Cause**: Force-close or abrupt process termination during an older uncommitted index write.
- **Recovery Procedure**:
  1. **In-App Automated Rebuild**: Open **Settings > Developer / Diagnostics**, and tap **Rebuild Knowledge Graph & Vector Index**.
  2. **Manual Cleanup via ADB**:
     ```bash
     adb shell rm -f /data/data/com.noteflowai.app/files/embeddings/vector_index.json
     ```
  3. **Auto-Reconciliation**: The background `MemoryRebuildWorker` automatically detects missing indices and re-indexes all active notes from the encrypted Room database on next app launch.

---

### Playbook 3: Low-Memory Killer (LMK) Aborts on Devices with < 6GB RAM
- **Symptom**: App suddenly vanishes during inference without an unhandled Java stacktrace; `adb logcat` reports:
  ```text
  lmkd: kill com.noteflowai.app (pid ...) to free ... kB
  ```
- **Root Cause**: Attempting to load the 2.4 GB Gemma 4 E4B model on a device with limited physical RAM (Tier 3 hardware).
- **Recovery Procedure**:
  1. Open app **Settings > AI Engine**.
  2. Switch default model to **Gemma 4 E2B (~1.2 GB)** or configure an optional remote cloud LLM provider.
  3. Verify `android:largeHeap="true"` is declared in `app/src/main/AndroidManifest.xml`.

---

### Playbook 4: Native JNI Audio / Whisper Crash
- **Symptom**: Tapping the voice recording stop button triggers `SIGSEGV` or `whisper_full failed`.
- **Root Cause**: Audio buffer captured at non-16kHz sample rate or zero-byte WAV header.
- **Recovery Procedure**:
  1. Inspect `AudioRecordController.kt`: Audio capture format must strictly be 16-bit PCM, 16,000 Hz, single channel (mono).
  2. Confirm whisper model weight integrity in `context.filesDir/whisper/`.

---

### Playbook 5: Database Key Decryption Failure
- **Symptom**: App crashes on start with `net.sqlcipher.database.SQLiteException: file is not a database`.
- **Root Cause**: Android Keystore alias desynchronization or corrupted key material following an unmigrated device backup restore.
- **Recovery Procedure**:
  1. Check logcat for `MemoryDatabase: Failed to retrieve passphrase from Android Keystore`.
  2. If running automated tests, clear test app storage:
     ```bash
     adb shell pm clear com.noteflowai.app
     ```
  3. For development databases, ensure Room schema migrations (`schemas/com.noteflowai.app.data.local.NoteDatabase`) are matched to the active version in `build.gradle.kts`.

---

## 👥 Escalation Contacts
If a production issue cannot be resolved using the steps above:
- **Primary Maintainer**: `@Archeon84` ([GitHub Issues](https://github.com/Archeon84/noteflowai/issues))
- **Security Vulnerabilities**: File privately via [GitHub Security Advisories](https://github.com/Archeon84/noteflowai/security/advisories/new)
