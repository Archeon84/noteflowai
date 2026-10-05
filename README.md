# NoteFlow AI 🧠⚡

> **100% Private, On-Device AI Note-Taking with a 2-Stage Hybrid RAG System & Autonomous Personal Memory Layer**

[![Android CI](https://github.com/Archeon84/noteflowai/actions/workflows/android.yml/badge.svg)](https://github.com/Archeon84/noteflowai/actions/workflows/android.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.0-blue.svg?logo=kotlin)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-API%2026%E2%80%9335-green.svg?logo=android)](https://developer.android.com)
[![Google LiteRT-LM](https://img.shields.io/badge/Google-LiteRT--LM%200.17.1-orange.svg)](https://ai.google.dev/edge/litert)
[![ONNX Runtime](https://img.shields.io/badge/ONNX%20Runtime-1.23.2-yellow.svg)](https://onnxruntime.ai)
[![SQLCipher](https://img.shields.io/badge/Encrypted-SQLCipher%20AES--256-blueviolet.svg)](https://www.zetetic.net/sqlcipher/)
[![License](https://img.shields.io/badge/License-Apache%202.0-lightgrey.svg)](LICENSE)

---

## 📌 Project Status & Verification Anchor

| Dimension | Specification | Verification Source / Date |
| :--- | :--- | :--- |
| **CI Build Status** | ✅ **Passing** | GitHub Actions Workflow (`.github/workflows/android.yml`) |
| **Local Test Suite** | ✅ **100% Green (0 failures)** | Verified: October 2026 via `./gradlew testDebugUnitTest` |
| **App Maturity** | 🟢 **Production-Ready** | Feature-complete 2-stage RAG, on-device LiteRT inference & memory graph |
| **Supported OS** | **Android 8.0 to Android 15** (API 26–35) | Tested against Pixel 6a/7/8 (API 33–35) & Galaxy S21/S23 (API 31–34) |
| **Hardware Architecture**| **ARM64 (`arm64-v8a`)** & **x86_64** | NDK native libraries compiled for both ABIs |
| **Model Distribution** | **Zero Bundled Weights** (~45 MB APK) | Models downloaded on-demand in-app to internal app storage |

---

NoteFlow AI is an offline-first **second brain** for Android. It replaces cloud-dependent note apps with high-velocity, sub-second local intelligence. By combining Google LiteRT-LM on-device inference, an Alibaba GTE neural cross-encoder, IBM Granite multilingual embeddings, and native C++ `whisper.cpp` speech recognition, NoteFlow AI turns your personal notes into an autonomous, interconnected knowledge network without cloud subscription fees or data leaks.

---

## ⚡ Quick Navigation

| Document | Description |
| :--- | :--- |
| 📖 **[HANDOFF.md](HANDOFF.md)** | **Complete Technical Architecture, Subsystems, ADRs, Incident Runbook & Ownership** |
| 🚀 **[SETUP.md](SETUP.md)** | **5-Minute Developer Quick Start, NDK Toolchain, Building & Troubleshooting** |
| 🛡️ **[CONTRIBUTING.md](CONTRIBUTING.md)** | **Contribution Guide, PR Checklist, Zero-Telemetry Rule & Security Policy** |
| ✅ **[RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md)** | **Pre-Flight Release Gates, Smoke Test Protocols & QA Checklist** |

---

## 📱 Hardware & Device Tier Support Matrix

| Tier | Target Devices / SoCs | Recommended Model | Expected Performance | Fallback / Behavior |
| :--- | :--- | :--- | :--- | :--- |
| **Tier 1 (Flagship)** | 8 GB+ RAM, Snapdragon 8 Gen 1+, Tensor G2/G3/G4, Dimensity 9000+ | Gemma 4 E4B (~2.4 GB) or E2B (~1.2 GB) | ~20–25 tokens/sec, TTFT < 700 ms | Full OpenCL GPU acceleration |
| **Tier 2 (Mid-Range)** | 6 GB RAM, Snapdragon 778G+, Tensor G1, Exynos 2100+ | Gemma 4 E2B (~1.2 GB) | ~15–20 tokens/sec, TTFT < 900 ms | OpenCL GPU acceleration, `largeHeap` enabled |
| **Tier 3 (Budget / Low-RAM)**| 4 GB RAM, Helio G99, Snapdragon 680 | Gemma 4 E2B or Cloud Fallback | ~6–10 tokens/sec (CPU XNNPACK) | CPU fallback; user prompted to use Cloud APIs if device encounters memory pressure |
| **Emulator** | Android Studio Emulator (x86_64, API 30+) | Gemma 4 E2B (Testing only) | ~5–8 tokens/sec | OpenCL unavailable; automatically switches to CPU XNNPACK |

---

## 🌟 Key Superpowers

### 1. 🎙️ Omni-Capture Speed Dial
- **Local Speech-to-Text**: Native `whisper.cpp` JNI transcription compiles via NDK CMake — record and transcribe lectures, voice memos, and meetings with zero cloud exposure.
- **Multi-Source Ingestion**: Capture notes via camera OCR (ML Kit Text Recognition v2), import PDFs (PdfBox-Android), DOCX files (Apache POI), and YouTube transcripts.

### 2. 🧬 Autonomous Knowledge Graph & Personal Memory
- **Autonomous Memory Worker**: Background `WorkManager` pipeline parses updated notes to construct dynamic knowledge graph connections.
- **Commitments & Conflicts**: Surfaces actionable commitments with due dates and alerts you to conflicting statements made across different notes.
- **Daily & Weekly Digest**: Proactive memory reviews present relevant historical notes and resurface forgotten ideas.

### 3. 🎯 2-Stage Precision Hybrid RAG
- **Stage 1 (Hybrid Candidate Retrieval)**: Combines SQLite FTS5 full-text keyword matching with IBM Granite 311M Multilingual ONNX vector similarity via Reciprocal Rank Fusion (RRF).
- **Stage 2 (Neural Cross-Encoder Reranking)**: Scores top candidate excerpts through an INT8 quantized Alibaba GTE cross-encoder ONNX model.
- **Strict Citation Isolation**: Injects verified footnote citations (`[1]`, `[2]`) in note-grounded chat and refuses hallucinated claims when notes lack evidence.

### 4. ⚡ Google LiteRT-LM Local Inference
- **Gemma 4 E2B & E4B**: Run state-of-the-art Google Gemma models directly on device.
- **Hardware Acceleration**: Automatic OpenCL GPU acceleration with instant CPU (XNNPACK) fallback.
- **Streaming Response**: Real-time token streaming via a custom coroutine bridge.

---

## 📂 Repository Organization by Concern

For new contributors navigating the codebase, core functional areas are mapped below:

```
NoteFlowAI/
├── app/src/main/
│   ├── java/com/noteflowai/app/
│   │   ├── data/
│   │   │   ├── LiteRtInferenceManager.kt  # On-device Google LiteRT-LM inference engine
│   │   │   ├── LlmConfig.kt               # Local & remote AI model parameters and prompts
│   │   │   ├── search/                    # 2-Stage Hybrid RAG (FTS5 + Granite embeddings)
│   │   │   │   ├── HybridRetriever.kt     # Reciprocal Rank Fusion (RRF) search pipeline
│   │   │   │   └── reranker/              # Alibaba GTE INT8 Cross-Encoder neural reranker
│   │   │   ├── memory/                    # Autonomous Memory Layer, entities & timelines
│   │   │   ├── security/                  # SQLCipher encryption passphrase & key managers
│   │   │   └── network/                   # Local-only network interceptor & remote clients
│   │   ├── service/
│   │   │   └── MemoryRebuildWorker.kt     # Resumable WorkManager memory rebuild pipeline
│   │   ├── ui/
│   │   │   ├── screens/                   # Compose screens (IntroScreen, NoteDetail, MemoryHub)
│   │   │   └── theme/                     # Material 3 colors, typography & NavigationModeUtils
│   │   └── whisper/                       # Audio recording controllers & state machines
│   ├── cpp/                               # Native whisper.cpp C++ implementation & CMakeLists
│   └── res/                               # Layouts, vector drawables, localized strings.xml
├── docs/                                  # Release checklist & architecture specifications
├── .github/workflows/                     # Automated GitHub Actions Android CI pipeline
└── macrobenchmark/                        # Startup & scrolling performance benchmark tests
```

---

## ⏱️ Quick Start (< 10 Minutes)

### Prerequisites (Verified Against Repo Configuration)
- **Android Studio**: Hedgehog (2023.1.1) or newer
- **JDK**: 17
- **NDK**: `27.0.12077973`
- **CMake**: `3.22.1`

### Running the Project

```bash
# 1. Clone the repository
git clone https://github.com/Archeon84/noteflowai.git
cd noteflowai

# 2. Build Debug APK
./gradlew assembleDebug

# 3. Run all unit tests
./gradlew testDebugUnitTest

# 4. Install to connected device (API 30+)
./gradlew installDebug
```

For full setup instructions, common build errors, and keystore signing, read **[SETUP.md](SETUP.md)**.

---

## 👥 Release Governance & Branching Policy

- **Main Branch (`main`)**: Protected. Direct pushes are disabled for contributors; all changes require a Pull Request that passes the automated Android CI pipeline.
- **Release Versioning**: Releases follow Semantic Versioning (`vMAJOR.MINOR.PATCH`) cut from `main`.
- **Sign-Off Protocol**: Production releases require sign-off from both the **Lead Architect** and **Release Engineer** after verifying all gates in **[RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md)**.

---

## 🔒 Privacy & Zero-Telemetry Guarantee

NoteFlow AI is built with an absolute **zero-telemetry commitment**:
- **No Third-Party Analytics**: No Firebase Crashlytics, no Google Analytics, no telemetry beacons.
- **Encrypted at Rest**: All note content and memory structures are encrypted with **SQLCipher** (AES-256).
- **Offline By Default**: Complete functionality is preserved in Airplane Mode.
- **Responsible Vulnerability Disclosure**: Please report any security vulnerability privately through [GitHub Security Advisories](https://github.com/Archeon84/noteflowai/security/advisories/new).

For contribution guidelines and security protocols, refer to **[CONTRIBUTING.md](CONTRIBUTING.md)**.

---

## 📄 License

Distributed under the Apache License 2.0. See [LICENSE](LICENSE) for details.
