# Privacy Policy for NoteFlow AI

**Last Updated:** October 2026

NoteFlow AI ("we", "our", or "the App") is built with a strict **Privacy-First, Zero-Telemetry, and Offline-First** architecture. Your personal thoughts, voice recordings, scanned documents, and knowledge graph belong exclusively to you. We do not operate proprietary backend servers that collect, store, or monetize your personal data.

---

## 1. Zero Telemetry & On-Device Processing

- **No Analytics, Crash Trackers, or Ad SDKs**: NoteFlow AI contains no behavioral analytics SDKs (such as Google Analytics or Firebase Analytics), no third-party crash-reporting telemetry, and no advertising SDKs.
- **On-Device Artificial Intelligence by Default**: By default, speech-to-text transcription (`whisper.cpp` / ONNX / QNN), document OCR (Google ML Kit), translation (NLLB-200 / ML Kit), semantic search embeddings (MiniLM ONNX / GTE Reranker), and local LLM inference (Google LiteRT-LM) execute **100% locally on your device**.
- **Local-Only Mode (Network Firewall)**: You can enable **Local-Only Mode** at any time in **Settings > Privacy & Data Vault**, which enforces an application-wide network interceptor (`LocalOnlyNetworkInterceptor`) that blocks all outbound HTTP/WebSocket traffic.

---

## 2. Permissions & Device Access

The App requests only the Android permissions required for user-initiated features:

1. **Microphone (`RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`)**:
   - **Purpose**: Used solely to capture voice notes, meetings, or user-configured scheduled recordings. When you start a recording and switch apps or lock your screen, a Foreground Service displays a persistent notification (`"NoteFlow AI — Recording"`) with a **Stop** action so you remain in full control.
   - **Processing**: In Offline Mode (default), audio is transcribed entirely on-device via `whisper.cpp` or ONNX Runtime. If you explicitly enable **Online Mode (Deepgram)** and provide your own Deepgram API key, audio is streamed or uploaded over TLS (`wss://` / `https://api.deepgram.com`) to Deepgram for transcription.

2. **Camera (`CAMERA`)**:
   - **Purpose**: Used exclusively when you open the **OCR Scanner** screen to capture text from physical documents, receipts, or whiteboards.
   - **Processing**: Camera frames are processed locally on-device using Google ML Kit Text Recognition. Captured images are never uploaded to any server unless you explicitly attach an image to a Cloud AI chat session.

3. **Notifications (`POST_NOTIFICATIONS`)**:
   - **Purpose**: Used to display active recording status/controls, scheduled recording alerts, and optional local daily digest or recall reminder notifications.

4. **Alarms & Boot Recovery (`SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`)**:
   - **Purpose**: Used only if you enable **Scheduled Recordings**, **Daily Digest**, or **Recall Reminders**, allowing the App to trigger your configured local alarms accurately and restore them after a device reboot.

5. **Document & Audio File Import (Android System File Picker)**:
   - **Purpose**: NoteFlow AI does **not** request broad storage permissions (`READ_EXTERNAL_STORAGE` or `READ_MEDIA_AUDIO`). Instead, it uses the Android Storage Access Framework (`OpenDocument` / `GetContent`), granting the App read access only to the specific PDF, DOCX, TXT, HTML, or audio file you explicitly select.

---

## 3. Data Storage & Encryption Architecture

- **Encrypted Memory Database (`memory_layer.db`)**: All derived memory artifacts—including source segments, vector embeddings, extracted entities, decisions, commitments, and timeline entries—are stored in a **SQLCipher AES-256** encrypted SQLite database. The 256-bit database passphrase is generated via `SecureRandom` and stored in **AndroidX `EncryptedSharedPreferences`** backed by the hardware **Android Keystore (`AES256_GCM`)**.
- **Encrypted API Keys (`noteflow_secure_prefs`)**: Any third-party API keys you configure (OpenAI, Gemini, Anthropic, DeepSeek, Deepgram, or Web Search) are encrypted at rest using **AndroidX Security Crypto (`AES256_SIV` key encryption and `AES256_GCM` value encryption)**.
- **OS-Sandboxed Raw Files & Password-Protected Backups**: Raw note files (`notes/`), WAV audio recordings (`recordings/`), and chat histories (`chats/`) are stored inside the App's private internal sandbox (`Context.filesDir`), which is isolated from other applications by Android OS kernel permissions. When exporting a backup via **Settings > Privacy & Data Vault > Export Encrypted Backup (`.enc.zip`)**, archives are encrypted with **AES-256-GCM** using a key derived from your password via **PBKDF2-HMAC-SHA256 (600,000 iterations)**.
- **Android Cloud & ADB Backup Exclusion**: Sensitive databases (`memory_layer.db`), encrypted preferences (`noteflow_secure_prefs`), and datastore configurations are excluded from automatic Android OS cloud backups and `adb backup` extraction via `backup_rules.xml` and `data_extraction_rules.xml`.

