# NoteFlowAI Retrieval Evaluation Design (Phase 5)

## Overview

Phase 5 makes retrieval measurable, configurable, and debuggable per the Agentic
Implementation Guide (lines 940-1039). Three architectural changes land together:

1. **A configurable retrieval pipeline** backed by a `RetrievalConfig` (weights,
   top-K, threshold, context budget) persisted in DataStore.
2. **A hard pre-fusion "Filter & Eval Gate"** stage (user-directed scope addition)
   that extracts metadata filters from the query plan and prunes the candidate set
   before any score arithmetic — date range, source type, rejected-entity links,
   and required-timeline constraints.
3. **A deterministic evaluation harness** (`RetrievalEvaluator`) shared between a
   JVM/Robolectric CI gate and a debug-only on-device latency run, driven by a
   single golden fixture file.

The existing reciprocal-rank fusion is replaced by score normalization + weighted
fusion (RRF survives only as a candidate-union helper, per the chosen Option C),
and the hardcoded `PromptAssembler` context cap yields to `RetrievalConfig.maxContextTokens`.

## Current State (verified)

- `HybridRetriever.retrieveGeneral()` (data/search/HybridRetriever.kt:431): BM25 search
  (maxResults=30) -> embedding search (on-device embedder, remote fallback, maxResults=30)
  -> `reciprocalRankFusion` (rank-only RRF; constants RRF_K=60, RRF_MERGED_MAX=40)
  -> segment search (fixed score 0.5) -> segment-embedding search (raw cosine score).
  Results capped at FILTERED_MAX=20. No score normalization, no weights, no threshold.
- `reciprocalRankFusion` (line 568): scores = sum of 1/(RRF_K + rank + 1).
- `NoteSearchIndex.search` returns raw unbounded BM25 scores; `EmbeddingIndex.search`
  returns cosine in [0,1]; `segmentEmbeddingService.search` returns raw cosine.
- `QueryParser.parse()` produces `QueryPlan` with intent, dateFrom/dateTo, entities,
  sourceTypes, memoryTypes, requiresTimeline, requiresComparison. Covers the 7 query classes.
- `PromptAssembler.assemble()` (data/search/PromptAssembler.kt:19) budgets
  `MAX_CONTEXT_TOKENS_CHARS = 8000` hardcoded.
- Only `retrieve()` call site: `MainViewModel.kt:3290` inside the chat RAG path.
- Rejected-entity gating exists per-intent (`retrieveEntities`/`retrieveProjects` via
  `EntityRepository.searchActive`/`getActiveMentions`) but is NOT applied on the general path.
- Phase 4 seams ready for tests: `EntityRepository(db)`, `TimelineRepository(db)`,
  `SourceSegmentRepository(db)` in-memory constructors; `HybridRetrieverRejectedGatingTest`
  demonstrates the Robolectric + MockK + in-memory DB pattern.

## Architecture

### RetrievalConfig (new file: data/search/RetrievalConfig.kt)

```kotlin
data class RetrievalConfig(
    val bm25Weight: Float = 0.4f,
    val vectorWeight: Float = 0.4f,
    val entityWeight: Float = 0.12f,
    val recencyWeight: Float = 0.08f,
    val topK: Int = 40,             // candidate cap pre-prune
    val minimumScore: Float = 0.28f, // post-fusion rejection gate
    val maxContextTokens: Int = 1000 // chars; replaces PromptAssembler's 8000 cap
)
```

Weights sum to 1.0 so `fused` stays in [0,1]. Persisted via DataStore in
`SettingsManager` (new `readRetrievalConfig()` following the existing
`readChatConfig()` pattern), satisfying "weights configurable".

### New HybridRetriever pipeline (restructured retrieveGeneral)

```
1. Query classification                (existing: QueryParser.parse -> QueryPlan)
2. Query embedding                     (existing: onDeviceEmbedder, remote fallback)
3. Candidate generation                (existing: BM25 + EmbeddingIndex + segment searchAll + segment embeddings)
4. Filter & Eval Gate                  (NEW, hard pre-fusion prune)
   a. extract metadata filters from QueryPlan
   b. prune candidates by date / sourceType / rejected-entity / required-timeline
   c. record RetrievalTrace for the evaluator
5. Score normalization                 (NEW: min-max per candidate source -> [0,1])
6. Weighted fusion                     (NEW: preserve RRF rank as candidate-union helper, then weighted sum)
7. Threshold rejection                 (NEW: fused < minimumScore -> drop)
8. Dedup + rerank                      (existing dedup by sourceSegmentId; rerank by fused score)
9. Context selection                   (NEW: cap by maxContextTokens)
```

