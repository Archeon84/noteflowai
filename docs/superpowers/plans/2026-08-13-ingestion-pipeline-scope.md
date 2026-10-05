# Ingestion Pipeline Scope (§7 of Personal Memory Layer Master Plan)

Date: 2026-08-13
Status: Scoped, not yet implemented
Plan: `Personal Memory Layer Master Plan.md` §7 (Ingestion Pipeline) and §25 (Implementation Order)

---

## 1. Goal

Turn the current fire-and-forget source→segment→extraction flow into a resumable,
multi-stage pipeline with **persistent per-stage status**, so that:

- Every processed source has a durable record of which stages completed.
- Work survives app restart (pending/running sources are re-enqueued on launch).
- Stages are retryable independently, with backoff for retryable failures.
- The same source is never processed twice concurrently.
- The pipeline coordinates the stages §7 lists: normalization, entity extraction,
  memory extraction, embedding generation, full-text indexing, relation detection,
  and completion.

The plan permits WorkManager **or** "the app's existing equivalent." This scope keeps
the existing coroutine-based worker and adds the missing durability + stages, because
that reuses what already works and avoids a new scheduling dependency. WorkManager is
listed in §9 as a later hardening option if Doze/foreground reliability requires it.

## 2. Current state (verified 2026-08-13)

The ingestion path today is a two-step hook chain:

```
save/import event
  → SourceSegmentManager.onNoteSaved / onTranscriptionSaved / onDocumentImported /
    onOcrCompleted / onYouTubeTranscriptFetched / onGeneratedNoteSaved
      → adapter (NoteBlock / WhisperSegment / DeepgramUtterance / DocumentChunk /
        OcrBlock / YouTubeSegment / GeneratedNote) builds SourceSegments
      → SourceSegmentRepository.insertAll / insert
      → triggerExtraction(sourceId)
        → ExtractionWorker.extract(sourceId)
          → MemoryExtractionService.extract(segments)
            → single LLM call extracts entities + decisions + commitments + other
            → repos store DETECTED objects + MemoryReviewItems
```

Key facts:

- **`ExtractionWorker`** (`data/memory/extraction/ExtractionWorker.kt`) is a coroutine
  worker on `Dispatchers.IO` with:
  - duplicate prevention via `ConcurrentHashMap<String, Job>` (`putIfAbsent`);
  - an **in-memory only** status map `StateFlow<Map<String, ExtractionStatus>>`
    (`ExtractionStage`: PENDING, RUNNING, COMPLETED, FAILED_RETRYABLE,
    FAILED_PERMANENT, CANCELLED). Nothing is persisted.
  - `extract` / `cancel` / `retry` API.
- **`SourceSegmentManager`** (`data/memory/repository/SourceSegmentManager.kt`) exposes
  the 6 `on*` hooks plus `onSourceDeleted`. All gated on `enableSourceSegmentsBlocking`;
  extraction additionally gated on `enableMemoryExtractionBlocking`.
- **`MemoryExtractionService.extract(segments): ExtractionResult`** does entity +
  decision + commitment + other-memory extraction in one LLM call, creates DETECTED
  objects and review items. It does NOT do embedding, indexing, or relation detection.
- **No WorkManager dependency** exists in `app/build.gradle.kts`.
- **Existing indexing infrastructure indexes NOTE files, not source segments:**
  - `NoteSearchIndex` (`data/search/NoteSearchIndex.kt`) — BM25 over `NoteFile`,
    `rebuildIndex(notes)`, `search(...)`, disk persistence via `NoteIndexPersistence`.
  - `EmbeddingIndex`, `OnDeviceEmbedder`, `RemoteEmbeddingClient` (`data/search/`).
  - `ConceptGraph` / `ConceptGraphRepository` (`data/concept/`).
  - None of these consume `SourceSegment`s today.
- **`MemoryDatabase`** (`data/memory/db/MemoryDatabase.kt`) is at **version 4**, with
  migrations 1→2, 2→3, 3→4. There is **no table tracking per-source processing status.**
  DAOs are provided via `MemoryDatabaseModule.provideX(context)` which delegate to the
  `MemoryDatabase.getInstance(context)` singleton.
- **`MemoryRelation` model + DAO exist**; no relation-detection service populates them.

## 3. Gap analysis against §7