---

## 4. Optional Network & Cloud Integrations

All network features below are **optional**, **user-initiated**, and **disabled when Local-Only Mode is active**:

- **Google Drive Backup Sync**:
  - If you sign in with Google to back up your notes, the App requests only the restricted `https://www.googleapis.com/auth/drive.appdata` (`DriveScopes.DRIVE_APPDATA`) OAuth scope.
  - This scope grants access **exclusively to NoteFlow AI's hidden `appDataFolder`** on your personal Google Drive. NoteFlow AI cannot view, list, or modify any other files in your Google Drive.
  - Your Google account email and display name are read locally from `GoogleSignInAccount` solely to display your connection status in Settings and are never sent to us.
  - You can sign out and revoke access at any time in **Settings > Google Drive Sync** or at [Google Account Permissions](https://myaccount.google.com/permissions).

- **Custom Cloud AI Providers (BYOK — Bring Your Own Key)**:
  - If you configure a remote AI provider (OpenAI, Google Gemini, Anthropic, DeepSeek, Groq, OpenRouter, Ollama, or a custom OpenAI-compatible URL), your chat prompts and relevant note context (snippets or full notes, governed by your **Grounded Memory** and **Full-Note Context** toggles) are transmitted directly from your device over HTTPS to your configured endpoint using your personal API key.

- **Cloud Speech-to-Text & Voice Readout (Deepgram BYOK)**:
  - If you enable **Online Mode (Deepgram)** for transcription or configure a Deepgram API key for **AI Voice Readout (Aura TTS)**, audio data or note text to be spoken aloud is sent directly to `https://api.deepgram.com` over TLS using your personal API key. When no Deepgram key is set, TTS uses the on-device Android system `TextToSpeech` engine.

- **Optional Web Search Grounding**:
  - If you enable **Web Search** in AI settings and toggle it on during a chat, your search query is sent directly to your configured search endpoint (or DuckDuckGo HTML search if no custom endpoint is configured) to retrieve web snippets.

- **YouTube Transcript Ingestion**:
  - When you paste a YouTube link into the **YouTube Transcription** screen, the App connects directly to `youtube.com` to fetch caption tracks. If direct captions are unavailable, the App may query `youtube-transcript.ai` or public Invidious instances (`yewtu.be`, `inv.nadeko.net`, `invidious.nerdvpn.de`) with the video ID to retrieve public captions.

- **On-Device AI Model Downloads**:
  - When you tap **Download** on an offline model in Settings, model weights are downloaded directly over HTTPS from Hugging Face (`huggingface.co`), Qualcomm AI Hub (`qaihub-public-assets.s3.us-west-2.amazonaws.com`), or Google ML Kit servers.

---

## 5. Data Retention, Deletion & User Rights

- **100% Local Data Ownership**: Because NoteFlow AI does not maintain user accounts or developer-hosted cloud databases, all data resides on your device (and in your personal Google Drive `appDataFolder` if you enabled Drive Sync).
- **Granular In-App Deletion**:
  - Delete individual or multiple notes, audio recordings, and chat sessions directly in the App.
  - Clear **TTS Audio Cache** and **Temporary Files** via **Settings > Privacy & Data Vault**.
  - Purge all derived vector embeddings, extracted entities, decisions, commitments, and timeline entries while keeping raw notes intact via **Settings > Privacy & Data Vault > Purge Derived Data**.
- **Complete Erasure**: Clearing App Storage in Android System Settings or uninstalling NoteFlow AI permanently deletes all local notes, recordings, API keys, and encrypted databases from your device.

---

## 6. Children's Privacy

NoteFlow AI is a general-audience productivity tool and is not directed to children under the age of 13. We do not knowingly collect any personal information from children.

---

## 7. Contact & Security Disclosures

For privacy questions, data inquiries, or security vulnerability disclosures, please contact us via our official repository:
- **Repository**: [https://github.com/Archeon84/noteflowai](https://github.com/Archeon84/noteflowai)
- **Security Advisories**: [https://github.com/Archeon84/noteflowai/security/advisories](https://github.com/Archeon84/noteflowai/security/advisories)
