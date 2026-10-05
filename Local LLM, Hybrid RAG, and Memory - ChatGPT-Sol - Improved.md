

# Improved Implementation Specification

````markdown
# Android Notes AI Implementation Plan

## Local LLM, Hybrid RAG, and Memory

## Purpose

Improve the existing Android notes app by adding or optimizing:

- Local LLM inference.
- Hybrid note retrieval.
- Compact memory.
- Streaming answers.
- Snapdragon and ARM64 CPU performance.
- Measurable offline behavior.

The implementation must improve the existing application rather than rebuild it. Existing note creation, editing, viewing, search, authentication, synchronization, and other core features must remain functional unless the approved phase explicitly requires a change.

---

## Operating Rules

### Scope

The agent may modify only files and behaviors required by the approved phase.

The agent must:

- Prefer additive and reversible changes.
- Preserve existing public APIs where practical.
- Preserve existing user-visible behavior.
- Avoid unrelated cleanup refactors.
- Avoid replacing libraries without approval.
- Avoid changing database schemas without approval.
- Avoid changing authentication or synchronization behavior without approval.
- Keep configuration values centralized and documented.

Before editing, the agent must report:

1. The approved phase.
2. Files expected to change.
3. Behaviors expected to change.
4. New dependencies or build requirements.
5. Risks and rollback strategy.

After editing, the agent must report:

1. Files changed.
2. Tests and builds executed.
3. Results.
4. Known limitations.
5. Any changes that exceeded the original scope.

### Approval Levels

Minor changes may proceed within the approved phase.

The agent must request approval before:

- Replacing the LLM runtime.
- Replacing the database or vector store.
- Changing the storage schema.
- Adding a new major architectural layer.
- Changing authentication.
- Changing synchronization.
- Redesigning core screens.
- Changing data migration behavior.
- Introducing a model that substantially increases application size.
- Changing retrieval semantics across the entire application.

If unsure, treat the change as major and request approval.

---

## Phase 0: Baseline and Safety

### Goals

Establish a reproducible baseline before optimization.

### Tasks

Record:

- Device manufacturer and model.
- Snapdragon chipset and CPU features.
- Android version.
- App version.
- llama.cpp commit.
- Android NDK version.
- CMake version.
- Model filename and SHA-256 checksum.
- Model quantization.
- Available RAM.
- Battery level and charging state.
- Temperature before testing.

Create a benchmark set of 20–50 representative queries, including:

- Exact note lookup.
- Semantic note search.
- Multi-note questions.
- Date-based questions.
- Numeric questions.
- Short queries.
- Long queries.
- Queries with no answer in the notes.
- Follow-up questions.

Measure:

- Model load time.
- App startup time.
- Retrieval latency.
- Prompt construction time.
- Prompt evaluation time.
- Time to first token.
- Generation tokens per second.
- Total response time.
- Peak native memory.
- Peak application memory.
- Temperature change.
- Cancellation response time.
- Error rate.

Run at least three warm-up requests and five measured requests per test. Report median and p95 values.

### Deliverables

- Baseline benchmark report.
- Device capability report.
- Existing behavior checklist.
- Reproducible benchmark command or test procedure.

---

## Phase 1: Model Configuration

### Goals

Select a model and quantization based on measured device constraints.

### Requirements

Do not hard-code an unverified model name. Define the model in configuration:

```text
MODEL_ID=
MODEL_REVISION=
MODEL_FILENAME=
MODEL_QUANTIZATION=Q4_K_M
MODEL_SHA256=
MODEL_CONTEXT_LENGTH=
MODEL_LICENSE=
````


### Quantization Test Order

Test:

1. Q4_K_M for the default profile.
2. Q4_K_S for lower memory use.
3. Q5_K_M when answer quality requires it.

Select the default using measured results rather than file size alone.

The model evaluation must consider:

- Retrieval-answer accuracy.
- Instruction following.
- Hallucination rate.
- Time to first token.
- Tokens per second.
- Peak memory.
- Thermal stability.
- Application package and download size.


### Model Storage

The model manager must support:

- First-run model download.
- Resumable download.
- Temporary download file.
- SHA-256 verification.
- Atomic rename after verification.
- Delete and redownload.
- Sufficient-storage checks.
- User-visible download progress.
- Clear error messages.

______________________________________________________________________

## Phase 2: Native Runtime and Android Integration

### Goals

Create a stable boundary between the Android app and llama.cpp.

### Recommended Flow

```text
Flutter or Android UI
        ↓