Scores are min-max normalized per candidate source (BM25, cosine, segment, segment-
embedding) into [0,1]; fused = bm25Weight*`n_bm25` + vectorWeight*`n_cos` +
entityWeight*entityBoost + recencyWeight*recencyBoost. This satisfies "do not combine
raw BM25 and cosine values without normalization".

The old `reciprocalRankFusion` becomes a rank-only candidate-union helper (still picks
the merged candidate set across BM25 + embedding lists so a weak-but-present list is
never starved); it no longer produces the final scores.

`PromptAssembler.assemble(...)` gains `maxContextChars: Int = 8000`; the ViewModel
passes `retrievalConfig.maxContextTokens`. The hardcoded 8000 becomes the default;
the running app uses the configured budget.

## Filter & Eval Gate (user-directed scope addition)

New types in data/search:

```kotlin
enum class ShortCircuitReason { NO_CANDIDATES, ALL_PRUNED, BELOW_THRESHOLD }

data class RetrievalOutcome(
    val results: List<RetrievalResult>,
    val shortCircuit: ShortCircuitReason?,  // null when OK
    val trace: RetrievalTrace?
)

data class RetrievalTrace(
    val candidatesGenerated: Int,
    val prunedByDate: Int,
    val prunedBySourceType: Int,
    val prunedByRejectedEntity: Int,
    val prunedByTimeline: Int,
    val survivedPrune: Int,
    val belowThreshold: Int,
    val finalCount: Int
)
```

New `MetadataFilterExtractor` (data/search/): pure, deterministic, unit-testable.
Input: `QueryPlan` + candidate segments. Output: `RetrievalFilters` carrying
dateFrom, dateTo, sourceTypes, entityNames, requireTimeline, rejectedEntityNames.

### Prune rules (hard drops, pre-fusion)

1. **Date range** (plain temporal query, no `requiresTimeline`): chunk kept when its
   known segment interval overlaps `[dateFrom, dateTo]`; chunk with *unknown* time is
   kept (conservative — never kill relevance on missing metadata).
2. **Source type**: when `plan.sourceTypes` non-empty, drop any candidate not in it.
3. **Rejected entity link (full-chunk rejection)**: resolve each candidate chunk's
   segment ids, load every entity mention and its entity confirmation in one batch,
   and if ANY single mention is REJECTED or belongs to a REJECTED entity, drop the
   whole chunk (`rejected_entity_link`). No partial/majority weighting. Mirrors the
   Phase 4 `searchActive`/`getActiveMentions` guarantee, now enforced uniformly on
   the general path.
4. **Required timeline (tightened)**: when `requiresTimeline`, a chunk survives only
   if its source note has a timeline row with a decidable anchor (startMs non-null —
   EXACT/DAY_RANGE/MONTH/YEAR; RELATIVE/UNKNOWN have none). No row -> DROP
   `no_timeline_match`. Row with undecidable time -> DROP `timeline_time_undecidable`.
   Conservativeness is scoped away for temporal queries.

All four lookups batch over the candidate set via the existing `segmentsByIds` map;
rejected-entities and timeline rows loaded once per query. No N+1.

### Short-circuit for zero-result scenarios

The pipeline terminates early at the first empty stage, returning a canonical reason:

- `NO_CANDIDATES` — candidate generation produced nothing
- `ALL_PRUNED` — prune dropped everything (metadata constraints)
- `BELOW_THRESHOLD` — all fused scores < minimumScore (weak results rejected)
- `OK` (shortCircuit null) — normal return

`retrieve()` now returns `RetrievalOutcome`; `MainViewModel:3290` adapts. The chat
flow can answer "I don't have anything relevant" from NO_CANDIDATES/ALL_PRUNED instead
of assembling an empty prompt. Satisfies exit criteria "'not found' supported" and
makes "weak results rejected" measurable.

