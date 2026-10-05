<img src="https://r2cdn.perplexity.ai/pplx-full-logo-primary-dark%402x.png" style="height:64px;margin-right:32px"/>

```markdown
# NoteFlowAI Agentic Implementation Guide

## Document Purpose

This document is the operating specification for an agentic AI coding system responsible for implementing, testing, reviewing, and improving NoteFlowAI.

The agent must work incrementally inside the existing repository. It must inspect the current implementation before editing, preserve working behavior, make small changes, run tests, review its own changes, and report progress clearly.

The agent must not rewrite NoteFlowAI from scratch.

---

# 1. Project Context

## Product

NoteFlowAI is a local-first personal knowledge and memory application.

Its purpose is to capture ideas and information through voice, text, chat, documents, and OCR; connect content to entities and timelines; index content for keyword and semantic retrieval; answer questions using grounded RAG; provide citations; and surface useful memories at the right time.

## Current Technology

- Android.
- Kotlin.
- Jetpack Compose.
- Material 3.
- Room.
- SQLCipher.
- WorkManager where appropriate.
- Whisper.cpp for local transcription.
- Llama.cpp for local LLM inference.
- Optional cloud providers such as Gemini, OpenAI, and Deepgram.
- BM25 keyword retrieval.
- Embedding-based semantic retrieval.
- Hybrid retrieval and score fusion.
- Entity extraction.
- Timeline extraction.
- Citation-aware AI chat.
- Text-to-speech.
- PDF, DOCX, TXT, HTML, OCR, and possible YouTube ingestion.

## Existing High-Level Pipeline

```text
Capture
  ↓
Persist raw content
  ↓
Transcribe or normalize
  ↓
Extract entities and timelines
  ↓
Chunk content
  ↓
Generate embeddings
  ↓
Build BM25 and semantic indexes
  ↓
Retrieve relevant context
  ↓
Generate grounded answer
  ↓
Validate citations
  ↓
Display answer and sources
  ↓
