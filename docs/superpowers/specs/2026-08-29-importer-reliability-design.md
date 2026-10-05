# Design Specification: Phase 8 Importer Reliability

**Date:** 2026-08-29
**Status:** Approved
**Target Phase:** Phase 8 of NoteFlowAI Agentic Guide (lines 1190-1258)

---

## 1. Overview and Objectives

NoteFlowAI supports rich multi-modal capture across documents (PDF, DOCX, TXT, HTML), images (OCR), audio files, and YouTube videos. Phase 8 establishes a unified, recoverable, and type-safe ingestion pipeline where:
1. Every input can be **inspected** before ingestion to verify support, detect encryption/passwords, and estimate size.
2. Ingestion produces typed **ImportResult** outcomes with granular **ImportError** categorizations.
3. Errors provide plain-language explanations, recovery recommendations, and local fallbacks without infinite retry loops.
4. Transient failures integrate with `RawCaptureRepository` and `CaptureJobScheduler` to enable one-tap resumable recovery.

---

## 2. Core Architecture and Interfaces

```
                                  ImportInput
                                       │
                                       ▼
                         ┌───────────────────────────┐
                         │  ContentImporterRegistry  │
                         └─────────────┬─────────────┘
                                       │ resolves
                                       ▼
                           ┌───────────────────────┐
                           │    ContentImporter    │
                           └───────────┬───────────┘
                                       │
                    ┌──────────────────┴──────────────────┐
                    ▼                                     ▼
     suspend fun inspect(input)            suspend fun import(input)
                    │                                     │
                    ▼                                     ▼
            ImportInspection                         ImportResult
                                             ┌────────────┴────────────┐
                                             ▼                         ▼
                                          Success                   Failure
                                                              (with ImportError)
```

### 2.1 Interface Definition

```kotlin
package com.noteflowai.app.data.importer

interface ContentImporter {
    /**
     * Inspects input metadata, support status, and encryption/password requirements
     * without performing full ingestion.
     */
    suspend fun inspect(input: ImportInput): ImportInspection

    /**
     * Ingests the content and extracts structured text and metadata.
     */
    suspend fun import(input: ImportInput): ImportResult
}
```

### 2.2 Models

```kotlin
data class ImportInput(
    val uri: android.net.Uri? = null,
    val file: java.io.File? = null,
    val url: String? = null,
    val mimeType: String? = null,
    val password: String? = null,
    val title: String? = null
)

data class ImportInspection(
    val isSupported: Boolean,
    val isPasswordProtected: Boolean = false,
    val formatLabel: String,
    val estimatedBytes: Long = 0L,
    val pageCount: Int? = null,
    val durationMs: Long? = null,
    val requiresNetwork: Boolean = false
)

sealed class ImportResult {
    data class Success(
        val text: String,
        val title: String,
        val format: String,
        val wordCount: Int,
        val charCount: Int,
        val sourceSegmentIds: List<String> = emptyList(),
        val rawCaptureId: String? = null
    ) : ImportResult()

    data class Failure(
        val error: ImportError,
        val rawMessage: String,
        val retryable: Boolean = false,
        val fallbackAction: FallbackAction? = null
    ) : ImportResult()
}

enum class FallbackAction {
    RUN_OCR,
    IMPORT_AUDIO,
    TRANSCRIBE_LOCALLY,
    RETRY_LATER,
    CHOOSE_ANOTHER_FILE,
    ENTER_PASSWORD
}
```

### 2.3 Typed Errors

```kotlin
sealed class ImportError(val code: String, val userMessageResId: Int) {
    data object UnsupportedFormat : ImportError("unsupported_format", R.string.import_error_unsupported_format)
    data object PasswordProtected : ImportError("password_protected", R.string.import_error_password_protected)
    data object EmptyContent : ImportError("empty_content", R.string.import_error_empty_content)
    data object TranscriptUnavailable : ImportError("transcript_unavailable", R.string.import_error_transcript_unavailable)
    data object PrivateContent : ImportError("private_content", R.string.import_error_private_content)
    data object NetworkUnavailable : ImportError("network_unavailable", R.string.import_error_network_unavailable)
    data object RateLimited : ImportError("rate_limited", R.string.import_error_rate_limited)
    data object ProviderUnavailable : ImportError("provider_unavailable", R.string.import_error_provider_unavailable)
    data object MalformedContent : ImportError("malformed_content", R.string.import_error_malformed_content)
}
```