Application AI service
        ↓
Kotlin inference manager
        ↓
JNI bridge
        ↓
llama.cpp native runtime
```


### Inference Manager Responsibilities

The inference manager must support:

- Load model.
- Unload model.
- Start inference.
- Stream generated tokens.
- Cancel inference.
- Return structured errors.
- Report progress.
- Prevent unsafe concurrent model access.
- Release native resources.
- Recover from failed model loading.
- Respect Android lifecycle events.


### Request Contract

Use a request object similar to:

```json
{
  "request_id": "unique-id",
  "query": "User question",
  "system_prompt": "Short instruction",
  "memory_context": [],
  "retrieved_context": [],
  "max_output_tokens": 128,
  "temperature": 0.2,
  "top_p": 0.9,
  "stop_sequences": []
}
```

Return events such as:

```json
{
  "request_id": "unique-id",
  "type": "token",
  "text": "Generated text"
}
```

Final events should include:

```json
{
  "request_id": "unique-id",
  "type": "complete",
  "input_tokens": 520,
  "output_tokens": 86,
  "time_to_first_token_ms": 740,
  "generation_tokens_per_second": 18.4
}
```


______________________________________________________________________

## Phase 3: ARM64 CPU Build

### Goals

Provide a reliable ARM64 baseline before optional optimization.

### Baseline Requirements

- Build `arm64-v8a`.
- Use a Release build.
- Disable unnecessary examples, tests, tools, and network features.
- Use the Android NDK toolchain.
- Preserve a general ARM64 NEON-compatible path.
- Record the exact llama.cpp revision.
- Build native libraries reproducibly.

Example baseline direction:

```bash
cmake -S . -B build-android \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-28 \
  -DCMAKE_BUILD_TYPE=Release \
  -DGGML_OPENMP=ON \
  -DLLAMA_CURL=OFF \
  -DLLAMA_BUILD_TESTS=OFF \
  -DLLAMA_BUILD_TOOLS=OFF
```

Do not add optional backend flags unless they are confirmed to exist in the selected llama.cpp revision and are supported by the target device.

### Optional Optimizations

Evaluate optional CPU optimizations separately:

- ARM NEON.
- OpenMP.
- KleidiAI, if supported by the selected revision.
- Qualcomm-specific acceleration, if supported by the selected runtime and device.
- Other architecture-specific kernels.

Each optimization must have:

- A compile-time detection check.
- A runtime capability check where required.
- A fallback path.
- A benchmark comparison.
- A stability test.

Never make an optional optimization the only build path.

______________________________________________________________________

## Phase 4: Runtime Tuning

### Goals

Reduce latency without causing memory instability or thermal throttling.

### Initial Parameters

Begin with:

```text
Context size: 2,048 tokens
Output limit: 128 tokens
Batch size: 64–128
Micro-batch size: 32–64
Temperature: 0.2
Top-p: 0.9
```

Benchmark thread counts:

```text
1, 2, 3, 4, 6, and device-specific performance-core count
```

Select the fastest stable configuration, not necessarily the highest thread count.

### Prompt Budget

Use a token budget rather than fixed character limits:

```text
System instructions: 150–250 tokens
Memory: up to 150 tokens
Retrieved context: 600–1,000 tokens
Conversation history: remaining budget
User query: always preserved
Output reservation: 128–256 tokens
```

The prompt builder must truncate in this order:

1. Remove low-priority conversation history.
2. Reduce low-scoring retrieved chunks.
3. Remove redundant memory.
4. Preserve the current user query.
5. Preserve required system instructions.

### Cancellation

The UI must allow the user to stop generation. Cancellation should:

- Signal the native runtime.
- Stop token streaming.
- Release or reset the active generation state.
- Leave the app ready for a new request.
- Record cancellation separately from failure.

______________________________________________________________________

## Phase 5: Hybrid Retrieval

### Goals

Improve retrieval accuracy while keeping mobile latency low.

### Retrieval Pipeline

```text
Query normalization
        ↓