Allow user correction
```


## Current Product Direction

The long-term product direction is:

> Capture anything once, connect it to existing knowledge, and bring it back when it becomes useful.

The primary engineering priority is not adding more AI features. It is making the capture-to-recall workflow reliable, understandable, private, and recoverable.

---

# 2. Known Issues and Gaps

The agent must treat the following as known engineering work, not assumptions of completion:

1. Raw capture must be persisted before AI or network processing begins.
2. AI processing must be represented as durable, retryable background jobs.
3. TTS streaming must use actual playback state instead of time-based cancellation.
4. Voice-command permission and lifecycle behavior require careful verification.
5. YouTube transcription failures need typed errors and user-facing fallback actions.
6. Entity management and timeline commitment interfaces are incomplete.
7. Memory rebuild must preserve original notes and rebuild only derived data.
8. Local embedding inference must be tested on representative devices.
9. SQLCipher key handling must be audited.
10. Backups, temporary files, caches, logs, and exports must be reviewed for privacy leaks.
11. Retrieval thresholds and weights require an evaluation dataset.
12. Citation validation must prevent unsupported claims.
13. Accessibility and performance claims require current verification.
14. Proactive recall and advanced web enrichment must remain feature-flagged until the core workflow is stable.

The existing profile reports an embedding floor of approximately `0.20`, hybrid BM25-plus-embedding retrieval, local AI components, citation validation, and an approximately `1.9 second` TTS first-audio measurement; these values must be treated as configurable or measured targets until reproduced across representative devices and datasets. [file:1]

---

# 3. Agent Mission

The agent must:

- Improve reliability before adding complexity.
- Preserve user data.
- Keep raw source content separate from derived AI data.
- Make every long-running task observable.
- Make every AI result editable or rejectable.
- Make failures understandable and recoverable.
- Keep privacy boundaries explicit.
- Use tests as implementation constraints.
- Maintain architectural consistency.
- Avoid unnecessary dependencies and rewrites.
- Stop when assumptions are unclear or data-loss risk exists.

The agent is responsible for implementation quality, not merely code generation.

---

# 4. Non-Negotiable Agent Rules

## Inspection Rules

Before changing code, the agent must:

1. Inspect the repository structure.
2. Identify modules and packages.
3. Inspect Gradle files and version configuration.
4. Identify the minimum and target Android SDK.
5. Identify Kotlin, Compose, Room, SQLCipher, and WorkManager versions.
6. Build the project.
7. Run the current test suite.
8. Locate relevant existing implementations.
9. Identify migrations and database schema history.
10. Produce a file-level implementation plan.

The agent must not edit code during the initial inspection stage.

## Scope Rules

- Work on one phase at a time.
- Do not begin a later phase until the current phase compiles and passes relevant tests.
- Do not modify unrelated files.
- Do not perform broad refactors unless required for correctness.
- Do not rename large portions of the project without approval.
- Do not introduce a new architecture merely because it is preferred.
- Do not delete existing functionality without a tested replacement.
- Do not change product behavior silently.


## Data-Safety Rules

- Persist raw captures before processing.
- Never delete original notes during a rebuild.
- Never replace source content with AI-generated text without preserving the original.
- Use database transactions for related state changes.
- Use migrations for schema changes.
- Make processing workers idempotent.
- Ensure process death does not cause data loss.
- Ensure failed AI processing does not make the original note inaccessible.
- Preserve user edits separately from automatic extraction.


## Threading Rules

- Never perform database work on the main thread.
- Never perform network work on the main thread.
- Never perform file operations on the main thread.
- Never run local model inference on the main thread.
- Use structured concurrency.
- Do not use `GlobalScope`.
- Cancel work when lifecycle or user action requires it.
- Avoid unbounded coroutine launches.
- Use `Dispatchers.IO` for file and database operations.
- Use `Dispatchers.Default` for CPU-heavy processing when appropriate.


## Privacy Rules

Never place the following in production logs:

- Raw note text.
- Audio content.
- Transcripts.
- LLM prompts.
- LLM responses containing private data.
- Retrieved private passages.
- API keys.
- Access tokens.
- Database keys.
- Personal identifiers.

The agent must verify:

- Cloud providers are explicit and configurable.
- Local-only mode makes no cloud requests.
- Temporary files are cleaned up.
- Backups do not contain secrets.
- Exports are protected appropriately.
- Database keys are protected with Android Keystore.
- Deleting derived data does not unexpectedly delete source notes.


## AI Output Rules

- Treat all model output as untrusted.
- Validate structured output.
- Handle malformed JSON.
- Handle missing fields.
- Handle unexpected enum values.
- Handle unsupported citations.
- Never trust model-generated citation IDs without validation.
- Never allow the model to invent source references.
- Prefer an evidence-limited answer over a fabricated answer.
- Label inferred entities and dates as suggestions until confirmed.
- Preserve confidence and provenance.


## Dependency Rules

Before adding a dependency, the agent must report:

- Why it is needed.
- Why existing dependencies are insufficient.
- Version compatibility.
- Size and runtime impact.
- Licensing concerns.
- Security implications.
- Test impact.

Do not add a dependency for a problem that can be solved safely with existing project code.

---

# 5. Agent Roles

The primary agent coordinates all work. Supporting agents must analyze narrowly and must not directly edit production code unless explicitly instructed.

## 5.1 Principal Engineer

Responsibilities:

- Understand the full architecture.
- Choose implementation order.
- Preserve existing behavior.
- Coordinate subagents.
- Review changes.
- Resolve conflicts.
- Approve phase completion.

Output:

- Architecture assessment.
- Implementation plan.
- Risk assessment.
- Final integration decision.


## 5.2 Repository Analyst

Responsibilities:

- Inspect project modules.
- Locate relevant classes and interfaces.
- Map dependencies.
- Identify existing tests.
- Identify technical debt.
- Identify likely files requiring changes.

Output:

```text
Repository area:
Current implementation:
Missing behavior:
Files involved:
Risks:
Tests required:
```

The Repository Analyst must not edit production code.

## 5.3 Android Architecture Reviewer

Responsibilities:

- Review Kotlin and Android architecture.
- Check lifecycle handling.
- Check coroutine usage.
- Check WorkManager usage.
- Check Room and migration safety.
- Check Compose state handling.
- Detect main-thread work.
- Detect memory and resource leaks.

Output categories:

- Critical.
- High.
- Medium.
- Low.


## 5.4 Data and Database Engineer

Responsibilities:

- Review Room entities.
- Review DAOs.
- Review transactions.
- Review migrations.
- Review SQLCipher initialization.
- Review deletion and rebuild behavior.
- Review backup and restore behavior.

Focus:

- Data loss.
- Duplicate records.
- Inconsistent derived data.
- Broken migrations.
- Unsafe encryption-key handling.


## 5.5 AI and RAG Engineer

Responsibilities:

- Review transcription.
- Review embeddings.
- Review chunking.
- Review BM25 retrieval.
- Review vector retrieval.
- Review score fusion.
- Review query planning.
- Review citation validation.
- Review refusal behavior.
- Build evaluation datasets.

Focus:

- Retrieval quality.
- Unsupported claims.
- Citation correctness.
- Multilingual behavior.
- Latency and model failures.


## 5.6 Privacy and Security Reviewer

Responsibilities:

- Audit secrets.
- Audit logs.
- Audit cloud boundaries.
- Audit database-key management.
- Audit backups.
- Audit caches.
- Audit temporary files.
- Audit exports and deletion.

The Security Reviewer must explicitly report any uncertain security assumption.

## 5.7 Compose UX and Accessibility Reviewer

Responsibilities:

- Review navigation.
- Review loading and failure states.
- Review status visibility.
- Review empty states.
- Review touch targets.
- Review TalkBack behavior.
- Review font scaling.
- Review contrast and theming.
- Review lifecycle state restoration.


## 5.8 Test Engineer

Responsibilities:

- Add unit tests.
- Add repository tests.
- Add DAO and migration tests.
- Add WorkManager tests.
- Add Compose UI tests.
- Add integration tests.
- Add adversarial RAG tests.
- Add regression tests for fixed bugs.

The Test Engineer must test failure paths, not only happy paths.

## 5.9 Performance Engineer

Responsibilities:

- Measure startup.
- Measure scrolling.
- Measure search latency.
- Measure retrieval latency.
- Measure transcription time.
- Measure embedding time.
- Measure TTS first-audio time.
- Measure memory, CPU, battery, and thermal behavior.

No performance claim should be accepted without a reproducible measurement method.

---

# 6. Agent Operating Workflow

For every phase, follow this process:

```text
Inspect
  ↓
