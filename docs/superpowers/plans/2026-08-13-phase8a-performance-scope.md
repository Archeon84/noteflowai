# Scope: Phase 8a — Performance Optimization

## Context

Phase 8 of the Personal Memory Layer master plan covers four areas; the user selected
**performance optimization** as the first target. This scope doc covers the concrete,
behavior-preserving optimizations identified by reading the hot paths. Everything here
keeps the public API and search semantics identical.

## Findings (verified in code)

Three families of waste:

1. **Repeated `Regex` compilation in hot tokenizers.** `NoteSearchIndex.tokenize()`,
   `SegmentIndexService.tokenize()`, `OnDeviceEmbedder.tokenize()`,
   `SmartSnippetExtractor`, `ConflictDetector`, `CitationValidator`, and
   `NoteSuggestionEngine` construct `Regex("[^a-z0-9\\s]")` / `Regex("\\s+")` on every
   call. Tokenize runs 5x per note on every index rebuild and once per query on every
   search, so the pattern gets recompiled thousands of times. Hoisting to `companion
   object` vals removes the repeated compilation.

2. **`SegmentIndexService.search()` recomputes corpus statistics on every query.**
   Lines 118-126 recompute `docFreq`, `docCount`, and `avgLength` from the full entry
   list, and line 131 recomputes each entry's term-frequency map
   (`groupingBy { it }.eachCount()`) — a full O(N·tokens) corpus pass per search. The
   class doc even flags this as a known cost. These are pure functions of the indexed
   entries, so they should be cached at mutation time (indexSource/removeSource/load)
   and read at search time. Search becomes O(N·queryTokens) with zero corpus pass.

3. **`HybridRetriever` N+1 queries and loop-allocated dedup sets.**
   `retrieveDecisions`/`retrieveCommitments`/`retrieveProjects`/`retrieveChangeAnalysis`
   call `sourceSegmentRepository.getById()` once per object (a Room query per row), and
   the dedup expressions `results.map { it.sourceSegmentId }` / `results.mapNotNull { it.memoryObjectId }`
   rebuild a list on every loop iteration (quadratic). Both should be batched/hoisted.

## Changes

### C1. Hoist tokenizer regexes (behavior-identical)
- `NoteSearchIndex`: hoist `NON_ALNUM` and `WHITESPACE` `Regex` to companion vals.
- `SegmentIndexService`: same.
- `OnDeviceEmbedder`: same (its tokenizer uses `[^a-z0-9\s]` and `\s+`).
- `SmartSnippetExtractor`, `ConflictDetector`, `CitationValidator`,
  `NoteSuggestionEngine`: hoist the tokenizer regexes used in the hot search/snippet/
  validator paths.

### C2. Cache BM25 corpus stats in SegmentIndexService (identical scoring)
- Precompute each entry's `tf: Map<String, Int>` at index time and store it in `Entry`
  (fall back to computing on load for pre-existing persisted files).
- Maintain `docFreq`, `totalDocs`, `avgLength` as cached fields, recomputed on
  `indexSource`, `removeSource`, and `ensureLoaded` (mutation is rare; search is hot).
- `search()` reads the cached fields; remove the per-search corpus pass.

### C3. HybridRetriever batch + hoist
- Add `SourceSegmentDao.getByIds(ids: List<String>)` (`WHERE id IN (:ids)`) and a
  repository passthrough `getByIds`.
- In `retrieveDecisions`, `retrieveCommitments`, `retrieveProjects`,
  `retrieveChangeAnalysis`, `retrieveSourceSummary`: batch the segment lookups into one
  query and resolve by id from a map.
- Hoist dedup `Set<String>` construction out of the loops in `retrieveChangeAnalysis`
  and `retrieveGeneral`.

### C4. OnDeviceEmbedder single-segment batching (deferred)
- The embed path is dominated by ONNX inference cost; batching would require dynamic
  batching support in the ONNX session. Deferred out of this scope unless trivial.

## Out of scope
- Embedding batching (C4).
- MainViewModel startup profiling (separate workstream; the pipeline recovery block is
  already off the main thread).
- Offline reliability / encryption / eval dashboard (other Phase 8 areas).

## Files to modify
| File | Change |
|------|--------|
| `app/.../data/search/NoteSearchIndex.kt` | Hoist regexes |
| `app/.../data/memory/pipeline/SegmentIndexService.kt` | Hoist regexes + cached stats |
| `app/.../data/search/OnDeviceEmbedder.kt` | Hoist tokenizer regexes |
| `app/.../data/search/SmartSnippetExtractor.kt` | Hoist regexes |
| `app/.../data/search/ConflictDetector.kt` | Hoist regexes |
| `app/.../data/chat/CitationValidator.kt` | Hoist regexes |
| `app/.../data/suggestions/NoteSuggestionEngine.kt` | Hoist regexes |
| `app/.../data/memory/dao/SourceSegmentDao.kt` | Add `getByIds` |
| `app/.../data/memory/repository/SourceSegmentRepository.kt` | Add `getByIds` passthrough |
| `app/.../data/search/HybridRetriever.kt` | Batch segment lookup + hoist dedup sets |
| `app/src/test/.../pipeline/SegmentIndexServiceTest.kt` | Add tests for cached stats (identical results) |
| `app/src/test/.../search/HybridRetrieverTest.kt` | New: batch lookup path |

## Verification
1. `gradlew :app:testDebugUnitTest` — all existing + new tests green.
2. `gradlew :app:assembleDebug` — compiles.
3. Search results unchanged: existing `NoteSearchIndexTest` and `SegmentIndexServiceTest`
   assertions cover identical scoring.