Metadata filtering
        ↓
Lexical retrieval
        ↓
Vector retrieval
        ↓
Score fusion
        ↓
Deduplication
        ↓
Optional reranking
        ↓
Prompt construction
```


### Default Behavior

- Use top-3 chunks for narrow queries.
- Use top-5 only for broad or multi-part queries.
- Filter by notebook, project, date, tags, or note ownership before retrieval.
- Remove near-duplicate chunks.
- Prefer newer content when scores are similar.
- Preserve note title and source identifiers.
- Keep source attribution available to the UI.


### Chunking

Chunk notes by semantic boundaries where practical:

- Heading.
- Paragraph.
- List.
- Table.
- Code block.
- Transcript segment.

Store:

```text
note_id
chunk_id
title
text
created_at
updated_at
tags
project_id
token_count
embedding_model
embedding_version
```

Do not silently re-embed all notes when the embedding model changes. Track embedding versions and reindex deliberately.

### Reranking

Reranking is optional. Use it only when:

- The query is broad.
- Candidate scores are close.
- The quality benefit is measurable.
- Added latency is acceptable.

______________________________________________________________________

## Phase 6: RAG Answering

### Goals

Generate concise answers grounded in retrieved notes.

### Prompt Rules

The model must:

- Answer using the supplied note context.
- Distinguish known information from inference.
- Say when the notes do not contain an answer.
- Avoid inventing note contents.
- Preserve dates, quantities, and names accurately.
- Avoid mentioning internal retrieval mechanics unless requested.
- Cite note titles or identifiers when the UI supports citations.


### Recommended Prompt Structure

```text
System instruction

Memory summary, if relevant

Retrieved notes:
[Source: note title, note identifier, date]
Note content

User question