Plan
  ↓
Report risks
  ↓
Implement smallest coherent slice
  ↓
Compile
  ↓
Run targeted tests
  ↓
Run regression tests
  ↓
Review diff
  ↓
Fix issues
  ↓
Report result
  ↓
Wait for approval
```


## Phase Start Report

Before editing, the agent must return:

```text
Phase:
Objective:

Relevant files inspected:
Existing behavior:
Missing behavior:

Planned files to modify:
Planned files to create:
Planned files to delete:

Data risks:
Privacy risks:
Lifecycle risks:
Testing plan:

Commands to run:
```


## Phase Completion Report

After implementation, the agent must return:

```text
Phase completed:

Implemented:
- Item
- Item
- Item

Files changed:
- path/to/file
- path/to/file

Tests added:
- Test name
- Test name

Tests run:
- Command
- Result

Build result:
- Debug build:
- Release-related checks:

Manual verification:
- Item
- Item

Remaining issues:
- Item

Known risks:
- Item

Recommended next phase:
```

The agent must not claim success if the build or tests fail.

---

# 7. Implementation Phases

## Phase 0: Baseline and Feature Flags

### Objective

Create a safe foundation for incremental implementation.

### Tasks

1. Inspect and document the repository.
2. Confirm the project builds.
3. Run existing tests.
4. Add or improve typed feature flags.
5. Add safe structured metadata-only logging.
6. Document build and test commands.
7. Create a release checklist if appropriate.

### Feature Flags

Support at least:

```kotlin
data class FeatureFlags(
    val semanticSearch: Boolean = true,
    val entityExtraction: Boolean = true,
    val timelineExtraction: Boolean = true,
    val webGrounding: Boolean = false,
    val proactiveRecall: Boolean = false,
    val localLlm: Boolean = true,
    val cloudLlm: Boolean = false,
    val voiceCommandService: Boolean = false
)
```

Do not duplicate feature-flag values throughout the codebase.

### Logging Metadata

Log only:

- Job ID.
- Note ID hash or safe identifier.
- Stage.
- Status.
- Duration.
- Provider.
- Model.
- Item count.
- Safe error code.


### Tests

- Feature-flag defaults.
- Logger redaction.
- Existing build.
- Existing tests.


### Exit Criteria

- Project builds.
- Existing tests pass.
- Feature flags are injectable.
- No sensitive data is logged.
- Agent produces a file-level change report.

---

## Phase 1: Durable Capture and Processing Jobs

### Objective

Make raw capture reliable and independent of AI processing.

### Data Model

Implement or improve:

```kotlin
@Entity
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val sourceType: SourceType,
    val rawText: String?,
    val audioUri: String?,
    val status: NoteStatus,
    val deletedAt: Instant? = null
)
```

```kotlin
@Entity
data class ProcessingJobEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val stage: ProcessingStage,
    val status: JobStatus,
    val attempt: Int,
    val createdAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val errorCode: String?,
    val errorMessageSafe: String?
)
```


### Processing Stages

```text
CAPTURED
TRANSCRIBING
TRANSCRIBED
NORMALIZING
NORMALIZED
CHUNKING
CHUNKED
EMBEDDING
EMBEDDED
EXTRACTING_ENTITIES
ENTITIES_EXTRACTED
EXTRACTING_TIMELINE
TIMELINE_EXTRACTED
INDEXING
INDEXED
READY
FAILED
```


### Required Behavior

When capture finishes:

1. Stop and close recorder.
2. Flush the audio file.
3. Insert the raw note.
4. Insert the first processing job.
5. Commit the transaction.
6. Display the note immediately.
7. Schedule processing.
8. Return control to the user.

### WorkManager Requirements

Workers must:

- Be idempotent.
- Support cancellation.
- Use unique work per note.
- Use retry policies.
- Distinguish transient and permanent errors.
- Persist stage state.
- Resume after process death.
- Avoid duplicate processing.


### Acceptance Criteria

- Captured note appears immediately.
- Force-close does not lose the note.
- Device restart resumes processing.
- Offline capture works.
- Failed processing leaves the note intact.
- Search still works when embeddings fail.
- Retry is available.
- No main-thread processing occurs.

---

## Phase 2: TTS Playback State Machine

### Objective

Replace time-based stream cancellation with true playback-state management.

### Playback State

```kotlin
sealed interface PlaybackState {
    data object Idle : PlaybackState

