# Privacy Policy for NoteFlow AI

**Last Updated:** October 2026

NoteFlow AI ("we", "our", or "the App") is developed with a strict **Privacy-First, Zero-Telemetry, and Offline-First** philosophy. We believe your personal thoughts, audio recordings, documents, and private knowledge should belong exclusively to you.

---

## 1. Zero Telemetry & Local Processing

- **No Analytics or Trackers**: NoteFlow AI contains no tracking SDKs, no behavioral analytics (such as Google Analytics or Firebase Analytics), and no third-party advertising SDKs.
- **On-Device Artificial Intelligence**: By default, all AI summarization, transcription, semantic search, and knowledge graph extractions are computed entirely on-device using local runtimes (Google LiteRT-LM, ONNX Runtime, and whisper.cpp). Your voice notes and text never leave your device for AI processing unless you explicitly configure an optional third-party remote API provider.

---

## 2. Permissions & Data Access

The App requests the following Android runtime permissions strictly to provide core functionality:

1. **Microphone (`RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE`)**:
   - **Purpose**: Used solely to record audio notes and lectures when initiated by the user.
   - **Processing**: Audio is processed entirely on-device using native `whisper.cpp` speech-to-text models. Raw audio recordings are stored in the App's private encrypted storage and are never uploaded to any remote server.

2. **Camera (`CAMERA`)**:
   - **Purpose**: Used exclusively for real-time document OCR (optical character recognition) via Google ML Kit on-device text recognition.
   - **Processing**: Camera frames are processed locally in real-time memory buffers; photos are not transmitted over the internet.

3. **Notifications (`POST_NOTIFICATIONS`)**:
   - **Purpose**: Used solely to display ongoing audio recording controls, model download progress, and local memory review / daily digest summaries.

4. **Storage (`READ_MEDIA_AUDIO`)**:
   - **Purpose**: Used only when the user explicitly chooses to import existing local audio files or document files (PDF, DOCX) into NoteFlow AI.

---

## 3. Data Storage & Encryption

- **Database Encryption**: All note contents, knowledge graph entities, embeddings, and commitments are encrypted at rest using **SQLCipher AES-256** encryption.
- **Android Keystore**: Cryptographic database keys are secured within the hardware-backed Android Keystore system.
- **Backup Isolation**: Sensitive database keys and temporary audio buffers are explicitly excluded from standard Android OS cloud backups and ADB backup archives.

---

## 4. Optional Cloud Integrations

The App provides optional cloud features that are disabled by default and only activated upon explicit user consent:

- **Google Drive Backup Sync**: If you choose to connect Google Drive, the App uses Google Sign-In with the limited `https://www.googleapis.com/auth/drive.file` scope. The App only accesses files and folders created specifically by NoteFlow AI (`NoteFlowAI_Backups`). You can disconnect your Google account and revoke access at any time within the App Settings or via [Google Account Security Settings](https://myaccount.google.com/permissions).
- **Custom Cloud AI Providers**: If you choose to configure optional cloud LLMs (OpenAI, Gemini, Anthropic, or custom endpoints), note prompts are transmitted directly from your device to your chosen provider using your personal API key over HTTPS. We operate no intermediary proxy servers.

---

## 5. Data Deletion & User Rights

- **Complete Data Ownership**: Because NoteFlow AI stores all data locally on your device, you have complete control over your data.
- **In-App Purge**: You can purge all derived vector embeddings, entity graphs, and cache files at any time via **Settings > Privacy & Data Vault > Purge Derived Data**.
- **Full Deletion**: Uninstalling the App or clearing App Storage via Android System Settings permanently and irrevocably deletes all local notes and encrypted databases.

---

## 6. Children's Privacy

NoteFlow AI is not directed at children under the age of 13. We do not knowingly collect or solicit any personal information from children.

---

## 7. Contact & Security Inquiries

For questions or security disclosures regarding this Privacy Policy, please open an issue or private security advisory on our official repository:
- **Repository**: [https://github.com/Archeon84/noteflowai](https://github.com/Archeon84/noteflowai)
- **Security Disclosures**: [https://github.com/Archeon84/noteflowai/security/advisories](https://github.com/Archeon84/noteflowai/security/advisories)
