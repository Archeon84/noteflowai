NoteFlowAI — App Profile (Audit & Improvement Ready)

1. Overview | What It Is

┌────────────┬─────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│  Property  │                                                      Value                                                      │
├────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Name       │ NoteFlowAI                                                                                                      │
├────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Platform   │ Android (Kotlin, Jetpack Compose, Room DB, SQLCipher)                                                           │
├────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Core       │ AI-powered personal knowledge & memory management: capture ideas via voice/chat, ground them in real-world      │
│ Purpose    │ entities and timelines, connect to the web for context, and surface relevant insights through proactive recall. │
├────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Current    │ Feature-complete with semantic-search toggle; RAG v2 pipeline wired via subagent-driven planning; deployed to   │
│ State      │ dev build.                                                                                                      │
└────────────┴─────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘

One-liner: A personal memory OS that turns captured ideas into grounded knowledge by linking them to real-world entities, timelines, and web sources — then surfacing what matters through AI recall.

---
2. Architecture | How It Works

High-Level Data Flow

Capture (voice/chat/PDF) → Extract Entities & Timeline → Embed/Vectorize → Semantic Index + BM25 Fusion → Query Planner Decomposes Intent → RAG Retrieve + Grounding Rules → Chat Response with Citations → Memory Rebuild (Entity mgmt / commit timeline)

Layered Stack

┌──────────────────┬───────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│      Layer       │                                            Tech & Design Notes                                            │
├──────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Presentation     │ Jetpack Compose, Material 3 teal palette (#00687A primary), ring-buffer debouncing for note list          │
│                  │ (ANR-guarded), goAsync + shimmer skeletons, master-detail intent routed to Chat/Notes screens.            │
├──────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Feature / Domain │ Feature-value objects: Note, MemoryObject, Entity, TimelineEntry. DAOs via Room for CRUD; SQLCipher on DB │
│                  │  file. Extraction pipeline splits a note into entities/timeline/web sources.                              │
├──────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Data /           │ Room → SQLCipher, embedding floor cosine 0.20 (per-complexity tweakable), Ollama branch verified with     │
│ Persistence      │ local model fallback.                                                                                     │
├──────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│                  │ TtsManager + new DeepgramTtsManager.kt: streaming MediaDataSource playback; Whisper.cpp for               │
│ AI / ML          │ transcription, Llama.cpp for local LLM inference; multilingual paraphrase embedding (qint8 quantized).    │
│                  │ AutoLinker now 8-signal fuse (BM25 + embeddings) with citation validity retry loop.                       │
├──────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Planning /       │ Subagent-driven RAG v2: Concept Memory Graph, Idea Evolution, Multi-Hop Reasoning, Query Decomposition;   │
│ Orchestration    │ audit findings fixed via adversarial verification.                                                        │
└──────────────────┴───────────────────────────────────────────────────────────────────────────────────────────────────────────┘

---
3. Features & Capabilities | What It Does

Content Ingestion

- Voice notes: Push-to-talk capture with mic-permission launcher (previously removed, see git status).
- Chat AI: Mid-messages crash-fixed (NPE in ragSources), back-navigation guard added.
- File import: PDF/DOCX/TXT/HTML + OCR pipeline wired; translator language coverage requested.

Knowledge Grounding

- Entities & Timelines: Auto-extraction into entity objects with temporal grounding.
- Semantic Search Toggle: Embedding-based cosine retrieval fused with BM25; floor threshold 0.20 relaxable per-complexity setting.
- Proactive Recall: Memory-layer flags (4 enabled by user) trigger surface suggestions across contexts.

Output & Export

- TTS to notes: Text-to-speech extended to note items; tap-to-stop race fixed via isActive guards + try/finally cleanup.
- AI Chat Export: Verification completed; supported-claim detector prevents hallucinated citations.

---
4. UX / Design Principles | How It Feels

┌────────────────────────┬─────────────────────────────────────────────────────────────────────────────────────────────────────┐
│       Principle        │                                           Implementation                                            │
├────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Calm & Productive      │ Teal palette reduces cognitive load over long sessions; ring-buffer debouncing keeps note list      │
│                        │ snappy without premature firing.                                                                    │
├────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Grounded Intelligence  │ Citations linked to entities/timelines make AI responses feel sourced, not generic.                 │
├────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Proactive but          │ Memory-layer flags surface contextually — no aggressive notifications or forced popups.             │
│ Unobtrusive            │                                                                                                     │
├────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Design-Conscious       │ Uniform standard margins at screen edges requested; microinteraction polish (button feedback,       │
│                        │ loading states) prioritized before launch.                                                          │
└────────────────────────┴─────────────────────────────────────────────────────────────────────────────────────────────────────┘

---
5. Technical Debt & Known Gaps | What Needs Work

Critical / High Priority

- Voice Command Service: Previously removed from git status — auto-start and mic-perm callback need re-evaluation if re-added; root cause was missing <queries> visibility on Android 11+.
- TTS Streaming: Current implementation uses time-based truncation (RST_STREAM CANCEL) rather than actual playback state — needs stream-aware shutdown to avoid silent stops.

Medium Priority

- YouTube Transcription Failures: Specific error codes need mapping to fallback models or user-facing retry prompts.
- Memory Rebuild: Commit timeline + entity mgmt UI still conceptual; needs component-level implementation before audit completion.

---
6. Platform Fit & Ecosystem | Where It Lives

┌───────────────────────┬──────────────────────────────────────────────────────────────────────────────────────────────────────┐
│        Aspect         │                                                Status                                                │
├───────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Android API           │ Jetpack Compose BOM migration completed; no remaining Bazel/Gradle conflicts (both use compatible    │
│                       │ versions).                                                                                           │
├───────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Offline-First         │ Room + SQLCipher local stack enables offline capture with sync later — verify on-device embedding    │
│ Readiness             │ inference path.                                                                                      │
├───────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Accessibility         │ A11y audit findings fixed per WCAG AA targets; verify current compliance before release signing.     │
└───────────────────────┴──────────────────────────────────────────────────────────────────────────────────────────────────────┘

---
7. Security & Privacy Considerations

- On-device AI: Whisper.cpp and Llama.cpp run locally — no raw audio or prompt data leaves device unless explicitly configured for cloud models (Gemini/OpenAI).
- Encryption-at-Rest: SQLCipher protects DB file; ensure key derivation tied to user auth state.
- API Key Hygiene: Previously dropped from backup ZIP; confirm no secrets in settings.local.json (fix already applied — deny array moved to sibling under permissions).

---
8. Performance Targets & Benchmarks

┌─────────────────────────┬───────────────────────────────────┬──────────────────────────────────────────────────────────────┐
│         Metric          │         Target / Current          │                            Notes                             │
├─────────────────────────┼───────────────────────────────────┼──────────────────────────────────────────────────────────────┤
│ Time-to-first-audio TTS │ ~1.9s streaming vs 39s file-based │ Measured on mid-tier device; verify across Android versions. │
├─────────────────────────┼───────────────────────────────────┼──────────────────────────────────────────────────────────────┤
│ Note List Debounce      │ Ring-buffer tuned to avoid ANR    │ Confirm wall-clock latency under 200ms for scroll events.    │
├─────────────────────────┼───────────────────────────────────┼──────────────────────────────────────────────────────────────┤
│ Embedding Cosine Floor  │ 0.20                              │ Per-complexity tweakable — log threshold changes at runtime. │
└─────────────────────────┴───────────────────────────────────┴──────────────────────────────────────────────────────────────┘