---

## 3. Concrete Content Importers

| Importer | Supported MIME / Schemes | Underlying Technology | Error Handling & Fallbacks |
| :--- | :--- | :--- | :--- |
| `PdfImporter` | `application/pdf` | PDFBox Android (`PDDocument`, `PDFTextStripper`) | Catches encrypted PDFs -> `PasswordProtected` (`ENTER_PASSWORD`); empty extracted text -> `EmptyContent` (`RUN_OCR`). |
| `DocxImporter` | `application/vnd.openxmlformats-officedocument.wordprocessingml.document` | Apache POI (`XWPFDocument`) | Detects password/corrupted ZIP streams -> `PasswordProtected` or `MalformedContent`. |
| `TextImporter` | `text/plain`, `text/markdown`, `text/csv`, `text/x-*` | Standard UTF-8 / UTF-16 stream readers with BOM detection | Validates non-empty characters -> `EmptyContent` on 0 readable chars. |
| `HtmlImporter` | `text/html` | Jsoup HTML Parser | Extracts main article body; strips script/style tags -> `EmptyContent` if no text. |
| `ImageOcrImporter` | `image/jpeg`, `image/png`, `image/webp`, `image/heic` | Google ML Kit Text Recognition | Returns extracted text or `EmptyContent` if no text recognized in image. |
| `YoutubeTranscriptImporter` | `https://www.youtube.com/*`, `https://youtu.be/*` | InnerTube Transcript + Web Player Captions | Detects disabled captions -> `TranscriptUnavailable` (`TRANSCRIBE_LOCALLY`); private/geo-blocked -> `PrivateContent`; HTTP 429 -> `RateLimited` (`RETRY_LATER`). |
| `AudioImporter` | `audio/wav`, `audio/mpeg`, `audio/mp4`, `audio/ogg`, `audio/m4a` | Local Whisper.cpp (offline) / Deepgram (cloud) | Inspects audio duration; returns `EmptyContent` on silent/unsupported streams. |

---

## 4. Importer Registry and Resolution

`ContentImporterRegistry` resolves the right importer for any `ImportInput`:
1. If `url` is a YouTube link -> returns `YoutubeTranscriptImporter`.
2. If `mimeType == application/pdf` or file extension is `.pdf` -> `PdfImporter`.
3. If `mimeType` is DOCX or extension is `.docx` -> `DocxImporter`.
4. If `mimeType` is HTML or extension is `.html`/`.htm` -> `HtmlImporter`.
5. If `mimeType` starts with `image/` or extension in `jpg, jpeg, png, webp, heic` -> `ImageOcrImporter`.
6. If `mimeType` starts with `audio/` or extension in `wav, mp3, m4a, ogg, aac` -> `AudioImporter`.
7. If `mimeType` starts with `text/` or extension in `txt, md, json, csv` -> `TextImporter`.
8. Otherwise -> returns `UnsupportedFormat` failure on inspection/import.

---

## 5. UI Integration & Error Action Handling

In `DocumentScreen`, `YouTubeScreen`, `ScanScreen`, and `RecordScreen`:
- Inspection runs before heavy imports where applicable.
- Failures display the plain-language string with an interactive action button corresponding to `fallbackAction`:
  - `ENTER_PASSWORD`: Displays password prompt dialog and re-runs `import(input.copy(password = ...))`.
  - `RUN_OCR`: Prompts user to scan/OCR the document images.
  - `TRANSCRIBE_LOCALLY`: Routes to local audio recording/transcription.
  - `RETRY_LATER`: Sets a delayed retry banner.

---

## 6. Spec Self-Review Checklist

- [x] **Placeholder scan:** No TBD/TODOs. All class names, interfaces, methods, and error types specified verbatim from guide lines 1190-1258.
- [x] **Internal consistency:** Aligns with existing `DocumentRepository`, `YouTubeRepository`, and `RawCaptureRepository`.
- [x] **Scope check:** Strictly covers Phase 8 Importer Reliability.
- [x] **Ambiguity check:** Plain-language user messages and typed error codes defined.