    data class Preparing(
        val itemId: String
    ) : PlaybackState

    data class Playing(
        val itemId: String,
        val positionMs: Long,
        val durationMs: Long?
    ) : PlaybackState

    data class Paused(
        val itemId: String,
        val positionMs: Long
    ) : PlaybackState

    data class Failed(
        val itemId: String,
        val code: String
    ) : PlaybackState
}
```


### Interface

```kotlin
interface TtsManager {
    val state: StateFlow<PlaybackState>

    suspend fun play(itemId: String, text: String)
    suspend fun pause()
    suspend fun resume()
    suspend fun stop()
    suspend fun release()
}
```


### Stop Requirements

`stop()` must:

1. Cancel the playback coroutine.
2. Cancel the network request.
3. Close response bodies.
4. Stop the player.
5. Clear media sources.
6. Abandon audio focus.
7. Prevent stale callbacks.
8. Return state to `Idle`.

### Required Tests

- Play then stop immediately.
- Play, stop, then play another item.
- Pause and resume.
- Rotate device.
- Lock device.
- Lose network.
- Lose audio focus.
- Connect Bluetooth.
- Play short text.
- Play long text.
- Repeat playback rapidly.


### Exit Criteria

- No silent stops.
- No stale callback corruption.
- No leaked player or network resources.
- UI state matches real playback state.

---

## Phase 3: Processing Status and Retry UI

### Objective

Make all processing visible and understandable.

### Required Statuses

```text
Saved locally
Processing
Transcribing
Preparing search
Building semantic index
Extracting entities
Extracting timeline
Ready
Needs attention
Failed
```


### UI Requirements

- Display status on note cards.
- Display detailed status on note detail screens.
- Add retry action.
- Add cancel action where safe.
- Add user-friendly failure messages.
- Hide technical details behind expandable UI.
- Show whether processing is local or cloud-based.
- Support empty, loading, success, and failure states.
- Preserve existing calm Material 3 visual language.


### Accessibility

Verify:

- Content descriptions.
- TalkBack traversal.
- Touch targets.
- Font scaling.
- Dark theme.
- Contrast.
- Reduced motion.
- State announcements.


### Exit Criteria

- Status survives process death.
- Retry works.
- Failure states are understandable.
- Large text does not clip.
- TalkBack can operate the workflow.

---

## Phase 4: Entity and Timeline Correction

### Objective

Make AI-inferred structure reviewable and editable.

### Entity Model

```kotlin
@Entity
data class EntityEntity(
    @PrimaryKey val id: String,
    val canonicalName: String,
    val type: EntityType,
    val createdAt: Instant,
    val updatedAt: Instant
)
```

```kotlin
@Entity(primaryKeys = ["entityId", "noteId"])
data class EntityMentionEntity(
    val entityId: String,
    val noteId: String,
    val sourceStart: Int?,
    val sourceEnd: Int?,
    val confidence: Float,
    val confirmation: ConfirmationState
)
```

```kotlin
enum class ConfirmationState {
    SUGGESTED,
    CONFIRMED,
    REJECTED,
    MERGED
}
```


### Entity Actions

- Confirm.
- Reject.
- Rename.
- Merge.
- Split.
- View source mentions.
- Remove link without deleting source note.


### Timeline Model

Each timeline entry must retain:

- Title.
- Date or date range.
- Temporal precision.
- Source note ID.
- Confidence.
- Confirmation state.
- Original extraction.

```kotlin
enum class TemporalPrecision {
    EXACT,
    DAY_RANGE,
    MONTH,
    YEAR,
    RELATIVE,
    UNKNOWN
}
```


### Rebuild Requirements

A rebuild must:

- Preserve raw notes.
- Rebuild derived data only.
- Be resumable.
- Support cancellation.
- Show progress.
- Use transactions where consistency requires it.
- Generate a final report.
- Avoid duplicate entities.
- Preserve confirmed user corrections where possible.


### Exit Criteria

- User corrections persist.
- Source notes remain unchanged.
- Rebuild survives process death.
- Rejected links no longer affect retrieval.
- Entity merges do not orphan references.
- Timeline precision is preserved.

---

## Phase 5: Retrieval Evaluation

### Objective

Make retrieval measurable, configurable, and debuggable.

### Retrieval Pipeline

```text
Query normalization
  ↓
Query classification
  ↓
BM25 candidate generation
  ↓
Vector candidate generation
  ↓
Entity and timeline filtering
  ↓
Score normalization
  ↓
Weighted score fusion
  ↓
Deduplication
  ↓
Reranking
  ↓