Answer briefly and state uncertainty when necessary.
```


### No-Result Behavior

If retrieval confidence is low:

- Do not force an answer.
- Ask whether the user wants a broader search.
- Offer a normal assistant response only if clearly labeled as outside the notes.
- Distinguish “not found in notes” from “the fact is false.”

______________________________________________________________________

## Phase 7: Memory Layer

### Goals

Keep useful long-term context without injecting raw chat history into every request.

### Memory Types

- User preference.
- Stable personal fact.
- Project fact.
- Ongoing task.
- Workflow preference.
- Temporary context.


### Memory Record

```json
{
  "id": "memory-id",
  "scope": "user-or-project",
  "category": "preference",
  "content": "Short memory statement",
  "source_note_ids": ["note-id"],
  "confidence": 0.85,
  "user_confirmed": false,
  "created_at": "timestamp",
  "updated_at": "timestamp",
  "expires_at": null
}
```


### Memory Rules

- Do not store sensitive information automatically without user permission.
- Do not store every conversation.
- Store short factual statements rather than raw transcripts.
- Record source notes when available.
- Allow the user to inspect, edit, disable, and delete memories.
- Support expiration for temporary memories.
- Avoid injecting unrelated memories into a prompt.
- Update memory asynchronously after the answer.
- Never allow memory to override explicit current user instructions.


### Memory Retrieval

Retrieve memories using:

- Scope.
- Relevance.
- Recency.
- Confidence.
- User confirmation.
- Expiration status.

Use a small token budget, normally no more than 100–150 tokens.

______________________________________________________________________

## Phase 8: Fast and Slow Paths

### Fast Path

Used for normal user interaction:

1. Normalize query.
2. Retrieve relevant notes.
3. Retrieve a small memory summary.
4. Build a bounded prompt.
5. Stream a concise answer.
6. Show sources where available.

### Slow Path

Run asynchronously:

- Memory summarization.
- Note reindexing.
- Embedding generation.
- Duplicate detection.
- Note summarization.
- Retrieval evaluation.
- Cache maintenance.

The slow path must never block the primary note-editing or answer-generation UI.

______________________________________________________________________

## Phase 9: Security and Privacy

### Requirements

- Keep local models and embeddings in protected app storage.
- Do not log note content by default.
- Redact sensitive text from diagnostic logs.
- Make network use explicit.
- Verify downloaded model files.
- Provide a setting to disable telemetry.
- Avoid sending local notes to external services unless the user enables it.
- Clear temporary files after failed downloads.
- Protect exported benchmark data if it contains note content.

______________________________________________________________________

## Phase 10: Testing

### Unit Tests

Test:

- Prompt budgeting.
- Token truncation.
- Query routing.
- Score fusion.
- Deduplication.
- Metadata filtering.
- Memory expiration.
- Model configuration validation.
- SHA-256 verification.
- Request cancellation.


### Integration Tests

Test:

- Model loading.
- Streaming output.
- Failed model loading.
- App backgrounding.
- App restart.
- Concurrent requests.
- Low-memory conditions.
- Note creation followed by indexing.
- Note update followed by reindexing.
- Deleted note removal from retrieval.


### Regression Tests

Verify that existing features still work:

- Create note.
- Edit note.
- Delete note.
- Search note.
- View note.
- Login and logout, if present.
- Synchronization, if present.
- Export and import, if present.
- Existing navigation flows.

______________________________________________________________________

## Phase 11: Success Criteria

### Performance

Define device-specific targets before optimization.

Track:

- Model load time.
- Median time to first token.
- p95 time to first token.
- Generation tokens per second.
- Retrieval latency.
- Total response latency.
- Peak memory.
- Temperature change.
- Cancellation latency.


### Quality

Track:

- Recall@3.
- Recall@5.
- Mean reciprocal rank.
- Grounded-answer rate.
- Unsupported-answer rate.
- No-result accuracy.
- Numeric and date accuracy.
- User correction rate.


### Stability

The phase is successful only if:

- No critical existing feature regresses.
- The app remains responsive during inference.
- Cancellation works.
- Model-loading failures are recoverable.
- Optional CPU optimizations have a fallback.
- No unrelated files are changed.
- No major architectural change was made without approval.

______________________________________________________________________

## Final Agent Instruction

Implement only the approved phase.

Before editing, inspect the existing project and list the exact files that will change. Preserve existing features and public behavior. Prefer minimal, reversible changes. Do not assume that a model, CMake flag, Qualcomm backend, CPU instruction, or llama.cpp option is available without verifying it in the actual project and target device.

If a required change is major, stop and request approval.

After implementation, run targeted tests and report:

- Files changed.
- Build commands used.
- Tests completed.
- Benchmark results.
- Regressions.
- Remaining risks.
- Rollback instructions.

```

## Recommended Phase Order

Your original plan contains ten phases, but the safest implementation order is:

1. **Baseline and device capability detection.**
2. **Native llama.cpp integration with a simple single-query path.**
3. **Model loading, verification, cancellation, and streaming.**
4. **Runtime benchmarking and thread tuning.**
5. **Hybrid retrieval without reranking.**
6. **Prompt budgeting and grounded answers.**
7. **Memory layer with user controls.**
8. **Optional reranking and architecture optimizations.**
9. **Thermal, battery, and low-memory testing.**
10. **Release hardening and regression testing.**

This order avoids optimizing a retrieval or memory architecture before the native runtime, model configuration, and measurement system are proven. The attached file’s original scope-control rules are worth retaining, but the revised version makes them more enforceable by adding explicit contracts, validation, privacy controls, fallback behavior, and acceptance tests. [^1]```


<div align="center">⁂</div>

[^1]: export_2394944010544335613.md```

