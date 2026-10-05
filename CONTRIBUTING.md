# Contributing to NoteFlow AI & Security Policy

Thank you for contributing to **NoteFlow AI**! This project is built on the core principle of **100% private, on-device intelligence without subscription locks or external telemetry**. 

To maintain high code quality, security boundaries, and runtime reliability, all contributors must adhere to the following guidelines.

---

## 🔒 Security Boundaries & Privacy Policy

NoteFlow AI is **Zero-Telemetry by Design**:
- **No Third-Party Analytics**: Do not introduce Firebase Crashlytics, Google Analytics, Mixpanel, Sentry, or any background telemetry SDKs.
- **Strict Data Isolation**: User notes, vector embeddings, transcripts, and personal knowledge graphs must never leave the device unless the user explicitly initiates a manual Google Drive backup or configures an opt-in remote LLM API key.
- **Never Commit Secrets**:
  - Never commit API keys (OpenAI, Gemini, Claude, Deepgram, DeepSeek, etc.).
  - Never commit keystores (`*.jks`, `*.keystore`) or passwords.
  - Never commit real user note databases or personal data.
  - Store all developer credentials in `local.properties` (git-ignored) or pass them via environment variables.
- **Encrypted Storage**:
  - The SQLite database is encrypted at rest using **SQLCipher**.
  - Any persisted credentials or API tokens in `SharedPreferences` must use AndroidX `EncryptedSharedPreferences`.

---

## 🤖 Model Weights & Asset Management

NoteFlow AI relies on modern local AI models (LiteRT-LM Gemma, ONNX Embeddings, GTE Cross-Encoder, Whisper models):
- **Never Commit Model Binaries**:
  - Model weights range from 75 MB to 2.4 GB and **must never be added to the git repository**.
  - Always download model files dynamically at runtime into `context.getExternalFilesDir()` or `context.cacheDir`.
- **Unit Testing with Mocks**:
  - Unit tests in `app/src/test/java/` should never depend on downloaded multi-gigabyte models.
  - Mock embeddings, tokenizer lookups, and inference engines using MockK, Robolectric, or lightweight test fixtures (e.g., `mini_tokens.txt`).

---

## 🛠️ Contribution & PR Workflow

Before submitting a Pull Request, verify your changes against this checklist:

### 1. Verification Checklist
1. **Pass All Unit Tests**:
   ```bash
   ./gradlew testDebugUnitTest
   ```
   All tests in `app/src/test/` must pass without regressions.
2. **Compile Cleanly**:
   ```bash
   ./gradlew assembleDebug
   ```
3. **No Large Blobs Added**:
   Verify that git staging does not include large cached files:
   ```bash
   git status
   ```
   Ensure no file over 10 MB is being added. Keep `.gitignore` updated.

### 2. Code Style & Architecture Conventions
- **Language**: Kotlin 2.0 with Jetpack Compose (Material 3).
- **Architecture**: MVI / MVVM layered pattern:
  - **UI Layer** (`ui/screens/`, `ui/theme/`): Composable functions, stateless components, reactive state collection with `collectAsStateWithLifecycle()`.
  - **ViewModel Layer** (`viewmodel/`): Single source of truth via Kotlin `StateFlow` and coroutines.
  - **Data Layer** (`data/`, `data/memory/`, `data/search/`): Room DAOs, ONNX runtimes, LiteRT inference managers, atomic file handlers.
- **Surgical Edits**: Touch only what is necessary to solve the issue. Do not add speculative abstractions or unnecessary dependencies.

### 3. Submitting a Pull Request
- Create a feature branch from `main`: `git checkout -b feature/your-feature-name`.
- Use descriptive commit messages following the Conventional Commits specification (e.g., `feat(rag): ...`, `fix(memory): ...`, `docs: ...`).
- Open a PR against `main` on [Archeon84/noteflowai](https://github.com/Archeon84/noteflowai).

---

*Thank you for helping keep personal computing private, fast, and intelligent!*