Context selection
```


### Retrieval Configuration

```kotlin
data class RetrievalConfig(
    val bm25Weight: Float,
    val vectorWeight: Float,
    val entityWeight: Float,
    val recencyWeight: Float,
    val topK: Int,
    val minimumScore: Float,
    val maxContextTokens: Int
)
```

Do not combine raw BM25 and cosine values without normalization.

### Query Classes

Support:

- Exact lookup.
- Semantic lookup.
- Summarization.
- Temporal query.
- Entity query.
- Comparison.
- Broad synthesis.


### Evaluation Fixture

Create test questions with expected source note IDs:

```json
{
  "question": "What oil viscosity did I use for the transmission service?",
  "expectedNoteIds": ["note-123"],
  "expectedKeywords": ["ATF", "viscosity"],
  "mustCite": true
}
```


### Metrics

Track:

- Recall@5.
- Recall@10.
- MRR.
- Citation precision.
- Unsupported claim rate.
- Refusal accuracy.
- Median retrieval latency.
- 95th-percentile retrieval latency.


### Exit Criteria

- Retrieval is reproducible.
- Weights are configurable.
- Threshold behavior is measurable.
- Exact lookups favor keyword matches.
- Paraphrases retrieve semantic matches.
- Weak results are rejected.
- “Not found” is supported.

---

## Phase 6: Citation and Grounding Enforcement

### Objective

Prevent unsupported AI answers.

### Structured Output

Require the model to produce:

```json
{
  "claims": [
    {
      "text": "The transmission service used ATF fluid.",
      "citationIds": ["citation-1"]
    }
  ],
  "uncertainty": "low"
}
```


### Citation Object

```kotlin
data class Citation(
    val id: String,
    val sourceType: SourceType,
    val sourceId: String,
    val chunkId: String,
    val quoteStart: Int?,
    val quoteEnd: Int?,
    val relevanceScore: Float
)
```


### Validation

Validate:

- Citation ID exists.
- Citation belongs to retrieved context.
- Source note exists.
- Chunk exists.
- Claim is supported by cited content.
- Citation can open the correct source.
- Unknown citations are rejected.
- Malformed model output does not crash chat.


### Refusal Behavior

Return an evidence-limited response when:

- No result exceeds the calibrated threshold.
- Sources conflict.
- The answer is not present.
- The query requests unsupported precision.
- Retrieved context does not entail the claim.

Preferred behavior:

```text
I found two notes mentioning the supplier, but neither records the final price.
```


### Exit Criteria

- Every user-data claim has a valid citation.
- Citation taps open the correct passage.
- Unsupported claims are removed or rewritten.
- Empty retrieval produces a useful refusal.
- Conflicting sources are surfaced.

---

## Phase 7: Privacy and Security

### Objective

Protect personal data throughout storage, processing, export, and deletion.

### Database Key Architecture

Use:

```text
Android Keystore key
  ↓ protects
Random SQLCipher passphrase
  ↓ opens