| §7 requirement | Current state | Gap |
|---|---|---|
| Resumable stages | single `extract()` step | multi-stage pipeline not present |
| Persistent stage status | in-memory StateFlow only | lost on restart; no per-stage granularity |
| `PENDING/RUNNING/COMPLETED/FAILED_RETRYABLE/FAILED_PERMANENT/CANCELLED` | enum exists on `ExtractionWorker` | not persisted, not per-stage |
| Retry independently per stage | `retry(sourceId)` reruns whole extraction | needs per-stage retry |
| Unique work per source (`process_source_{sourceId}`) | putIfAbsent on job map | exists; keep, move to pipeline |
| Progress reporting | none | add stage field + timestamps |
| Cancellation support | `cancel(sourceId)` | exists; keep |
| Foreground execution if required | none | deferred (see §9) |
| `SourceProcessingWorker` | — | replaced by `SourceProcessingPipeline` (coroutine) |
| `EntityExtractionWorker` | — | entity extraction currently inside one LLM call |
| `MemoryExtractionWorker` | `ExtractionWorker` | rename/keep |
| `EmbeddingWorker` | — | source segments are not embedded |
| `IndexingWorker` | — | source segments not full-text indexed |
| `RelationDetectionWorker` | — | `MemoryRelation` never populated |
| Survives restart | no | needs re-enqueue on app start |
| Duplicate processing prevention | job map | keep + persist "in progress" marker |

## 4. Design decisions

1. **Stay on the coroutine worker, do not add WorkManager now.** The app's existing
   equivalent (§7 allows it) is `ExtractionWorker`. Adding persistent status + a stage
   pipeline + restart re-enqueue meets the functional requirements without a new
   dependency. WorkManager is a Phase-8 hardening option (see §9).
2. **Persist processing status in Room.** New `ProcessingStatus` entity + DAO, schema
   version 4→5 (new table, additive, no data rewrite). Keeps the `ExtractionStage` enum
   values but stores per-stage state.
3. **One pipeline, sequential stages per source.** Stages:
   `SEGMENTS_CREATED → EXTRACTION → EMBEDDING → INDEXING → RELATION_DETECTION → COMPLETE`.
   Normalization/segment creation already happens in the hooks; the pipeline starts at
   extraction.
4. **Reuse existing indexing instead of building a second one where possible.** Add a
   lightweight source-segment index that reuses `NoteSearchIndex`'s tokenizer/BM25 and
   the existing `EmbeddingIndex` client, OR extend those to accept segments. Decision
   left open in §6 (flagged as the main open question).
5. **Extraction stays a single LLM call** (entity + memory objects together) for the
   first pipeline cut, matching today's behavior; a dedicated entity-extraction stage is
   deferred because the schema/validation already handles both.
6. **Restart re-enqueue** via `MainViewModel`/Application init scanning
   `ProcessingStatus` for rows not `COMPLETE` and re-enqueuing them (after clearing
   stale `RUNNING` → `FAILED_RETRYABLE`).

## 5. New components

### 5.1 `ProcessingStatus` entity + DAO (Room v5)

Entity `processing_status`:
- `sourceId` TEXT PK
- `sourceType` TEXT
- `currentStage` TEXT (enum name)
- `status` TEXT (PENDING/RUNNING/COMPLETED/FAILED_RETRYABLE/FAILED_PERMANENT/CANCELLED)
- `error` TEXT NULL
- `attempts` INTEGER
- `startedAt` / `updatedAt` / `completedAt` INTEGER

DAO: `getByStatus`, `getPendingOrRunning`, `getBySourceId`, `upsert`, `markStage`,
`markComplete`, `markFailed`, `deleteBySourceId`.

Migration `MIGRATION_4_5`: `CREATE TABLE IF NOT EXISTS processing_status (...)` + index.

### 5.2 `SourceProcessingPipeline`

`class SourceProcessingPipeline(context)` in `data/memory/pipeline/`:
- `suspend fun processSource(sourceId: String, sourceType: SourceType)` — runs each
  stage with persisted status transitions, per-stage error handling.
- Wraps `MemoryExtractionService`, a new `SegmentIndexService`, and a new
  `RelationDetectionService`.
- Keeps the in-memory `ConcurrentHashMap<String, Job>` dedup.
- `retryStage(sourceId, stage)` for independent retry.

### 5.3 Stage implementations