## Evaluation harness

### Fixture (single canonical file)

`app/src/main/assets/retrieval_fixtures.json` — Robolectric reads via
`ApplicationProvider` assets; the on-device runner reads the same asset (no
duplication). Covers the 7 query classes plus one paraphrase, one rejected-entity,
one date-range, one timeline, one "not found".

```json
{
  "question": "...",
  "expectedNoteIds": ["note_2026-03-risk"],
  "expectedKeywords": ["risk", "mitigation"],
  "mustCite": true,
  "expectNotFound": false
}
```

### RetrievalEvaluator (new: data/search/eval/RetrievalEvaluator.kt)

Pure, shared between the JVM suite and the on-device run.

| Metric | Computation | Layer |
|---|---|---|
| Recall@5 / Recall@10 | fraction of expectedNoteIds present in top-k | JVM (deterministic) |
| MRR | mean reciprocal rank of first expected note | JVM |
| Citation precision | citedSegments ∩ retrieved / citedSegments for mustCite fixtures | JVM |
| Unsupported claim rate | heuristic keyword/overlap proxy on answer claims vs retrieved excerpts; full LLM pass deferred to manual on-device run | JVM proxy + on-device |
| Refusal accuracy | expectNotFound ⇔ (shortCircuit != OK) agreement | JVM |
| Median + p95 latency | System.nanoTime around N warm retrieve() calls per fixture | on-device only |

### On-device latency runner (debug-only)

A debug entry (Settings debug section / Memory Hub dev card) runs the real embedder +
disk index through the same evaluator and writes `retrieval_eval_report.json` to
`filesDir`. Latency is meaningless on the JVM, so only the two latency metrics live
here; the other six gate the build.

## Files changed

New:
- `data/search/RetrievalConfig.kt`
- `data/search/RetrievalOutcome.kt` (RetrievalOutcome, ShortCircuitReason, RetrievalTrace)
- `data/search/MetadataFilterExtractor.kt` (RetrievalFilters + PruneDecision)
- `data/search/eval/RetrievalEvaluator.kt`
- `app/src/main/assets/retrieval_fixtures.json`
- Debug entry for the on-device latency run

Modified:
- `data/search/HybridRetriever.kt` — restructure retrieveGeneral; prune stage;
  normalize + weighted fusion; threshold; short-circuit; return RetrievalOutcome.
- `data/search/PromptAssembler.kt` — `maxContextChars` param.
- `data/settings/SettingsManager.kt` — `readRetrievalConfig()`.
- `viewmodel/MainViewModel.kt` — pass RetrievalConfig; adapt RetrievalOutcome.
- `data/memory/repository/EntityRepository.kt` (or DAO) — batched segment-mentions
  lookup with entity confirmation, for the rejected-entity full-chunk prune.

## Tests (JVM, Robolectric + MockK + in-memory DB)

- `MetadataFilterExtractorTest` — each prune rule in isolation.
- `RetrievalShortCircuitTest` — NO_CANDIDATES / ALL_PRUNED / BELOW_THRESHOLD.
- `RetrievalFusionNormalizationTest` — fused scores stay in [0,1]; keyword query
  ranks exact-match first; paraphrase ranks via vector weight; weights configurable
  and change ranking.
- `RetrievalEvaluationTest` — fixtures drive Recall@k/MRR minimums, citation
  precision, refusal accuracy = 1 for "not found".
- Infra mirrors `HybridRetrieverRejectedGatingTest` (real repos over in-memory DB;
  mocked search indexes / embedder).

## Exit criteria coverage

- retrieval reproducible: fixtures + deterministic metrics in CI.
- weights configurable: DataStore + weight-ranking test.
- threshold behavior measurable: BELOW_THRESHOLD + trace.
- exact lookups favor keyword matches: fusion normalization test.
- paraphrases retrieve semantic matches: fixture.
- weak results rejected: short-circuit + threshold.
- "not found" supported: refusal accuracy + ShortCircuitReason.

## Out of scope (later phases)

Phase 6 citation enforcement, Phase 7 privacy audit, Phase 8 importer reliability,
Phase 9 perf/a11y, Phase 10 advanced. The unsupported-claim LLM pass is deferred to
a manual on-device run (the deterministic keyword proxy ships now).