Encrypted Room database
```

Do not store plaintext database keys in preferences, source code, logs, assets, backups, or exported settings.

### Audit Areas

Inspect:

- SQLCipher setup.
- Android Keystore.
- Backup rules.
- Audio files.
- OCR images.
- Imported files.
- Model caches.
- TTS caches.
- HTTP caches.
- Temporary files.
- Crash reports.
- Export files.
- Cloud API requests.
- Settings files.
- CI artifacts.


### Privacy Controls

Implement or verify:

- Local-only mode.
- Explicit cloud provider settings.
- Cloud-processing indicators.
- Provider and model labels.
- Clear TTS cache.
- Delete embeddings.
- Delete derived memories.
- Delete source note and derived data.
- Encrypted export.
- Temporary-file cleanup.
- Privacy dashboard.


### Privacy Tests

- Database cannot open without the key.
- Prohibited files are excluded from backups.
- No secrets appear in logs.
- Cloud-disabled mode makes no cloud calls.
- Derived-data deletion preserves source notes.
- Source deletion removes derived data.
- Temporary files are removed after success and failure.
- Export does not leak API keys or private configuration.

---

## Phase 8: Importer Reliability

### Objective

Make document, OCR, audio, and YouTube ingestion recoverable.

### Importer Interface

```kotlin
interface ContentImporter {
    suspend fun inspect(input: ImportInput): ImportInspection
    suspend fun import(input: ImportInput): ImportResult
}
```


### Importer Types

```text
PdfImporter
DocxImporter
TextImporter
HtmlImporter
ImageOcrImporter
YoutubeTranscriptImporter
AudioImporter
```


### Typed Errors

```kotlin
sealed class ImportError(val code: String) {
    data object UnsupportedFormat : ImportError("unsupported_format")
    data object PasswordProtected : ImportError("password_protected")
    data object EmptyContent : ImportError("empty_content")
    data object TranscriptUnavailable : ImportError("transcript_unavailable")
    data object PrivateContent : ImportError("private_content")
    data object NetworkUnavailable : ImportError("network_unavailable")
    data object RateLimited : ImportError("rate_limited")
    data object ProviderUnavailable : ImportError("provider_unavailable")
    data object MalformedContent : ImportError("malformed_content")
}
```


### User-Facing Error Behavior

Every error must provide:

- Plain-language explanation.
- Appropriate next action.
- Retry where appropriate.
- Local fallback where possible.
- No infinite retry loop.

Examples:


| Error | User message | Action |
| :-- | :-- | :-- |
| Transcript unavailable | No transcript was provided for this video. | Transcribe audio |
| Private content | This video cannot be accessed. | Import local audio |
| Rate limited | The service is temporarily limiting requests. | Retry later |
| Unsupported format | This file type is not supported. | Choose another file |
| Empty content | No readable text was found. | Run OCR or select another file |


---

## Phase 9: Performance, Accessibility, and Release Testing

### Measure

- Cold startup.
- Warm startup.
- Capture-to-save latency.
- Transcription duration.
- Embedding duration.
- Search latency.
- Retrieval latency.
- Chat time to first token.
- TTS time to first audio.
- Memory usage.
- CPU usage.
- Battery impact.
- Thermal behavior.
- Database growth.
- Scroll performance.


### Suggested Private-Beta Targets

These are engineering targets and must be verified through reproducible tests:


| Metric | Target |
| :-- | --: |
| Raw note saved | Under 500 ms |
| Local text search | Under 150 ms |
| Search results visible | Under 300 ms |
| Chat retrieval | Under 1.5 seconds where device/model permits |
| TTS first audio | Under 3 seconds with network available |
| Note-list scrolling | No visible jank |
| Failed job recovery | One-tap retry |
| Crash-free sessions | At least 99.5 percent during beta |

### Tests

- Unit tests.
- DAO tests.
- Migration tests.
- Repository tests.
- WorkManager tests.
- Integration tests.
- Compose UI tests.
- Macrobenchmarks.
- Accessibility tests.
- Offline tests.
- Process-death tests.
- Low-memory tests.
- Battery and thermal tests.
- Adversarial RAG tests.

---

## Phase 10: Advanced Features

Only begin this phase after the previous phases pass their release gates.

Implement gradually:

- Proactive recall.
- Web enrichment.
- Multi-hop reasoning.
- Concept memory graph.
- Voice command service.
- Background suggestions.
- Idea evolution analysis.


### Rules

- Every feature remains behind a feature flag.
- Suggestions must explain why they appeared.
- Users can dismiss suggestions.
- Users can mute or disable suggestions.
- No active workflow may be interrupted.
- Cloud use must respect privacy settings.
- Suggestion quality must be measured.
- False-positive rate must be tracked.
- Do not equate generated suggestions with useful suggestions.

---

# 8. Debugging Protocol

## General Debugging Rules

When a bug is reported:

1. Reproduce it.
2. Record exact steps.
3. Record device, Android version, build variant, and model/provider.
4. Inspect logs without exposing private content.
5. Identify the first incorrect state transition.
6. Determine whether the issue is:
    - UI state
    - lifecycle
    - coroutine
    - database
    - worker
    - network
    - model
    - parsing
    - retrieval
    - citation
    - permissions
    - encryption
7. Add a regression test before or alongside the fix.
8. Implement the smallest safe fix.
9. Re-run the failing test.
10. Re-run related regression tests.
11. Review the diff.
12. Report root cause and prevention.

## Debugging Output Format

```text
Bug:
Environment:
Reproduction steps:

Expected behavior:
Actual behavior:

First incorrect state:
Root cause:
Contributing factors:

Files involved:
Fix implemented:
Regression test:

Tests run:
Build result:

Remaining risks:
```


## Do Not Debug by Guessing

The agent must not:

- Randomly change coroutine scopes.
- Add arbitrary delays.
- Increase timeouts without identifying the cause.
- Suppress exceptions without recording state.
- Retry permanently failed operations forever.
- Disable encryption to “test quickly” without explicit approval.
- Hide crashes with broad `try/catch`.
- Remove validation to make tests pass.
- Modify unrelated code to silence compilation errors.

---

# 9. Specific Debugging Playbooks

## 9.1 Note Disappears After Capture

Check:

1. Was the audio file closed?
2. Was the note inserted before processing?
3. Did the database transaction commit?
4. Did navigation occur before the insert completed?
5. Did a worker delete or replace the note?
6. Did Room migration fail?
7. Did process death occur?
8. Did the UI query filter by an incorrect status?

Required fix:

- Persist the raw note first.
- Add a regression test.
- Verify force-close behavior.
- Verify process restart behavior.


## 9.2 Worker Runs Twice

Check:

1. Unique Work name.
2. Existing work policy.
3. Job status transition.
4. Retry behavior.
5. Worker idempotency.
6. Process death during commit.
7. Duplicate job insertion.

Required fix:

- Add unique constraints.
- Use a transaction.
- Make stage writes idempotent.
- Add duplicate scheduling test.


## 9.3 TTS Stops Silently

Check:

1. Is time-based truncation still active?
2. Is the network request cancelled prematurely?
3. Is the media source closed?
4. Is a stale callback changing state?
5. Is audio focus lost?
6. Is the player released during lifecycle change?
7. Is the response body closed too early?

Required fix:

- Use playback-state callbacks.
- Use generation tokens.
- Add repeated play-stop-play tests.
- Verify resource cleanup.


## 9.4 Chat Has Wrong Citations

Check:

1. Was the citation generated by the model?
2. Was the citation ID created by the application?
3. Was the cited chunk retrieved?
4. Was context deduplicated incorrectly?
5. Was the chunk ID changed during reranking?
6. Did the model output malformed JSON?
7. Did a retry reuse stale citation IDs?

Required fix:

- Application owns citation IDs.
- Validate every citation.
- Reject unknown references.
- Add claim-level citation tests.


## 9.5 Retrieval Returns Irrelevant Notes

Check:

1. Query classification.
2. BM25 normalization.
3. Vector normalization.
4. Score weights.
5. Chunk size.
6. Language mismatch.
7. Embedding model consistency.
8. Threshold calibration.
9. Duplicate chunks.
10. Entity or date filtering.

Required fix:

- Add the query to the evaluation dataset.
- Measure Recall@5 and Recall@10.
- Adjust configuration, not hard-coded logic.
- Re-run the full retrieval evaluation.


## 9.6 Entity Is Incorrect

Check:

1. Original extraction.
2. Chunk boundaries.
3. Entity normalization.
4. Duplicate detection.
5. Alias handling.
6. Language and spelling variation.
7. User confirmation state.
8. Merge logic.

Required fix:

- Preserve original extraction.
- Add a correction record.
- Do not overwrite source text.
- Add a regression fixture.


## 9.7 Database Cannot Open

Check:

1. Keystore availability.
2. Key alias.
3. Key invalidation.
4. Passphrase encoding.
5. SQLCipher version.
6. Migration order.
7. Backup and restore path.
8. Device lock or biometric state.

Required fix:

- Never silently create a new empty database.
- Provide a safe recovery path.
- Report data-risk status immediately.
- Add migration and key-handling tests.

---

# 10. Agent Commands

## Initial Inspection

```text
Inspect the repository only. Do not modify files.

Return:
- project structure
- modules
- build configuration
- SDK versions
- Kotlin and Compose versions
- Room and SQLCipher setup
- WorkManager setup
- relevant source files
- existing tests
- current build result
- proposed Phase 0 plan
```


## Phase Approval

```text
Proceed with Phase 0 only.

Modify only files necessary for Phase 0.
Compile the project.
Run relevant tests.
Do not begin Phase 1.
Return the required phase completion report.
```


## Capture Implementation

```text
Proceed with Phase 1 only.

Inspect the existing capture, Room, DAO, repository, and WorkManager code first.
Implement durable raw capture and retryable processing jobs.
Preserve existing behavior.
Add migrations and tests where required.
Compile and run targeted and regression tests.
Do not begin Phase 2.
```


## TTS Implementation

```text
Proceed with Phase 2 only.

Inspect the existing TTS managers, streaming source, player lifecycle, cancellation logic, and audio focus handling.
Replace time-based cancellation with playback-state-driven behavior.
Add lifecycle and repeated-playback tests.
Compile and run all relevant tests.
Do not modify unrelated retrieval or database code.
```


## Code Review

```text
Review the current diff as a senior Android engineer.

Check:
- data loss
- main-thread work
- coroutine leaks
- lifecycle leaks
- duplicate WorkManager jobs
- stale Compose state
- SQLCipher risks
- sensitive logs
- missing migrations
- missing tests
- unsupported AI claims
- accessibility regressions

Do not modify code.
Return findings ranked Critical, High, Medium, and Low.
```


## Regression Fix

```text
The previous implementation introduced a regression.

Do not add new features.
Reproduce the issue.
Identify the root cause.
Add a regression test.
Make the smallest safe fix.
Run the failing test and related regression tests.
Review the final diff.
Return the debugging report.
```


## Performance Audit

```text
Perform a performance audit without changing production behavior.

Measure or identify how to measure:
- startup
- note-list scrolling
- capture save latency
- search latency
- retrieval latency
- TTS first audio
- transcription cost
- embedding cost
- memory
- battery
- thermal behavior

Do not claim a target has been achieved without a reproducible measurement.
Return bottlenecks, evidence, and recommended changes.
```


## Security Audit

```text
Perform a read-only privacy and security audit.

Inspect:
- SQLCipher key management
- Android Keystore
- backups
- API keys
- logs
- temporary files
- caches
- audio files
- OCR files
- exports
- cloud-provider boundaries
- deletion behavior

Do not modify code.
Report Critical, High, Medium, and Low findings with file paths and remediation steps.
```


## Retrieval Evaluation

```text
Run a retrieval evaluation using the existing test fixtures.

