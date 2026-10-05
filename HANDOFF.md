# NoteFlow AI — Project Handoff & Technical Architecture Guide

Welcome to **NoteFlow AI** — a 100% private, on-device note-taking system equipped with a 2-stage Hybrid Retrieval-Augmented Generation (RAG) engine, an Autonomous Memory Layer & Knowledge Graph, and sub-second native AI inference.

---

## 📋 Table of Contents
1. [Executive Overview](#-executive-overview)
2. [Key Architecture & Core Subsystems](#-key-architecture--core-subsystems)
3. [Recent Major Updates](#-recent-major-updates)
4. [Repository Directory Structure](#-repository-directory-structure)
5. [Prerequisites & Build Guide](#-prerequisites--build-guide)
6. [On-Device & Cloud AI Models Catalog](#-on-device--cloud-ai-models-catalog)
7. [Testing & Quality Assurance](#-testing--quality-assurance)
8. [Roadmap & Next Steps for Developers](#-roadmap--next-steps-for-developers)

---

## 🚀 Executive Overview

NoteFlow AI is built to give users a **private second brain** that operates completely offline on Android hardware. Unlike cloud-dependent note applications, NoteFlow AI processes text, audio, and documents locally without telemetry or subscription fees.

### Key Capabilities:
- **Omni-Capture Speed Dial**: Record voice notes processed locally via `whisper.cpp`, capture documents via camera OCR, or import PDF and YouTube transcripts.
- **Autonomous Knowledge Graph & Personal Memory**: Automatically extracts entities, commitments, temporal events, and decision conflicts from user notes.
- **2-Stage Hybrid RAG**: Merges FTS5 full-text keyword retrieval with ONNX semantic vector embeddings, refined by a neural cross-encoder reranker for zero-hallucination note grounded chat with exact citations (`[1]`, `[2]`).
- **Google LiteRT-LM Inference**: Direct execution of `Gemma 4 E2B/E4B` models on OpenCL GPU / XNNPACK CPU with zero cloud latency.

---

## 🏗️ Key Architecture & Core Subsystems

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           Jetpack Compose UI                            │
│  (NoteFlowApp, IntroScreen, NoteDetail, MemoryHub, ChatScreen, Settings) │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │
┌────────────────────────────────────▼────────────────────────────────────┐
│                             MainViewModel                               │
│           (State Management, Async Flow Co-routines, Navigation)        │
└─────────┬──────────────────────────┬──────────────────────────┬─────────┘
          │                          │                          │
┌─────────▼───────────┐    ┌─────────▼───────────┐    ┌─────────▼─────────┐
│     RAG Engine      │    │    Memory Layer     │    │   Speech/Capture  │
│ ┌─────────────────┐ │    │ ┌─────────────────┐ │    │ ┌───────────────┐ │
│ │ HybridRetriever │ │    │ │ MemoryRebuild   │ │    │ │ whisper.cpp   │ │
│ └────────┬────────┘ │    │ │ Worker          │ │    │ │ (JNI / NDK)   │ │
│ ┌────────▼────────┐ │    │ └────────┬────────┘ │    │ └───────────────┘ │
│ │ ONNX Reranker   │ │    │ ┌────────▼────────┐ │    │ ┌───────────────┐ │
│ │ (Alibaba GTE)   │ │    │ │ Entity & Graph  │ │    │ │ Camera OCR /  │ │
│ └────────┬────────┘ │    │ │ Extraction      │ │    │ │ Doc Importers │ │
│ ┌────────▼────────┐ │    │ └─────────────────┘ │    │ └───────────────┘ │
│ │ LiteRT-LM Gemma │ │    └─────────────────────┘    └───────────────────┘
│ └─────────────────┘ │
└─────────────────────┘
```

### 1. On-Device LLM & RAG Engine
- **LiteRT-LM Integration** (`LiteRtInferenceManager.kt`): Replaces legacy wrappers with Google's native LiteRT runtime, utilizing GPU hardware acceleration (`OpenCL`) and falling back to `XNNPACK` on CPU. Streaming output is integrated seamlessly using a custom `MessageCallback` bridge.
- **Hybrid Retrieval (`HybridRetriever.kt`)**: Combines BM25-like SQLite FTS5 full-text search with vector similarity search via `EmbeddingIndex`.
- **Neural Cross-Encoder Reranker (`GteRerankerManager.kt`)**: Runs an INT8 quantized ONNX `Alibaba GTE` cross-encoder model to deeply evaluate query-excerpt pairs, scoring semantic relevance before prompt assembly.
- **Strict Citation Isolation (`OfflineRagPromptBuilder.kt`)**: Isolates note retrieval to grounded modes ("Talk to Notes") while suppressing citation injection in general AI mode ("Talk with AI Assistant").

### 2. Autonomous Memory Layer & Knowledge Graph
- **Memory Rebuild Engine (`MemoryRebuildWorker.kt`)**: Asynchronous worker processing updated notes into structured memory items:
  - **Commitment DAO**: Actions and tasks derived from user notes.
  - **Conflict DAO**: Contradictory statements or evolving facts.
  - **Review DAO**: Surfaceable memory items for daily/weekly digest.
- **Atomic File I/O (`AtomicJsonFile.kt`)**: Vector and segment index persistence utilizes atomic file replacement to prevent memory allocation spikes (OOM) and database corruption.

### 3. Speech-to-Text & Multi-Source Capture
- **Native Whisper JNI (`whisper.cpp`)**: C++ implementation compiled using NDK CMake for offline audio transcription directly on device.
- **Cloud STT Fallback**: Optional Deepgram integration for cloud speech-to-text.

---

## ⚡ Recent Major Updates

1. **Intro & Onboarding UI Redesign**:
   - Replaced basic onboarding with interactive multi-slide `IntroScreen.kt` detailing offline AI badges, top features, required on-device model setups, and privacy safeguards.
   - Created `NavigationModeUtils.kt` with comprehensive unit tests for adaptive layout modes.

2. **Google LiteRT-LM & OpenCL Hardware Acceleration**:
   - Migrated on-device inference to `.litertlm` model format with Gemma turn templating.
   - Declared `libOpenCL.so` native dependency and enabled `largeHeap` in `AndroidManifest.xml`.

3. **Alibaba GTE Neural Reranker & IBM Granite Embeddings**:
   - Integrated `GteTokenizer` (Unigram Viterbi tokenization) and `GteRerankerManager` for ONNX cross-attention reranking.
   - Upgraded default semantic embedding model to `ibm-granite/granite-embedding-311m-multilingual-r2`.

---

## 📁 Repository Directory Structure

```
NoteFlowAI/
├── app/
│   ├── build.gradle.kts           # App-level build config, dependencies, NDK setup
│   └── src/
│       ├── main/
│       │   ├── cpp/               # whisper.cpp native C++ implementation
│       │   │   ├── whisper.cpp
│       │   │   └── CMakeLists.txt # NDK CMake build instructions
│       │   ├── java/com/noteflowai/app/
│       │   │   ├── data/          # Room DB, DAOs, ONNX managers, LiteRT managers, RAG
│       │   │   ├── di/            # Dependency injection modules
│       │   │   ├── jni/           # JNI bridges for C++ libraries
│       │   │   ├── service/       # Background workers and memory rebuild schedulers
│       │   │   ├── ui/            # Jetpack Compose screens, components, theme
│       │   │   │   ├── screens/   # IntroScreen, NoteDetail, MemoryHub, Settings, Chat
│       │   │   │   └── theme/     # App typography, colors, NavigationModeUtils
│       │   │   ├── util/          # Prompt builders, atomic file I/O, tokenizers
│       │   │   ├── viewmodel/     # MainViewModel, SettingsViewModel, ChatViewModel
│       │   │   └── whisper/       # Audio recording and transcription controllers
│       │   └── res/               # Layouts, drawables, strings.xml, XML configs
│       └── test/                  # Unit tests (OfflineRagPromptBuilder, NavigationModeUtils, etc.)
├── macrobenchmark/                # Performance and startup benchmark tests
├── build.gradle.kts               # Project root Gradle configuration
├── gradle.properties              # JVM daemon arguments & build flags
├── SETUP.md                       # Developer environment setup instructions
└── HANDOFF.md                     # This handoff documentation
```

---

## 🛠️ Prerequisites & Build Guide

### Prerequisites:
- **Android Studio**: Hedgehog (2023.1.1) or later
- **JDK**: 17
- **NDK**: `27.0.12077973` (Installed via SDK Manager)
- **CMake**: `3.22.1` (Installed via SDK Manager)

### Build Commands:

```bash
# Clean project
./gradlew clean

# Build Debug APK
./gradlew assembleDebug

# Run Unit Tests
./gradlew testDebugUnitTest
```

### Release Signing:
Properties can be set in `local.properties` or environment variables (`KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`). See [SETUP.md](file:///h:/Work/NoteFlowAI/SETUP.md) for full instructions.

---

## 🤖 On-Device & Cloud AI Models Catalog

| Model Type | Default / Recommended Model | Storage Size | Runtime Engine | Location / Config |
| :--- | :--- | :--- | :--- | :--- |
| **Local LLM** | Gemma 4 E2B (`.litertlm`) | ~1.2 GB | Google LiteRT-LM | Settings > AI Engine |
| **Local LLM (Large)** | Gemma 4 E4B (`.litertlm`) | ~2.4 GB | Google LiteRT-LM | Settings > AI Engine |
| **Cross-Encoder Reranker** | Alibaba GTE ONNX INT8 | ~340 MB | ONNX Runtime | Settings > AI Intelligence |
| **Vector Embeddings** | IBM Granite 311M Multilingual | ~120 MB | ONNX Runtime | Settings > AI Intelligence |
| **Voice Speech-to-Text** | Whisper Base / Tiny | ~75 - 140 MB | whisper.cpp JNI | Settings > Voice & Speech |
| **Cloud LLM (Optional)** | OpenAI / Gemini / Claude / Ollama | N/A | HTTP Ktor API | Settings > Remote Providers |

---

## 🧪 Testing & Quality Assurance

The codebase includes an extensive Kotlin unit testing suite located under `app/src/test/java/`.

### Key Verified Tests:
- `NavigationModeUtilsTest`: Validates UI layout mode switches and window size breakpoints.
- `OfflineRagPromptBuilderTest`: Verifies grounded note citations and prompt isolation.
- `GteTokenizerTest`: Tests Unigram Viterbi tokenization for cross-encoder reranking.
- `LlmConfigTest`: Validates model configurations and format parsing.
- `MemoryRebuildWorkerTest`: Tests session resumption and background memory extraction.

Run all tests anytime with:
```bash
./gradlew testDebugUnitTest
```

---

## 🗺️ Roadmap & Next Steps for Developers

1. **LitertLM Quantization Fine-Tuning**: Explore 4-bit AWQ quantization for Gemma 4 models to decrease memory footprint on low-ram devices (<6GB RAM).
2. **Multi-Modal Document Visual RAG**: Extend local OCR pipeline to embed document images directly into vector space.
3. **P2P Encrypted Sync**: Implement direct local network device-to-device note synchronization.
4. **Android Widget Integration**: Expand quick capture widgets with push-to-talk voice recording directly from Android Home Screen.

---

*Handed off by NoteFlow AI Engineering Team.*