- **Extraction**: reuse `MemoryExtractionService.extract(segments)` (already returns
  counts + errors). Mark `EXTRACTION` COMPLETE or FAILED_RETRYABLE.
- **Embedding**: `SegmentEmbeddingService` — generate embeddings for the source's
  segments via the existing `OnDeviceEmbedder` / `RemoteEmbeddingClient`, store into
  the existing `EmbeddingIndex` (or a segment-scoped index).
- **Indexing**: `SegmentIndexService` — BM25-index segment text via `NoteSearchIndex`
  pattern (or a new `SegmentSearchIndex`) so retrieval can return segment-level hits.
- **Relation detection**: `RelationDetectionService` — conservative relation links
  (MENTIONS/SUPPORTS/CONTRADICTS/FOLLOWS_UP/BELONGS_TO) between new memory objects and
  existing ones, low-confidence only, stored in `memory_relations`.

### 5.4 Restart re-enqueue

`SourceProcessingPipeline.recoverPending(context)` — called from app init
(`MainViewModel` init or `Application.onCreate`): flip stale `RUNNING` → `FAILED_RETRYABLE`,
then `processSource` for every non-COMPLETE source. Gated on the same feature flags.

## 6. Files to create / modify

New:
- `data/memory/model/ProcessingStatus.kt` (entity)
- `data/memory/dao/ProcessingStatusDao.kt`
- `data/memory/db/MIGRATION_4_5` (inside `MemoryDatabase.kt`)
- `data/memory/pipeline/SourceProcessingPipeline.kt`
- `data/memory/pipeline/SegmentEmbeddingService.kt`
- `data/memory/pipeline/SegmentIndexService.kt`
- `data/memory/pipeline/RelationDetectionService.kt`
- `data/memory/repository/ProcessingStatusRepository.kt` (thin wrapper, pattern-matched)

Modified:
- `data/memory/db/MemoryDatabase.kt` — add entity + DAO + MIGRATION_4_5 (version 5)
- `data/memory/db/MemoryDatabaseModule.kt` — `provideProcessingStatusDao`
- `data/memory/repository/SourceSegmentManager.kt` — replace `triggerExtraction` with
  `pipeline.processSource(...)` behind flags
- `data/memory/extraction/ExtractionWorker.kt` — fold into/be replaced by pipeline
  (keep `ExtractionStage` enum for compatibility), or keep as the EXTRACTION stage runner
- `viewmodel/MainViewModel.kt` — call `recoverPending()` on init (flag-gated)
- `app/schemas/` — Room will emit `5.json` for the new version

Open question (decide during implementation):
- Embedding/indexing of source segments: extend `NoteSearchIndex`/`EmbeddingIndex` to
  accept segments, or add a dedicated `SegmentSearchIndex`. This drives whether §14
  retrieval can return segment-level citations for all sources.

## 7. Feature flags

Reuse existing `SettingsManager` flags. Proposal:
- `ENABLE_SOURCE_SEGMENTS` (existing) gates segment creation.
- `ENABLE_MEMORY_EXTRACTION` (existing) gates the EXTRACTION stage.
- New `ENABLE_SEGMENT_EMBEDDING` + `ENABLE_SEGMENT_INDEXING` (or reuse one flag) gate
  EMBEDDING + INDEXING.
- `ENABLE_MEMORY_DATABASE` (existing) gates the whole pipeline.

## 8. Verification

1. Compile: `./gradlew assembleDebug`.
2. Unit tests (§22): pipeline stage transitions, status persistence, restart recovery,
   relation-detection conservatism, index/embedding of segments.
3. Manual: save a note → confirm `processing_status` row transitions to COMPLETE;
   kill app mid-extraction → relaunch → source re-processed and completes.
4. No duplicate workers: enqueue twice quickly → one pipeline run (dedup map + persisted
   RUNNING marker).
5. Regression: existing note save/delete, transcription, document import unchanged.

## 9. Deferred / out of scope

- WorkManager adoption (unique `process_source_{sourceId}` enqueue, foreground service,
  Doze-safe scheduling) — Phase-8 hardening if needed.
- Foreground execution for long-running extraction.
- Dedicated entity-resolution stage (§13) and processing-policy/privacy (§20) — separate
  workstreams, not part of this pipeline cut.
- Embedding/index dedup strategy beyond what exists.