Measure:
- Recall@5
- Recall@10
- MRR
- citation precision
- unsupported claim rate
- refusal accuracy
- latency

Do not change retrieval weights during the first run.
Return failures with the query, expected source, actual source, and suspected cause.
```


---

# 11. Change Budget

Unless explicitly approved:

- Modify no more than 10 production files per task.
- Add no more than 5 dependencies per milestone.
- Do not alter database schema without migration tests.
- Do not add public APIs without tests or documented usage.
- Do not perform unrelated refactors.
- Do not rename project-wide symbols unnecessarily.
- Do not delete code unless a tested replacement exists.
- Do not change product behavior silently.
- Do not begin a new phase while the current phase is failing.

If the change requires exceeding the budget, stop and explain why.

---

# 12. Definition of Done

A task is complete only when:

- The intended behavior is implemented.
- Existing behavior is preserved.
- The project compiles.
- Relevant tests pass.
- Regression tests exist for the bug or feature.
- No sensitive data is logged.
- No data-loss path is known.
- Lifecycle behavior is considered.
- Cancellation behavior is considered.
- Failure behavior is user-visible.
- The diff has been reviewed.
- Remaining risks are documented.

A phase is not complete merely because the code compiles.

---

# 13. Private-Beta Definition of Done

NoteFlowAI is ready for serious private-beta testing when a tester can:

1. Record a note without network access.
2. See the note saved immediately.
3. Force-close and reopen the app without losing it.
4. Resume processing after interruption.
5. See processing status.
6. Retry failed processing.
7. Search exact text.
8. Search by meaning.
9. Ask a question over notes.
10. Open the supporting citation.
11. See when evidence is insufficient.
12. Correct an entity.
13. Edit or reject a timeline item.
14. Stop and restart TTS safely.
15. Import unsupported or problematic content and receive a useful explanation.
16. Understand whether local or cloud AI was used.
17. Delete a note and its derived data.
18. Delete derived data without deleting source notes.
19. Use the core workflow with large fonts and TalkBack.
20. Use the app without obvious scrolling, memory, or battery problems.

---

# 14. Agent Completion Checklist

Before reporting a milestone complete, confirm:

## Repository

- [ ] Existing architecture was inspected.
- [ ] No unnecessary rewrite occurred.
- [ ] No unrelated files changed.
- [ ] Build configuration remains valid.


## Data

- [ ] Raw source is preserved.
- [ ] Derived data is rebuildable.
- [ ] Migrations exist.
- [ ] Transactions are used where required.
- [ ] Deletion behavior is tested.


## Async Processing

- [ ] Workers are idempotent.
- [ ] Retry policy is appropriate.
- [ ] Cancellation is handled.
- [ ] Process death is handled.
- [ ] Status is persisted.


## AI

- [ ] Model output is validated.
- [ ] Malformed output does not crash the app.
- [ ] Unsupported claims are rejected.
- [ ] Citations are application-validated.
- [ ] “Insufficient evidence” is supported.


## Security

- [ ] No secrets are logged.
- [ ] Keys are protected.
- [ ] Backups are audited.
- [ ] Temporary files are cleaned.
- [ ] Cloud boundaries are visible.
- [ ] Deletion is complete and predictable.


## UX

- [ ] Loading state exists.
- [ ] Empty state exists.
- [ ] Failure state exists.
- [ ] Retry exists where appropriate.
- [ ] Accessibility is tested.
- [ ] Large text is supported.


## Testing

- [ ] Unit tests pass.
- [ ] Integration tests pass.
- [ ] UI tests pass.
- [ ] Regression tests exist.
- [ ] Offline behavior is tested.
- [ ] Process death is tested.
- [ ] Failure paths are tested.

---

# 15. First Agent Instruction

Start with Phase 0 only.

Do not modify code yet.

Inspect the repository and return:

1. Project structure.
2. Build configuration summary.
3. Current relevant classes and files.
4. Existing tests.
5. Existing implementation versus this specification.
6. Proposed Phase 0 file-level plan.
7. Commands that will be run.
8. Risks and ambiguities requiring confirmation.

Wait for approval before editing.

```

## Recommended use

Give the agent the document above, then use this first instruction:

```text
Read NoteFlowAI-Agentic-Implementation-Guide.md.

Perform the First Agent Instruction only.
Inspect the repository.
Do not modify any files.
Return the Phase 0 inspection report.
```

After reviewing its report:

```text
Approved. Proceed with Phase 0 only. Follow the change budget, compile the project, run relevant tests, and return the Phase Completion Report. Do not begin Phase 1.
```

This staged approach is important because NoteFlowAI combines encrypted local storage, background processing, native AI inference, streaming audio, hybrid retrieval, and citation-grounded responses; allowing an agent to modify all of those areas in one autonomous pass would make regressions and data-loss risks difficult to isolate. [^1]

<div align="center">⁂</div>

[^1]: NoteFlowAI-App-Profile.md

