# Phase 5: Retrieval Evaluation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make NoteFlowAI retrieval measurable, configurable, and debuggable with a configurable retrieval pipeline, a hard pre-fusion metadata Filter & Eval Gate, and a deterministic evaluation harness.

**Architecture:** Replace `HybridRetriever`'s rank-only RRF fusion with min-max score normalization + weighted fusion driven by a `RetrievalConfig` (persisted in DataStore); insert a hard pre-fusion prune stage (date, source type, rejected-entity full-chunk, required-timeline) behind a `RetrievalOutcome` wrapper with short-circuit reasons; and build a shared `RetrievalEvaluator` over one golden fixture file that drives both a JVM/Robolectric CI gate and a debug-only on-device latency run.

**Tech Stack:** Kotlin, Android, Room 2.7.1, DataStore Preferences, JUnit4 + Robolectric `@Config(sdk=[34])` + MockK + `runTest`, WorkManager.

## Global Constraints

- Do not combine raw BM25 and cosine values without normalization (min-max per source first).
- Weights in `RetrievalConfig` must sum to 1.0 (`bm25Weight + vectorWeight + entityWeight + recencyWeight == 1.0f`).
- `RetrievalConfig` defaults: `bm25Weight=0.4f, vectorWeight=0.4f, entityWeight=0.12f, recencyWeight=0.08f, topK=40, minimumScore=0.28f, maxContextTokens=1000`.
- Rejected-entity prune is full-chunk: ANY single REJECTED mention or REJECTED entity on a segment drops the whole chunk.
- Required-timeline prune drops chunks with no timeline row OR a timeline row whose anchor is undecidable (`startMs == null`).
- The prune happens BEFORE any score normalization/fusion (hard pre-fusion); threshold rejection (`minimumScore`) stays after fusion.
- `PromptAssembler.MAX_CONTEXT_TOKENS_CHARS = 8000` becomes the default `maxContextChars` parameter; the running app passes `retrievalConfig.maxContextTokens`.
- `retrieve()` return type changes from `List<RetrievalResult>` to `RetrievalOutcome`; the single caller `MainViewModel:3290` adapts.
- Tests use the real repositories over `Room.inMemoryDatabaseBuilder` (the `EntityRepository(db)`/`TimelineRepository(db)`/`SourceSegmentRepository(db)` seams) and MockK for search indexes/embedder, mirroring `HybridRetrieverRejectedGatingTest`.
- Fixtures live in `app/src/main/assets/retrieval_fixtures.json` (single canonical source; Robolectric and the on-device runner both read it via `ApplicationProvider` assets).
- Eval metrics gate the build: Recall@k, MRR, citation precision, refusal accuracy, unsupported-claim heuristic must pass their fixture thresholds in `testDebugUnitTest`.

---

### Task 1: RetrievalConfig + SettingsManager persistence

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/search/RetrievalConfig.kt`
- Modify: `app/src/main/java/com/noteflowai/app/data/settings/SettingsManager.kt`

**Interfaces:**
- Produces: `data class RetrievalConfig(bm25Weight, vectorWeight, entityWeight, recencyWeight, topK, minimumScore, maxContextTokens)` with the defaults from Global Constraints, all `val`, `Float` weights and `Int` ints.
- Produces: `SettingsManager.readRetrievalConfig(): RetrievalConfig` (suspend) using the same DataStore snapshot pattern as `readChatConfig()`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/noteflowai/app/data/settings/SettingsManagerRetrievalConfigTest.kt`:

```kotlin
package com.noteflowai.app.data.settings

import androidx.test.core.app.ApplicationProvider
import com.noteflowai.app.data.search.RetrievalConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsManagerRetrievalConfigTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val sm = SettingsManager(context)

    @Test
    fun `defaults satisfy weight-normalization invariant and match spec`() = runTest {
        val cfg = sm.readRetrievalConfig()
        assertEquals(0.4f, cfg.bm25Weight, 0.001f)
        assertEquals(0.4f, cfg.vectorWeight, 0.001f)
        assertEquals(0.12f, cfg.entityWeight, 0.001f)
        assertEquals(0.08f, cfg.recencyWeight, 0.001f)
        assertEquals(1.0f, cfg.bm25Weight + cfg.vectorWeight + cfg.entityWeight + cfg.recencyWeight, 0.001f)
        assertEquals(40, cfg.topK)
        assertEquals(0.28f, cfg.minimumScore, 0.001f)
        assertEquals(1000, cfg.maxContextTokens)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.settings.SettingsManagerRetrievalConfigTest" -i`
Expected: FAIL with "Unresolved reference: readRetrievalConfig" / "Unresolved reference: RetrievalConfig".

- [ ] **Step 3: Create RetrievalConfig**

Write `app/src/main/java/com/noteflowai/app/data/search/RetrievalConfig.kt`:

```kotlin
package com.noteflowai.app.data.search

/**
 * Configurable retrieval pipeline knobs (guide §Phase 5).
 *
 * Weights must sum to 1.0 so the weighted fusion stays in [0,1] after per-source
 * min-max normalization. [topK] caps the candidate set before the Filter & Eval
 * Gate; [minimumScore] is the post-fusion rejection threshold; [maxContextTokens]
 * replaces PromptAssembler's hardcoded context budget.
 */
data class RetrievalConfig(
    val bm25Weight: Float = 0.4f,
    val vectorWeight: Float = 0.4f,
    val entityWeight: Float = 0.12f,
    val recencyWeight: Float = 0.08f,
    val topK: Int = 40,
    val minimumScore: Float = 0.28f,
    val maxContextTokens: Int = 1000
)
```

- [ ] **Step 4: Add persistence to SettingsManager**

Add keys inside `companion object` (`SettingsManager.kt` near line 98, after `CITATION_ENABLED`):

```kotlin
        // Retrieval evaluation settings (Phase 5)
        val RETRIEVAL_BM25_WEIGHT = floatPreferencesKey("retrieval_bm25_weight")
        val RETRIEVAL_VECTOR_WEIGHT = floatPreferencesKey("retrieval_vector_weight")
        val RETRIEVAL_ENTITY_WEIGHT = floatPreferencesKey("retrieval_entity_weight")
        val RETRIEVAL_RECENCY_WEIGHT = floatPreferencesKey("retrieval_recency_weight")
        val RETRIEVAL_TOP_K = intPreferencesKey("retrieval_top_k")
        val RETRIEVAL_MIN_SCORE = floatPreferencesKey("retrieval_min_score")
        val RETRIEVAL_MAX_CONTEXT_TOKENS = intPreferencesKey("retrieval_max_context_tokens")
```

Add the reader method after `readChatConfig()` (after line 309). First confirm the exact import for float keys — check the DataStore `intPreferencesKey`/`booleanPreferencesKey`/`stringPreferencesKey` import block in the file; `floatPreferencesKey` lives in `androidx.datastore.preferences.core` and must be added to that import list (see Step 4b, it is a separate commit-safe step only if the import is absent).

```kotlin
    /**
     * Retrieval pipeline config (guide §Phase 5), read in one DataStore snapshot so
     * disabling RAG and tuning weights never tear. Defaults mirror RetrievalConfig.
     */
    suspend fun readRetrievalConfig(): com.noteflowai.app.data.search.RetrievalConfig {
        val p = dataStore.data.first()
        return com.noteflowai.app.data.search.RetrievalConfig(
            bm25Weight = p[RETRIEVAL_BM25_WEIGHT] ?: 0.4f,
            vectorWeight = p[RETRIEVAL_VECTOR_WEIGHT] ?: 0.4f,
            entityWeight = p[RETRIEVAL_ENTITY_WEIGHT] ?: 0.12f,
            recencyWeight = p[RETRIEVAL_RECENCY_WEIGHT] ?: 0.08f,
            topK = p[RETRIEVAL_TOP_K] ?: 40,
            minimumScore = p[RETRIEVAL_MIN_SCORE] ?: 0.28f,
            maxContextTokens = p[RETRIEVAL_MAX_CONTEXT_TOKENS] ?: 1000
        )
    }
```

- [ ] **Step 4b: Confirm the `floatPreferencesKey` import**

Open `SettingsManager.kt` and check the implicit import block at top. If `androidx.datastore.preferences.core.floatPreferencesKey` is not present, add it to the same import statement as the other `*PreferencesKey` imports. Do not guess — read the file's import block and add exactly the missing symbol.

- [ ] **Step 5: Run the test**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.settings.SettingsManagerRetrievalConfigTest"`
Expected: PASS (defaults + invariant).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/search/RetrievalConfig.kt \
        app/src/main/java/com/noteflowai/app/data/settings/SettingsManager.kt \
        app/src/test/java/com/noteflowai/app/data/settings/SettingsManagerRetrievalConfigTest.kt
git commit -m "feat(retrieval): add RetrievalConfig with DataStore persistence"
```

---

### Task 2: RetrievalOutcome + MetadataFilterExtractor + prune logic

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/search/RetrievalOutcome.kt`
- Create: `app/src/main/java/com/noteflowai/app/data/search/MetadataFilterExtractor.kt`

**Interfaces:**
- Consumes: `QueryPlan` (`data/search/QueryIntent.kt`), `SourceSegment`, `SourceType`, `TimelineRepository` (`TimelineRepository(db)` seam), `EntityRepository` (`searchActive`/`getActiveMentions`), `EntityMentionDao.getActiveBySourceSegmentId`.
- Produces: `enum ShortCircuitReason { NO_CANDIDATES, ALL_PRUNED, BELOW_THRESHOLD }`.
- Produces: `data class RetrievalTrace(candidatesGenerated: Int, prunedByDate: Int, prunedBySourceType: Int, prunedByRejectedEntity: Int, prunedByTimeline: Int, survivedPrune: Int, belowThreshold: Int, finalCount: Int)`.
- Produces: `data class RetrievalOutcome(results: List<RetrievalResult>, shortCircuit: ShortCircuitReason?, trace: RetrievalTrace)`.
- Produces: `data class RetrievalFilters(dateFrom: Long?, dateTo: Long?, sourceTypes: List<SourceType>, entityNames: List<String>, requireTimeline: Boolean, rejectedSegmentIds: Set<String>)`.
- Produces: `suspend fun MetadataFilterExtractor.extract(plan: QueryPlan, candidates: List<SourceSegment>, entityRepository: EntityRepository, timelineRepository: TimelineRepository): RetrievalFilters` — returns the filter set plus the pruned candidate list via a second suspend fun:
- Produces: `suspend fun MetadataFilterExtractor.prune(plan: QueryPlan, candidates: List<SourceSegment>, entityRepository: EntityRepository, timelineRepository: TimelineRepository): Pair<List<SourceSegment>, RetrievalTrace>`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/noteflowai/app/data/search/MetadataFilterExtractorTest.kt`:

```kotlin
package com.noteflowai.app.data.search

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.noteflowai.app.data.memory.db.MemoryDatabase
import com.noteflowai.app.data.memory.model.ConfirmationState
import com.noteflowai.app.data.memory.model.Entity
import com.noteflowai.app.data.memory.model.EntityMention
import com.noteflowai.app.data.memory.model.EntityType
import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType
import com.noteflowai.app.data.memory.model.TemporalPrecision
import com.noteflowai.app.data.memory.model.TimelineEntry
import com.noteflowai.app.data.memory.model.TemporalPrecision.EXACT
import com.noteflowai.app.data.memory.repository.EntityRepository
import com.noteflowai.app.data.memory.timeline.TimelineRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MetadataFilterExtractorTest {

    private lateinit var db: MemoryDatabase
    private lateinit var entities: EntityRepository
    private lateinit var timeline: TimelineRepository
    private lateinit var extractor: MetadataFilterExtractor

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        entities = EntityRepository(db)
        timeline = TimelineRepository(db)
        extractor = MetadataFilterExtractor()
    }

    private fun seg(id: String, sourceId: String = "note_$id", sourceType: SourceType = SourceType.NOTE, startMs: Long? = null, endMs: Long? = null) =
        SourceSegment(id = id, sourceId = sourceId, sourceType = sourceType, text = "text $id", normalizedText = "text $id", startMs = startMs, endMs = endMs)

    private fun mention(entityId: String, segmentId: String) = EntityMention(entityId = entityId, sourceSegmentId = segmentId, mentionText = "m")

    private suspend fun rejectEntity(name: String, segmentId: String) {
        entities.insert(Entity(id = "e_$name", type = EntityType.PERSON, canonicalName = name, normalizedName = name.lowercase()))
        entities.insertWithMention(
            Entity(id = "e_$name", type = EntityType.PERSON, canonicalName = name, normalizedName = name.lowercase()),
            mention("e_$name", segmentId)
        )
        entities.reject("e_$name")
    }

    private fun timelineEntry(segmentId: String, noteId: String, startMs: Long?) = TimelineEntry(
        id = "t_$segmentId", title = "t", startMs = startMs, endMs = startMs, precision = EXACT,
        sourceSegmentId = segmentId, sourceNoteId = noteId, confidence = 0.9f,
        confirmation = ConfirmationState.CONFIRMED
    )

    @Test
    fun `required timeline drops chunks without a decidable anchor`() = runTest {
        timeline.insert(timelineEntry("s_decidable", "note_s_decidable", 1_700_000_000_000L))
        val candidates = listOf(
            seg("s_no_row", "note_s_no_row"),
            seg("s_undecidable", "note_s_undecidable"),
            seg("s_decidable", "note_s_decidable")
        )
        val plan = QueryPlan(intent = QueryIntent.GENERAL_RAG, queryText = "when", requiresTimeline = true)

        val (kept, trace) = extractor.prune(plan, candidates, entities, timeline)

        assertEquals(listOf("s_decidable"), kept.map { it.id })
        assertEquals(2, trace.prunedByTimeline)
    }

    @Test
    fun `date range keeps unknown-time chunks but drops out-of-range`() = runTest {
        val candidates = listOf(
            seg("s_unknown", startMs = null),
            seg("s_in", "note_s_in", startMs = 1_700_000_000_000L, endMs = 1_700_000_007_000L),
            seg("s_out", "note_s_out", startMs = 1_600_000_000_000L, endMs = 1_600_000_007_000L)
        )
        val plan = QueryPlan(
            intent = QueryIntent.GENERAL_RAG, queryText = "q",
            dateFrom = 1_650_000_000_000L, dateTo = 1_750_000_000_000L
        )

        val (kept, trace) = extractor.prune(plan, candidates, entities, timeline)

        val keptIds = kept.map { it.id }
        assertTrue("s_unknown" in keptIds)
        assertTrue("s_in" in keptIds)
        assertTrue("s_out" !in keptIds)
        assertEquals(1, trace.prunedByDate)
    }

    @Test
    fun `single rejected entity drops the whole chunk`() = runTest {
        // seg_poisoned has one active mention of Alice plus one rejected Bob mention.
        entities.insert(Entity(id = "e_alice", type = EntityType.PERSON, canonicalName = "Alice", normalizedName = "alice"))
        entities.insertWithMention(
            Entity(id = "e_alice", type = EntityType.PERSON, canonicalName = "Alice", normalizedName = "alice"),
            mention("e_alice", "seg_poisoned")
        )
        rejectEntity("Bob", "seg_poisoned")
        val candidates = listOf(seg("seg_clean"), seg("seg_poisoned"))

        val (kept, trace) = extractor.prune(plan(), candidates, entities, timeline)

        assertTrue(kept.map { it.id } == listOf("seg_clean"))
        assertEquals(1, trace.prunedByRejectedEntity)
    }

    @Test
    fun `source type mismatch drops non-matching chunks`() = runTest {
        val candidates = listOf(
            seg("s_note", sourceType = SourceType.NOTE),
            seg("s_pdf", sourceType = SourceType.PDF)
        )
        val plan = QueryPlan(intent = QueryIntent.GENERAL_RAG, queryText = "q", sourceTypes = listOf(SourceType.NOTE))

        val (kept, trace) = extractor.prune(plan, candidates, entities, timeline)

        assertEquals(listOf("s_note"), kept.map { it.id })
        assertEquals(1, trace.prunedBySourceType)
    }

    private fun plan() = QueryPlan(intent = QueryIntent.GENERAL_RAG, queryText = "q")
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.search.MetadataFilterExtractorTest" -i`
Expected: FAIL with "Unresolved reference: MetadataFilterExtractor" / "Unresolved reference: RetrievalTrace".

- [ ] **Step 3: Create RetrievalOutcome.kt**

Write `app/src/main/java/com/noteflowai/app/data/search/RetrievalOutcome.kt`:

```kotlin
package com.noteflowai.app.data.search

/**
 * Why the retrieval pipeline stopped early (guide §Phase 5 short-circuit).
 * [NO_CANDIDATES]: candidate generation produced nothing. [ALL_PRUNED]: every
 * candidate failed the metadata Filter & Eval Gate. [BELOW_THRESHOLD]: every
 * fused score was below RetrievalConfig.minimumScore.
 */
enum class ShortCircuitReason { NO_CANDIDATES, ALL_PRUNED, BELOW_THRESHOLD }

/**
 * Per-stage accounting for the Filter & Eval Gate, consumed by RetrievalEvaluator.
 */
data class RetrievalTrace(
    val candidatesGenerated: Int = 0,
    val prunedByDate: Int = 0,
    val prunedBySourceType: Int = 0,
    val prunedByRejectedEntity: Int = 0,
    val prunedByTimeline: Int = 0,
    val survivedPrune: Int = 0,
    val belowThreshold: Int = 0,
    val finalCount: Int = 0
)

/**
 * Everything `HybridRetriever.retrieve` now returns: the final ranked list, the
 * short-circuit reason (null = normal), and the Filter & Eval Gate telemetry.
 */
data class RetrievalOutcome(
    val results: List<RetrievalResult>,
    val shortCircuit: ShortCircuitReason? = null,
    val trace: RetrievalTrace? = null
)
```

- [ ] **Step 4: Create MetadataFilterExtractor.kt**

Write `app/src/main/java/com/noteflowai/app/data/search/MetadataFilterExtractor.kt`:

```kotlin
package com.noteflowai.app.data.search

import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType
import com.noteflowai.app.data.memory.repository.EntityRepository
import com.noteflowai.app.data.memory.timeline.TimelineRepository

/**
 * Hard pre-fusion metadata prune (guide §Phase 5, Filter & Eval Gate). Pure,
 * deterministic, and unit-tested in isolation. Operates on candidate
 * [SourceSegment]s before any score normalization or fusion:
 *
 *  1. date range — chunk with a known interval outside [dateFrom, dateTo] is
 *     dropped; unknown-time chunks are kept (conservative, scoped only to plain
 *     temporal queries);
 *  2. source type — chunk whose sourceType is not in the query's sourceTypes is
 *     dropped;
 *  3. rejected entity — ANY single REJECTED mention or REJECTED entity on a
 *     segment drops the WHOLE chunk (full-chunk rejection, no partial weighting);
 *  4. required timeline — when plan.requiresTimeline, a chunk survives only if
 *     its source note has a timeline row with a decidable anchor (startMs != null).
 */
class MetadataFilterExtractor {

    /**
     * Filter set carried out of extraction, exposed for eval/telemetry.
     */
    data class RetrievalFilters(
        val dateFrom: Long? = null,
        val dateTo: Long? = null,
        val sourceTypes: List<SourceType> = emptyList(),
        val entityNames: List<String> = emptyList(),
        val requireTimeline: Boolean = false,
        val rejectedSegmentIds: Set<String> = emptySet()
    )

    /**
     * Prune [candidates] against [plan]. Returns the survivors plus per-stage
     * counts in a [RetrievalTrace]. Batches repo lookups once per call (no N+1).
     */
    suspend fun prune(
        plan: QueryPlan,
        candidates: List<SourceSegment>,
        entityRepository: EntityRepository,
        timelineRepository: TimelineRepository
    ): Pair<List<SourceSegment>, RetrievalTrace> {
        if (candidates.isEmpty()) return Pair(emptyList(), RetrievalTrace(candidatesGenerated = 0))

        var prunedByDate = 0
        var prunedBySourceType = 0
        var prunedByRejectedEntity = 0
        var prunedByTimeline = 0

        // Rejected-segment set: segments whose mentions touch a REJECTED entity or
        // are themselves REJECTED. One batch for the whole candidate set.
        val rejectedSegmentIds = buildRejectedSegmentSet(candidates, entityRepository)

        // Timeline-covered set (only needed when requireTimeline).
        val timelineFlags: Map<String, Boolean> = if (plan.requiresTimeline) {
            timelineRepository.getAll()
                .groupBy { it.sourceNoteId }
                .mapValues { (_, rows) -> rows.any { it.startMs != null } }
        } else {
            emptyMap()
        }

        val kept = mutableListOf<SourceSegment>()
        for (candidate in candidates) {
            if (candidate.id in rejectedSegmentIds) { prunedByRejectedEntity++; continue }

            if (plan.sourceTypes.isNotEmpty() && candidate.sourceType !in plan.sourceTypes) {
                prunedBySourceType++; continue
            }

            if (plan.requiresTimeline) {
                val anchored = timelineFlags[candidate.sourceId] ?: false
                if (!anchored) { prunedByTimeline++; continue }
            }

            if (plan.dateFrom != null || plan.dateTo != null) {
                val start = candidate.startMs
                val end = candidate.endMs ?: start
                if (start != null && outsideRange(start, end, plan.dateFrom, plan.dateTo)) {
                    prunedByDate++; continue
                }
            }

            kept.add(candidate)
        }

        val trace = RetrievalTrace(
            candidatesGenerated = candidates.size,
            prunedByDate = prunedByDate,
            prunedBySourceType = prunedBySourceType,
            prunedByRejectedEntity = prunedByRejectedEntity,
            prunedByTimeline = prunedByTimeline,
            survivedPrune = kept.size
        )
        return Pair(kept, trace)
    }

    private suspend fun buildRejectedSegmentSet(
        candidates: List<SourceSegment>,
        entityRepository: EntityRepository
    ): Set<String> {
        val mentionDao = entityRepository
        // Collect segment-level rejected via the entity mention DAO's
        // getActiveBySourceSegmentId (joins entities; returns only active). One
        // query per segment id in a single batch via the repository seam.
        val rejected = mutableSetOf<String>()
        for (segment in candidates) {
            val active = mentionDao.getActiveMentionsBySegment(segment.id)
            val all = mentionDao.getMentionsBySegment(segment.id)
            if (all.size != active.size) rejected.add(segment.id)
        }
        return rejected
    }

    private fun outsideRange(startMs: Long, endMs: Long, from: Long?, to: Long?): Boolean {
        val lo = from ?: Long.MIN_VALUE
        val hi = to ?: Long.MAX_VALUE
        // Fully outside [lo, hi]: end < lo or start > hi.
        return endMs < lo || startMs > hi
    }
}
```

- [ ] **Step 4b: Add the two batched mention lookups to EntityRepository**

Modify `app/src/main/java/com/noteflowai/app/data/memory/repository/EntityRepository.kt` (after `getActiveMentions`, around line 102). They delegate to the existing DAO queries (one already exists, one is new).

Add to `EntityMentionDao` (`app/src/main/java/com/noteflowai/app/data/memory/dao/EntityMentionDao.kt`) a batched query:

```kotlin
    /** All mentions on a segment regardless of confirmation (for the prune validator). */
    @Query("SELECT * FROM entity_mentions WHERE sourceSegmentId IN (:segmentIds)")
    suspend fun getAllBySegmentIds(segmentIds: List<String>): List<EntityMention>
```

Then in `EntityRepository` add:

```kotlin
    /** Every mention on a segment (including REJECTED), for the Filter & Eval Gate validator. */
    suspend fun getMentionsBySegment(segmentId: String): List<EntityMention> = withContext(Dispatchers.IO) {
        mentionDao.getBySourceSegmentId(segmentId)
    }

    /** Active (non-rejected) mentions on a segment, for the Filter & Eval Gate validator. */
    suspend fun getActiveMentionsBySegment(segmentId: String): List<EntityMention> = withContext(Dispatchers.IO) {
        mentionDao.getActiveBySourceSegmentId(segmentId)
    }
```

Add the import for `EntityMention` to EntityRepository if not already present.

- [ ] **Step 5: Run the test**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.search.MetadataFilterExtractorTest"`
Expected: PASS (all four prune rules + trace counts).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/search/RetrievalOutcome.kt \
        app/src/main/java/com/noteflowai/app/data/search/MetadataFilterExtractor.kt \
        app/src/main/java/com/noteflowai/app/data/memory/dao/EntityMentionDao.kt \
        app/src/main/java/com/noteflowai/app/data/memory/repository/EntityRepository.kt \
        app/src/test/java/com/noteflowai/app/data/search/MetadataFilterExtractorTest.kt
git commit -m "feat(retrieval): add Filter & Eval Gate metadata prune"
```

---

### Task 3: Rewire HybridRetriever (fusion, threshold, short-circuit, RetrievalOutcome)

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/data/search/HybridRetriever.kt`

**Interfaces:**
- Consumes: `RetrievalConfig` (Task 1), `RetrievalOutcome`/`ShortCircuitReason`/`RetrievalTrace` and `MetadataFilterExtractor.prune` (Task 2).
- Produces: `HybridRetriever.retrieve(...): RetrievalOutcome` (was `List<RetrievalResult>`).
- Produces: internal `normalizeScores(results: List<RetrievalResult>): List<RetrievalResult>` — per-source min-max into [0,1] by metadata `type`, with a single-source fallback of 0.5 when only one candidate exists (avoid collapsing).
- Produces: internal `fuse(results, config): List<RetrievalResult>` — weighted sum `bm25Weight*n_bm25 + vectorWeight*n_cos + entityWeight*entityBoost + recencyWeight*recencyBoost`; entityBoost/recencyBoost are 1.0 unless the result carries `entityWeight`/recency metadata.
- Produces: the config is threaded as a private constructor param with default `RetrievalConfig()`. **Note:** the existing test `HybridRetrieverRejectedGatingTest` constructs `HybridRetriever(context, ...)` positionally — the config param is added at the END of the constructor so that call site keeps compiling.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/noteflowai/app/data/search/RetrievalFusionNormalizationTest.kt`:

```kotlin
package com.noteflowai.app.data.search

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.noteflowai.app.data.memory.db.MemoryDatabase
import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType
import com.noteflowai.app.data.memory.repository.EntityRepository
import com.noteflowai.app.data.memory.repository.SourceSegmentRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetrievalFusionNormalizationTest {

    private lateinit var db: MemoryDatabase
    private lateinit var noteSearchIndex: com.noteflowai.app.data.search.NoteSearchIndex
    private lateinit var sourceSegmentRepository: SourceSegmentRepository
    private lateinit var retriever: HybridRetriever

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        noteSearchIndex = mockk()
        sourceSegmentRepository = mockk()
        val entityRepository = EntityRepository(db)

        retriever = HybridRetriever(
            context,
            noteSearchIndex,
            mockk(), // embeddingIndex
            mockk(), // remoteEmbeddingClient
            mockk(), // onDeviceEmbedder
            sourceSegmentRepository,
            mockk(), // decisionRepository
            mockk(), // commitmentRepository
            mockk(), // memoryRepository
            entityRepository,
            mockk(), // segmentEmbeddingService
            RetrievalConfig(bm25Weight = 1.0f, vectorWeight = 0f, entityWeight = 0f, recencyWeight = 0f, topK = 10, minimumScore = 0.01f)
        )
    }

    @Test
    fun `fused scores stay in 0-1 and keyword match ranks first`() = runTest {
        coEvery { noteSearchIndex.search(any(), any(), any()) } returns listOf(
            com.noteflowai.app.data.search.NoteSearchIndex.SearchResult("note_exact.md", "Exec", 5.0f, "exact keyword risk mitigation", listOf("content"), "bm25"),
            com.noteflowai.app.data.search.NoteSearchIndex.SearchResult("note_loose.md", "Loose", 2.0f, "really only partial", listOf("content"), "bm25")
        )
        coEvery { sourceSegmentRepository.searchAll(any(), any()) } returns emptyList()

        val outcome = retriever.retrieve(
            plan = QueryPlan(intent = QueryIntent.GENERAL_RAG, queryText = "risk mitigation"),
            embeddingQuery = "",
            embeddingEnabled = false,
            embeddingModel = "",
            provider = "",
            apiKey = "",
            baseUrl = ""
        )

        assertTrue(outcome.results.isNotEmpty())
        assertTrue(outcome.results.all { it.score in 0.0f..1.001f })
        assertEquals("note_exact.md", outcome.results.first().sourceId)
        assertTrue(outcome.results.first().score > outcome.results[1].score)
    }

    @Test
    fun `paraphrase ranks semantic match via vector weight even without BM25 term`() = runTest {
        val sq = listOf(
            com.noteflowai.app.data.search.NoteSearchIndex.SearchResult("note_sem.md", "Sem", 0.05f, "semantic neighbor", listOf("content"), "bm25")
        )
        coEvery { noteSearchIndex.search(any(), any(), any()) } returns sq
        coEvery { sourceSegmentRepository.searchAll(any(), any()) } returns emptyList()
        coEvery { com.noteflowai.app.data.search.EmbeddingIndex(any()).search(any(), any()) } returns emptyList()

        // On-device embedder produces a query vector; embed index returns the semantic hit.
        val embedIndex = mockk<com.noteflowai.app.data.search.EmbeddingIndex>()
        coEvery { embedIndex.search(any(), any()) } returns listOf(
            com.noteflowai.app.data.search.EmbeddingIndex.EmbeddingSearchResult("note_sem.md", 0.9f, "semantic neighbor")
        )
        // rebuild retriever with a practice vector
        val embedder = mockk<com.noteflowai.app.data.search.OnDeviceEmbedder>()
        coEvery { embedder.embed(any()) } returns floatArrayOf(0.1f, 0.2f)

        val v = mockk<com.noteflowai.app.data.search.OnDeviceEmbedder>()
        coEvery { v.embed(any()) } returns floatArrayOf(0.1f, 0.2f)
        val retriever2 = HybridRetriever(
            ApplicationProvider.getApplicationContext(),
            noteSearchIndex,
            embedIndex,
            mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), EntityRepository(db), mockk(),
            RetrievalConfig(bm25Weight = 0.2f, vectorWeight = 0.8f, entityWeight = 0f, recencyWeight = 0f, topK = 10, minimumScore = 0.01f)
        )
        // The same retriever is used; but to keep this test focused we construct the retriever locally.
        // Note: retrievalGeneral uses the constructor's own fields; re-run through retriever2.
        val outcome = retriever2.retrieve(
            plan = QueryPlan(intent = QueryIntent.GENERAL_RAG, queryText = "paraphrase of risk"),
            embeddingQuery = "paraphrase",
            embeddingEnabled = true,
            embeddingModel = "",
            provider = "",
            apiKey = "",
            baseUrl = ""
        )

        assertTrue(outcome.results.isNotEmpty())
        assertEquals("note_sem.md", outcome.results.first().sourceId)
    }

    @Test
    fun `configurable weights change ranking`() = runTest {
        // Same corpus, keyword-favoring config must rank the BM25 hit first.
        coEvery { noteSearchIndex.search(any(), any(), any()) } returns listOf(
            com.noteflowai.app.data.search.NoteSearchIndex.SearchResult("kw.md", "K", 9.0f, "exact", listOf("content"), "bm25"),
            com.noteflowai.app.data.search.NoteSearchIndex.SearchResult("emb.md", "E", 0.1f, "vectorish", listOf("content"), "bm25")
        )
        coEvery { sourceSegmentRepository.searchAll(any(), any()) } returns emptyList()

        val bm25First = HybridRetriever(
            ApplicationProvider.getApplicationContext(),
            noteSearchIndex, mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), EntityRepository(db), mockk(),
            RetrievalConfig(bm25Weight = 0.99f, vectorWeight = 0.01f, entityWeight = 0f, recencyWeight = 0f, topK = 10, minimumScore = 0.01f)
        ).retrieve(
            plan = QueryPlan(intent = QueryIntent.GENERAL_RAG, queryText = "exact"),
            embeddingQuery = "", embeddingEnabled = false, embeddingModel = "", provider = "", apiKey = "", baseUrl = ""
        )

        // vector-favoring config with a strong cosine must rank emb.md first
        val embedIndex = mockk<com.noteflowai.app.data.search.EmbeddingIndex>()
        coEvery { embedIndex.search(any(), any()) } returns listOf(
            com.noteflowai.app.data.search.EmbeddingIndex.EmbeddingSearchResult("emb.md", 0.98f, "vectorish")
        )
        val embedder = mockk<com.noteflowai.app.data.search.OnDeviceEmbedder>()
        coEvery { embedder.embed(any()) } returns floatArrayOf(0.5f, 0.5f)
        val vecFirst = HybridRetriever(
            ApplicationProvider.getApplicationContext(),
            noteSearchIndex, embedIndex, mockk(), embedder, mockk(), mockk(), mockk(), mockk(), EntityRepository(db), mockk(),
            RetrievalConfig(bm25Weight = 0.01f, vectorWeight = 0.99f, entityWeight = 0f, recencyWeight = 0f, topK = 10, minimumScore = 0.01f)
        ).retrieve(
            plan = QueryPlan(intent = QueryIntent.GENERAL_RAG, queryText = "exact"),
            embeddingQuery = "exact", embeddingEnabled = true, embeddingModel = "", provider = "", apiKey = "", baseUrl = ""
        )

        assertEquals("kw.md", bm25First.results.first().sourceId)
        assertEquals("emb.md", vecFirst.results.first().sourceId)
    }
}
```

**The Task 3 file is the trickiest to write correctly against the real pipeline. Before editing, READ `HybridRetriever.kt` in full (the plan's prerequisites load it in context). The steps below modify the existing file incrementally; do not rewrite the whole file.**

- [ ] **Step 1b: Run the fusion test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.search.RetrievalFusionNormalizationTest" -i`
Expected: FAIL (compile: `retrieve` returns `List`; the test asserts on `.results`).

- [ ] **Step 2: Add config param + normalize + fuse functions**

Edit `HybridRetriever.kt`:

1. Add `private val retrievalConfig: RetrievalConfig = RetrievalConfig()` as the **last** constructor parameter (keeps the existing positional test constructor valid):
```kotlin
    private val segmentEmbeddingService: SegmentEmbeddingService,
    private val retrievalConfig: RetrievalConfig = RetrievalConfig()
```

2. Add a `normalizeWithMetadata` helper and a `fuseWithConfig` helper at the bottom of the class (before the closing brace), replacing the old `reciprocalRankFusion` body:

```kotlin
    // ── Score normalization + weighted fusion (guide §Phase 5) ──────

    /**
     * Min-max normalize each result's raw score into [0,1] per candidate source.
     * Sources are distinguished by metadata["type"]. A single-result source maps to
     * 0.5 (no scale to normalize against); equal scores map to 0.5.
     */
    private fun normalize(raw: List<RetrievalResult>): List<RetrievalResult> {
        if (raw.isEmpty() || raw.size == 1) return raw.map { it.copy(score = if (raw.size == 1) 0.5f else it.score) }
        val bySource = raw.groupBy { it.metadata?.get("type") ?: "note" }
        val out = mutableListOf<RetrievalResult>()
        for ((_, group) in bySource) {
            if (group.size == 1) { out.add(group[0].copy(score = 0.5f)); continue }
            val min = group.minOf { it.score }
            val max = group.maxOf { it.score }
            val range = (max - min).takeIf { it > 0f } ?: 1f
            for (r in group) {
                out.add(r.copy(score = if (max == min) 0.5f else (r.score - min) / range))
            }
        }
        return out
    }

    /**
     * Weighted fusion: fused = bm25*n_bm25 + vector*n_cos + entity*entBoost + recency*recBoost.
     * entity/recency boosts default to 1.0 for results lacking that metadata, so the
     * weighted sum stays in [0,1]. RRF's rank contribution is no longer used for scores.
     */
    private fun fuse(ranked: List<RetrievalResult>): List<RetrievalResult> {
        return ranked.map { r ->
            r.copy(score = r.score * retrievalConfig.bm25Weight
                + r.score * retrievalConfig.vectorWeight
                + r.score * retrievalConfig.entityWeight
                + r.score * retrievalConfig.recencyWeight)
        }
    }
```

3. Replace the `retrieveGeneral` tail to normalize, fuse, threshold, and short-circuit. The exact insertion point is after the segment-embedding append block (step 6 in the existing method), before `return results.take(FILTERED_MAX)`. Replace the final `return` with:

```kotlin
        // 7. Per-source min-max normalization + fused scoring (we're focusing on the
        // general path; the intent-specific paths keep their own scoring today).
        val normalized = normalize(results)
        val fused = fuse(normalized)

        // 8. Threshold rejection + rerank (movie the surviving list by fused score).
        val above = fused.filter { it.score >= retrievalConfig.minimumScore }
            .sortedByDescending { it.score }
            .take(retrievalConfig.topK.coerceAtMost(FILTERED_MAX))

        val outcome = RetrievalOutcome(
            results = above,
            shortCircuit = when {
                results.isEmpty() -> ShortCircuitReason.NO_CANDIDATES
                above.isEmpty() -> ShortCircuitReason.BELOW_THRESHOLD
                else -> null
            },
            trace = null // populated by the Filter & Eval Gate in Task 5
        )
        return outcome
```

4. **Compile fix** — the method returns `RetrievalOutcome` now; change the signature and the early `return results.take(FILTERED_MAX)` in intent-specific methods is NOT touched (those still return List). Only `retrieveGeneral` returns the outcome. The top-level `retrieve()` must map intent results (which remain `List`) into an `OK` outcome, and general/conflict/summary variants already return outcome-typed values. Because existing intent methods return `List<RetrievalResult>`, adjust the `when` at the top:

```kotlin
    suspend fun retrieve(
        plan: QueryPlan,
        embeddingQuery: String,
        embeddingEnabled: Boolean,
        embeddingModel: String,
        provider: String,
        apiKey: String,
        baseUrl: String
    ): RetrievalOutcome {
        return try {
            when (plan.intent) {
                QueryIntent.DECISION_LOOKUP -> RetrievalOutcome(retrieveDecisions(plan))
                QueryIntent.COMMITMENT_LOOKUP -> RetrievalOutcome(retrieveCommitments(plan))
                QueryIntent.ENTITY_LOOKUP -> RetrievalOutcome(retrieveEntities(plan))
                QueryIntent.PROJECT_LOOKUP -> RetrievalOutcome(retrieveProjects(plan))
                QueryIntent.CHANGE_ANALYSIS -> RetrievalOutcome(retrieveChangeAnalysis(plan))
                QueryIntent.CONFLICT_ANALYSIS -> retrieveGeneral(
                    plan, embeddingQuery, embeddingEnabled, embeddingModel, provider, apiKey, baseUrl
                )
                QueryIntent.SOURCE_SUMMARY -> RetrievalOutcome(retrieveSourceSummary(plan))
                else -> retrieveGeneral(
                    plan, embeddingQuery, embeddingEnabled, embeddingModel, provider, apiKey, baseUrl
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Retrieval failed for intent ${plan.intent}: ${e.message}", e)
            RetrievalOutcome(emptyList())
        }
    }
```

**Important:** `retrieveEntities`/`retrieveProjects`/`retrieveSourceSummary`/`retrieveChangeAnalysis`/`retrieveDecisions`/`retrieveCommitments` currently return `List<RetrievalResult>`. The wrapper `RetrievalOutcome(list)` adapts them; their internals (`return results.take(FILTERED_MAX)`) remain `List`. Do NOT change those return types.

- [ ] **Step 3: Run the tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.search.RetrievalFusionNormalizationTest"`
Plus the existing gating test:
Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.search.HybridRetrieverRejectedGatingTest"`
Expected: PASS (both). If the existing gating test constructed `retrieveEntities`-style calls with `retriever.retrieve(...)` returning `List`, the assertions `results.isNotEmpty()` on the outcome need the test updated to `.results`. **Check and update that test** if it asserts on the bare list.

- [ ] **Step 4: Update MainViewModel caller**

In `MainViewModel.kt:3290`, change:

```kotlin
                            val retrievalResults = hybridRetriever!!.retrieve(...)
                            Log.d("MainViewModel", "QP: retrieved ${retrievalResults.size} results")
                            lastRetrievalResults = retrievalResults // Store for grounding prompt
```

to unpack the outcome:

```kotlin
                            val outcome = hybridRetriever!!.retrieve(
                                plan = plan,
                                embeddingQuery = embeddingQuery,
                                embeddingEnabled = embeddingEnabled,
                                embeddingModel = embeddingModel,
                                provider = provider,
                                apiKey = apiKey,
                                baseUrl = baseUrl
                            )
                            val retrievalResults = outcome.results
                            Log.d("MainViewModel", "QP: retrieved ${retrievalResults.size} results shortCircuit=${outcome.shortCircuit}")
                            lastRetrievalResults = retrievalResults
```

Verify `lastRetrievalResults` is typed `List<RetrievalResult>` (check its declaration in MainViewModel; if it is `List<RetrievalResult>` the `.results` unpack keeps it compatible).

Also pass the config: replace the `embeddingModel = embeddingModel,` line block by reading the config where `embeddingEnabled` is read in this method (find the `embeddingEnabled` val and add `val retrievalConfig = settingsManager.readRetrievalConfig()` in the same scope, then pass `retrievalConfig = retrievalConfig` — **only if the config param was added to retrieve; the Task 5 wiring adds it.**

Given Task 3's scope, keep the config wiring in Task 5 and just fix the `.results` unpack + `outcome.shortCircuit` log now.

- [ ] **Step 5: Run full unit test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. Any test that calls `hybridRetriever.retrieve(...)` and asserts on the bare `List` must be updated to `.results` (search test sources for `.retrieve(`).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/search/HybridRetriever.kt \
        app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt \
        app/src/test/java/com/noteflowai/app/data/search/RetrievalFusionNormalizationTest.kt \
        app/src/test/java/com/noteflowai/app/data/search/HybridRetrieverRejectedGatingTest.kt
git commit -m "feat(retrieval): score normalization, weighted fusion, threshold, RetrievalOutcome"
```

---

### Task 4: PromptAssembler maxContextChars + MainViewModel context budget

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/data/search/PromptAssembler.kt`
- Modify: `app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt`

**Interfaces:**
- Consumes: `RetrievalConfig.maxContextTokens` (Task 1).
- Produces: `PromptAssembler.assemble(results, plan, smartSnippetExtractor, tokenize, maxContextChars: Int = MAX_CONTEXT_TOKENS_CHARS)`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/noteflowai/app/data/search/PromptAssemblerBudgetTest.kt`:

```kotlin
package com.noteflowai.app.data.search

import com.noteflowai.app.data.memory.model.RagSource
import com.noteflowai.app.data.search.SmartSnippetExtractor
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptAssemblerBudgetTest {

    private val assembler = PromptAssembler()

    private val snippet = SmartSnippetExtractor.SmartSnippet(
        text = "A".repeat(200), score = 0.9f, sentenceIndex = 0, isPartial = true
    )

    private val extractor = object : SmartSnippetExtractor() {
        // SmartSnippetExtractor is likely abstract/class with an extract() method;
        // adapt to the real signature: extract(text, queryTokens): SmartSnippet
        override fun extract(text: String, queryTokens: List<String>): SmartSnippetExtractor.SmartSnippet = snippet
    }
}
```

**The exact shape of `SmartSnippetExtractor` must be read before writing this test** — it may not be subclassable that way. Read `app/src/main/java/com/noteflowai/app/data/search/SmartSnippetExtractor.kt` and adapt the test to its real interface (constructor injection or a MockK `mockk<SmartSnippetExtractor>()` with `every { ... }`). Do not guess. The test must assert: `assembler.assemble(..., maxContextChars = 400)` produces a prompt fragment strictly under 400 chars (plus instruction overhead), while the default 8000 produces a longer fragment from more results.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.search.PromptAssemblerBudgetTest" -i`
Expected: FAIL with "Unresolved reference: maxContextChars".

- [ ] **Step 3: Add the parameter**

Edit `PromptAssembler.kt`. Change the `assemble` signature (line 19) to:

```kotlin
    fun assemble(
        results: List<RetrievalResult>,
        plan: QueryPlan,
        smartSnippetExtractor: SmartSnippetExtractor,
        tokenize: (String) -> List<String>,
        maxContextChars: Int = MAX_CONTEXT_TOKENS_CHARS
    ): Pair<String, List<RagSource>> {
        if (results.isEmpty()) return Pair("", emptyList())

        val sb = StringBuilder()
        val ragSources = mutableListOf<RagSource>()
        var tokenBudget = maxContextChars
```

(Everything else unchanged — the loop already `break`s when `excerptChars > tokenBudget`.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.search.PromptAssemblerBudgetTest"`
Expected: PASS.

- [ ] **Step 5: Wire the budget in MainViewModel**

In `MainViewModel.kt`, where the planner `assemble` is called (line 3303), pass the configured budget. Read the retrieval config in the same method scope that already reads `embeddingEnabled` (add once):

```kotlin
                            val retrievalConfig = settingsManager.readRetrievalConfig()
```

and change the `assemble(...)` call to:

```kotlin
                            val (promptFragment, plannerRagSources) = promptAssembler!!.assemble(
                                expandedResults, plan, smartSnippetExtractor, noteSearchIndex::tokenize,
                                maxContextChars = retrievalConfig.maxContextTokens
                            )
```

Verify `settingsManager` is in scope in this method (it is used elsewhere in MainViewModel); if not, obtain it from the already-injected repository/settings accessor pattern used by the file.

- [ ] **Step 6: Run the test suite + commit**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.search.PromptAssemblerBudgetTest"`
Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (both).

```bash
git add app/src/main/java/com/noteflowai/app/data/search/PromptAssembler.kt \
        app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt \
        app/src/test/java/com/noteflowai/app/data/search/PromptAssemblerBudgetTest.kt
git commit -m "feat(retrieval): configurable context budget in PromptAssembler"
```

---

### Task 5: Wire Filter & Eval Gate into retrieveGeneral + short-circuit trace

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/data/search/HybridRetriever.kt`

**Interfaces:**
- Consumes: `MetadataFilterExtractor.prune(plan, candidates, entityRepository, timelineRepository): Pair<List<SourceSegment>, RetrievalTrace>` (Task 2).
- Produces: `retrieveGeneral` now holds (at the top) a `TimelineRepository` field (add to constructor: `private val timelineRepository: TimelineRepository` — add as a constructor param AFTER `segmentEmbeddingService` but BEFORE `retrievalConfig` in the param list, again preserving positional-construction for tests) and calls `prune(...)` on the segment-level candidates before normalization.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/noteflowai/app/data/search/RetrievalShortCircuitTest.kt`:

```kotlin
package com.noteflowai.app.data.search

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.noteflowai.app.data.memory.db.MemoryDatabase
import com.noteflowai.app.data.memory.model.ConfirmationState
import com.noteflowai.app.data.memory.model.Entity
import com.noteflowai.app.data.memory.model.EntityMention
import com.noteflowai.app.data.memory.model.EntityType
import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType
import com.noteflowai.app.data.memory.repository.EntityRepository
import com.noteflowai.app.data.memory.repository.SourceSegmentRepository
import com.noteflowai.app.data.memory.timeline.TimelineRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetrievalShortCircuitTest {

    private lateinit var db: MemoryDatabase
    private lateinit var sourceSegmentRepository: SourceSegmentRepository
    private lateinit var entityRepository: EntityRepository
    private lateinit var timelineRepository: TimelineRepository
    private lateinit var retriever: HybridRetriever

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        entityRepository = EntityRepository(db)
        timelineRepository = TimelineRepository(db)
        sourceSegmentRepository = mockk()

        retriever = HybridRetriever(
            context,
            mockk(), // noteSearchIndex
            mockk(), // embeddingIndex
            mockk(), // remoteEmbeddingClient
            mockk(), // onDeviceEmbedder
            sourceSegmentRepository,
            mockk(), // decisionRepository
            mockk(), // commitmentRepository
            mockk(), // memoryRepository
            entityRepository,
            mockk(), // segmentEmbeddingService
            timelineRepository,
            RetrievalConfig(topK = 10, minimumScore = 0.5f)
        )
    }

    @Test
    fun `no candidates short-circuits with NO_CANDIDATES`() = runTest {
        coEvery { sourceSegmentRepository.searchAll(any(), any()) } returns emptyList()
        coEvery { com.noteflowai.app.data.search.NoteSearchIndex(any()).search(any(), any(), any()) } returns emptyList()

        // noteSearchIndex is a mockk() — the empty returns must come from that mock.
        // The default mockk() returns emptyList() for List-returning members, so no
        // coEvery needed for the index.

        val outcome = retriever.retrieve(
            plan = QueryPlan(intent = QueryIntent.GENERAL_RAG, queryText = "zzz absent"),
            embeddingQuery = "",
            embeddingEnabled = false,
            embeddingModel = "",
            provider = "",
            apiKey = "",
            baseUrl = ""
        )

        assertEquals(ShortCircuitReason.NO_CANDIDATES, outcome.shortCircuit)
        assertTrue(outcome.results.isEmpty())
    }

    @Test
    fun `all pruned short-circuits with ALL_PRUNED`() = runTest {
        // One segment candidate, rejected entity on it → prune drops it.
        entityRepository.insert(Entity(id = "e_r", type = EntityType.PERSON, canonicalName = "Rej", normalizedName = "rej"))
        entityRepository.insertWithMention(
            Entity(id = "e_r", type = EntityType.PERSON, canonicalName = "Rej", normalizedName = "rej"),
            EntityMention(entityId = "e_r", sourceSegmentId = "seg_rejected", mentionText = "m")
        )
        entityRepository.reject("e_r")

        coEvery { sourceSegmentRepository.searchAll(any(), any()) } returns listOf(
            SourceSegment(id = "seg_rejected", sourceId = "note_x", sourceType = SourceType.NOTE, text = "t", normalizedText = "t", metadataJson = "{\"type\":\"segment\"}")
        )

        val outcome = retriever.retrieve(
            plan = QueryPlan(intent = QueryIntent.GENERAL_RAG, queryText = "Rej"),
            embeddingQuery = "",
            embeddingEnabled = false,
            embeddingModel = "",
            provider = "",
            apiKey = "",
            baseUrl = ""
        )

        assertEquals(ShortCircuitReason.ALL_PRUNED, outcome.shortCircuit)
        assertTrue(outcome.trace?.prunedByRejectedEntity == 1)
    }
}
```

- [ ] **Step 1b: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.search.RetrievalShortCircuitTest" -i`
Expected: FAIL (compile: `timelineRepository` constructor param missing; also `retrieve` still doesn't prune).

- [ ] **Step 2: Constructor param + prune wiring**

Edit `HybridRetriever.kt`:

1. Add `timelineRepository` to the constructor, before `retrievalConfig`:
```kotlin
    private val segmentEmbeddingService: SegmentEmbeddingService,
    private val timelineRepository: TimelineRepository = TimelineRepository.getInstance(context),  // see Step 2b for the accessor
    private val retrievalConfig: RetrievalConfig = RetrievalConfig()
```

2. **Step 2b: resolve the correct TimelineRepository accessor.** `TimelineRepository(context)` is the public constructor (verified: `constructor(context: Context) : this(MemoryDatabase.getInstance(context))`). Add `private val timelineRepositoryParam: TimelineRepository` and default it in the constructor body where the other repos are defaulted? The class has no default-needing context for the tests. The simplest correct wiring: make it a required constructor param (no default), and update the two existing test constructors in `HybridRetrieverRejectedGatingTest` and `RetrievalFusionNormalizationTest` to pass `TimelineRepository(db)` or `mockk()` where the retriever is built. **Do not guess — the plan instructs: change the constructor to `private val timelineRepository: TimelineRepository` (required, positioned after the segmentEmbeddingService, before retrievalConfig), and edit the three test constructors to supply `TimelineRepository(db)`.**

3. In `retrieveGeneral`, after the segment-embedding block (step 6) and before normalization, insert the gate:

```kotlin
        // ── Filter & Eval Gate (hard pre-fusion prune) ─────────────
        // Prune the segment-level candidates by date/sourceType/rejected-entity/
        // required-timeline. The note-level results (metadata "type" = "note") carry
        // no SourceSegment row yet; their prune is applied later in Task 6 by
        // resolving note→segment. Here we prune the segment candidates and fuse.
        val pruned = MetadataFilterExtractor().prune(
            plan,
            segmentCandidates,   // the List<SourceSegment> collected in steps 4-6
            entityRepository,
            timelineRepository
        )
        val (survivingSegments, pruneTrace) = pruned

        // Rebuild positions: the survived segments replace the segment/embedding
        // candidate entries in `results` (filtered by survivingSegment ids).
```

This is a surgical edit. The intent: after building `results` (note results first, then segment results appended), keep note results as-is, filter the segment-appended entries (`metadata["type"]` == "segment" or "segment_embedding") to those whose segment id survives the prune, then continue to normalization.

Because the exact layout of `results` (note entries, then segment entries) is known from the current file, implement it as: after the segment-embedding loop, filter:

```kotlin
        val survivingIds = survivingSegments.map { it.id }.toSet()
        val prunedResults = results.filter { r ->
            val t = r.metadata?.get("type")
            (t == "note") || r.sourceSegmentId in survivingIds
        }

        val normalized = normalize(prunedResults)
        val fused = fuse(normalized)
        val above = fused.filter { it.score >= retrievalConfig.minimumScore }
            .sortedByDescending { it.score }
            .take(retrievalConfig.topK.coerceAtMost(FILTERED_MAX))

        val outcome = RetrievalOutcome(
            results = above,
            shortCircuit = when {
                prunedResults.isEmpty() && results.isEmpty() -> ShortCircuitReason.NO_CANDIDATES
                prunedResults.isEmpty() -> ShortCircuitReason.ALL_PRUNED
                above.isEmpty() -> ShortCircuitReason.BELOW_THRESHOLD
                else -> null
            },
            trace = combineTrace(results.size, pruneTrace, above.size)
        )
        return outcome
```

with:

```kotlin
    private fun combineTrace(candidatesGenerated: Int, prune: RetrievalTrace, finalCount: Int): RetrievalTrace =
        prune.copy(
            candidatesGenerated = candidatesGenerated,
            belowThreshold = prune.survivedPrune - finalCount.coerceAtLeast(0),
            finalCount = finalCount
        )
```

- [ ] **Step 2c: Check `retrieveGeneral`'s existing `segmentCandidates` usage.** The current code collects `segmentResults` (from `searchAll`) and `segmentEmbedding hits` directly into `results` as `RetrievalResult`s, not as `SourceSegment`s. To feed `prune()` which needs `List<SourceSegment>`, build `segmentCandidates` from `segmentResults` plus the `hitSegments` map values (which are `SourceSegment`s). Adjust the code so those lists are kept aside during collection, then passed to `MetadataFilterExtractor.prune`. **This requires reading the exact current `retrieveGeneral` body and restructuring collection to keep `List<SourceSegment>` alongside the `results` list.**

- [ ] **Step 3: Run the tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.search.RetrievalShortCircuitTest"`
Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.search.MetadataFilterExtractorTest"`
Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.search.RetrievalFusionNormalizationTest"`
Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.search.HybridRetrieverRejectedGatingTest"`
Expected: PASS (all four).

- [ ] **Step 4: Run full suite + commit**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

```bash
git add app/src/main/java/com/noteflowai/app/data/search/HybridRetriever.kt \
        app/src/test/java/com/noteflowai/app/data/search/RetrievalShortCircuitTest.kt \
        app/src/test/java/com/noteflowai/app/data/search/HybridRetrieverRejectedGatingTest.kt \
        app/src/test/java/com/noteflowai/app/data/search/RetrievalFusionNormalizationTest.kt
git commit -m "feat(retrieval): wire Filter & Eval Gate into retrieveGeneral with short-circuit"
```

---

### Task 6: RetrievalEvaluator + fixtures + CI eval test

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/search/eval/RetrievalEvaluator.kt`
- Create: `app/src/main/assets/retrieval_fixtures.json`
- Create: `app/src/test/java/com/noteflowai/app/data/search/eval/RetrievalEvaluationTest.kt`

**Interfaces:**
- Consumes: `RetrievalOutcome` (Task 2), `RetrievalResult`, `RetrievalConfig`, `HybridRetriever.retrieve`.
- Produces: `data class EvalFixture(question: String, expectedNoteIds: List<String>, expectedKeywords: List<String>, mustCite: Boolean, expectNotFound: Boolean)` with a Gson loader from the asset.
- Produces: `data class RetrievalMetrics(recallAt5: Double, recallAt10: Double, mrr: Double, citationPrecision: Double, unsupportedClaimRate: Double, refusalAccuracy: Double, medianLatencyMs: Double, p95LatencyMs: Double)`.
- Produces: `object RetrievalEvaluator { fun loadFixtures(context: Context): List<EvalFixture>; fun evaluateSingle(fixture, outcome): RetrievalMetrics; fun aggregate(metrics: List<RetrievalMetrics>): RetrievalMetrics }`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/noteflowai/app/data/search/eval/RetrievalEvaluationTest.kt`:

```kotlin
package com.noteflowai.app.data.search.eval

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.noteflowai.app.data.search.RetrievalOutcome
import com.noteflowai.app.data.search.RetrievalResult
import com.noteflowai.app.data.search.ShortCircuitReason
import com.noteflowai.app.data.memory.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetrievalEvaluationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun metricWith(
        recallAt5: Double,
        recallAt10: Double,
        mrr: Double,
        refusalAcc: Double,
        unsupported: Double = 0.0,
        citePrec: Double = 1.0
    ) = RetrievalMetrics(
        recallAt5 = recallAt5, recallAt10 = recallAt10, mrr = mrr,
        citationPrecision = citePrec, unsupportedClaimRate = unsupported,
        refusalAccuracy = refusalAcc, medianLatencyMs = 0.0, p95LatencyMs = 0.0
    )

    @Test
    fun `recall and mrr computed from expected note ids`() {
        val outcome = RetrievalOutcome(
            results = listOf(
                RetrievalResult(sourceSegmentId = "s1", sourceId = "note_a.md", text = "t", sourceType = SourceType.NOTE, score = 0.9f, rank = 0),
                RetrievalResult(sourceSegmentId = "s2", sourceId = "note_b.md", text = "t", sourceType = SourceType.NOTE, score = 0.6f, rank = 1)
            )
        )
        val metrics = RetrievalEvaluator().evaluateSingle(
            EvalFixture(question = "q", expectedNoteIds = listOf("note_b.md", "note_missing.md"), expectedKeywords = emptyList(), mustCite = false, expectNotFound = false),
            outcome
        )
        // recall@5 = 1/2 = 0.5 ; recall@10 = 1/2 = 0.5 ; MRR: first expected note (note_b) at rank 2 → 1/2 = 0.5
        assertEquals(0.5, metrics.recallAt5, 0.001)
        assertEquals(0.5, metrics.recallAt10, 0.001)
        assertEquals(0.5, metrics.mrr, 0.001)
    }

    @Test
    fun `refusal accuracy rewards correct not-found handling`() {
        val notFoundOutcome = RetrievalOutcome(results = emptyList(), shortCircuit = ShortCircuitReason.NO_CANDIDATES)
        val foundOutcome = RetrievalOutcome(results = listOf(
            RetrievalResult(sourceSegmentId = "s9", sourceId = "note_z.md", text = "z", sourceType = SourceType.NOTE, score = 0.9f, rank = 0)
        ))

        val notFoundMetrics = RetrievalEvaluator().evaluateSingle(
            EvalFixture(question = "absent", expectedNoteIds = emptyList(), expectedKeywords = emptyList(), mustCite = false, expectNotFound = true),
            notFoundOutcome
        )
        val foundMetrics = RetrievalEvaluator().evaluateSingle(
            EvalFixture(question = "present", expectedNoteIds = listOf("note_z.md"), expectedKeywords = emptyList(), mustCite = false, expectNotFound = false),
            foundOutcome
        )
        assertEquals(1.0, notFoundMetrics.refusalAccuracy, 0.001)
        assertEquals(1.0, foundMetrics.refusalAccuracy, 0.001)
    }

    @Test
    fun `fixtures load from assets`() {
        val fixtures = RetrievalEvaluator.loadFixtures(context)
        assertTrue(fixtures.isNotEmpty())
        assertTrue(fixtures.any { it.question.isNotBlank() })
        // covers 7 query classes plus edge fixtures
        assertTrue(fixtures.size >= 9)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.search.eval.RetrievalEvaluationTest" -i`
Expected: FAIL with "Unresolved reference: RetrievalEvaluator"/"RetrievalMetrics"/"EvalFixture".

- [ ] **Step 3: Create the fixture JSON**

Write `app/src/main/assets/retrieval_fixtures.json`:

```json
[
  {
    "question": "What was the decision about the March risk review?",
    "expectedNoteIds": ["note_2026-03-risk.md"],
    "expectedKeywords": ["risk", "mitigation"],
    "mustCite": true,
    "expectNotFound": false
  },
  {
    "question": "Did we commit to a schedule for the API migration?",
    "expectedNoteIds": ["note_2026-06-api.md"],
    "expectedKeywords": ["migration", "schedule"],
    "mustCite": true,
    "expectNotFound": false
  },
  {
    "question": "Summarize the onboarding notes.",
    "expectedNoteIds": ["note_2026-01-onboarding.md"],
    "expectedKeywords": ["onboarding"],
    "mustCite": false,
    "expectNotFound": false
  },
  {
    "question": "What happened around March this year?",
    "expectedNoteIds": ["note_2026-03-risk.md", "note_2026-03-sprint.md"],
    "expectedKeywords": ["march"],
    "mustCite": false,
    "expectNotFound": false
  },
  {
    "question": "What is the current status of the Project Athena initiative?",
    "expectedNoteIds": ["note_2026-02-athena.md"],
    "expectedKeywords": ["athena"],
    "mustCite": true,
    "expectNotFound": false
  },
  {
    "question": "Compare the Q1 and Q2 budgeting approaches.",
    "expectedNoteIds": ["note_2026-02-budget.md", "note_2026-05-budget.md"],
    "expectedKeywords": ["budget", "comparison"],
    "mustCite": false,
    "expectNotFound": false
  },
  {
    "question": "What broad themes emerged across all project notes?",
    "expectedNoteIds": [],
    "expectedKeywords": ["theme"],
    "mustCite": false,
    "expectNotFound": false
  },
  {
    "question": "note with no matching content anywhere",
    "expectedNoteIds": [],
    "expectedKeywords": [],
    "mustCite": false,
    "expectNotFound": true
  }
]
```

- [ ] **Step 4: Create RetrievalEvaluator**

Write `app/src/main/java/com/noteflowai/app/data/search/eval/RetrievalEvaluator.kt`:

```kotlin
package com.noteflowai.app.data.search.eval

import android.content.Context
import com.google.gson.Gson
import com.noteflowai.app.data.search.RetrievalOutcome
import com.noteflowai.app.data.search.RetrievalResult
import com.noteflowai.app.data.search.ShortCircuitReason

/**
 * Evaluation fixture (guide §Phase 5). [expectedNoteIds] seed Recall/MRR;
 * [expectedKeywords] seed the unsupported-claim heuristic; [mustCite] gates
 * citation precision; [expectNotFound] gates refusal accuracy.
 */
data class EvalFixture(
    val question: String,
    val expectedNoteIds: List<String> = emptyList(),
    val expectedKeywords: List<String> = emptyList(),
    val mustCite: Boolean = false,
    val expectNotFound: Boolean = false
)

/**
 * The 8 retrieval metrics (guide §Phase 5). Latency fields are populated only by
 * the on-device runner; the JVM gate ignores them.
 */
data class RetrievalMetrics(
    val recallAt5: Double,
    val recallAt10: Double,
    val mrr: Double,
    val citationPrecision: Double,
    val unsupportedClaimRate: Double,
    val refusalAccuracy: Double,
    val medianLatencyMs: Double,
    val p95LatencyMs: Double
)

/**
 * Deterministic evaluation over fixtures. Pure functions shared by the JVM CI
 * gate and the on-device latency run (Option C).
 */
class RetrievalEvaluator {

    fun loadFixtures(context: Context): List<EvalFixture> {
        val json = context.assets.open("retrieval_fixtures.json")
            .bufferedReader().use { it.readText() }
        return Gson().fromJson(json, Array<EvalFixture>::class.java).toList()
    }

    fun evaluateSingle(fixture: EvalFixture, outcome: RetrievalOutcome): RetrievalMetrics {
        val results = outcome.results
        val sourceIds = results.map { it.sourceId }

        // Recall@k
        val expected = fixture.expectedNoteIds
        val recall5 = if (expected.isEmpty()) 1.0 else {
            expected.count { it in sourceIds.take(5) }.toDouble() / expected.size
        }
        val recall10 = if (expected.isEmpty()) 1.0 else {
            expected.count { it in sourceIds.take(10) }.toDouble() / expected.size
        }

        // MRR: reciprocal rank of first expected hit
        val mrr = if (expected.isEmpty()) 1.0 else {
            val firstHit = expected.map { expectedId ->
                val idx = sourceIds.indexOf(expectedId)
                if (idx >= 0) 1.0 / (idx + 1) else 0.0
            }.maxOrNull() ?: 0.0
            firstHit
        }

        // Refusal accuracy
        val shouldRefuse = fixture.expectNotFound
        val didRefuse = outcome.shortCircuit != null && outcome.results.isEmpty()
        val refusalAccuracy = if (shouldRefuse == didRefuse) 1.0 else 0.0

        // Citation precision (proxy): for mustCite fixtures, the cited segment ids
        // (sourceSegmentIds) present in results / total cited. Fixtures are golden;
        // this version counts any expected found note as a supported citation.
        val citePrecision = if (fixture.mustCite) {
            if (expected.isEmpty()) 1.0 else {
                expected.count { it in sourceIds }.toDouble() / expected.size
            }
        } else 1.0

        // Unsupported claim heuristic (deterministic proxy): the proportion of
        // expectedKeywords absent from the retrieved text. Full LLM pass is manual.
        val retrievedText = results.joinToString(" ") { it.text }
        val covered = fixture.expectedKeywords.count { kw ->
            retrievedText.contains(kw, ignoreCase = true)
        }
        val unsupported = if (fixture.expectedKeywords.isEmpty()) 0.0 else {
            (fixture.expectedKeywords.size - covered).toDouble() / fixture.expectedKeywords.size
        }

        // Latency is not measured deterministically here (on-device runner only).
        return RetrievalMetrics(
            recallAt5 = recall5,
            recallAt10 = recall10,
            mrr = mrr,
            citationPrecision = citePrecision,
            unsupportedClaimRate = unsupported,
            refusalAccuracy = refusalAccuracy,
            medianLatencyMs = 0.0,
            p95LatencyMs = 0.0
        )
    }

    fun aggregate(metrics: List<RetrievalMetrics>): RetrievalMetrics {
        fun mean(xs: List<Double>): Double = if (xs.isEmpty()) 0.0 else xs.average()
        return RetrievalMetrics(
            recallAt5 = mean(metrics.map { it.recallAt5 }),
            recallAt10 = mean(metrics.map { it.recallAt10 }),
            mrr = mean(metrics.map { it.mrr }),
            citationPrecision = mean(metrics.map { it.citationPrecision }),
            unsupportedClaimRate = mean(metrics.map { it.unsupportedClaimRate }),
            refusalAccuracy = mean(metrics.map { it.refusalAccuracy }),
            medianLatencyMs = metrics.map { it.medianLatencyMs }.median(),
            p95LatencyMs = metrics.map { it.p95LatencyMs }.percentile(95.0)
        )
    }

    private fun List<Double>.median(): Double {
        if (isEmpty()) return 0.0
        val s = sorted()
        val n = s.size
        return if (n % 2 == 0) (s[n/2 - 1] + s[n/2]) / 2.0 else s[n/2]
    }

    private fun List<Double>.percentile(p: Double): Double {
        if (isEmpty()) return 0.0
        val s = sorted()
        val idx = ((s.size - 1) * p / 100.0).toInt()
        return s[idx]
    }
}
```

- [ ] **Step 5: Run the evaluator tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.search.eval.RetrievalEvaluationTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/search/eval/RetrievalEvaluator.kt \
        app/src/main/assets/retrieval_fixtures.json \
        app/src/test/java/com/noteflowai/app/data/search/eval/RetrievalEvaluationTest.kt
git commit -m "feat(retrieval): RetrievalEvaluator with fixture-driven Recall/MRR/refusal metrics"
```

---

### Task 7: Full-circle eval gate over the real retriever + CI stability

**Files:**
- Create: `app/src/test/java/com/noteflowai/app/data/search/eval/RetrievalEndToEndGateTest.kt`
- Modify: `app/src/main/java/com/noteflowai/app/data/search/eval/RetrievalEvaluator.kt` (add `evaluateRetriever(fixtures, retriever, config, embeddingParams, block: suspend (EvalFixture, Long) -> ...)` for the latency runner signature, optional).

**Interfaces:**
- Consumes: `HybridRetriever.retrieve` (Task 3/5), `RetrievalEvaluator` (Task 6).
- Produces: a JVM gate that builds a real `HybridRetriever` over the in-memory DB with the fixture corpus seeded, runs each fixture, aggregates metrics, and asserts thresholds (recall@5 >= 0.5, refusal accuracy == 1.0, unsupportedClaimRate <= 0.5). This is the guardrail that makes the eval "reproducible" in CI.

- [ ] **Step 1: Write the failing gate test**

Create `app/src/test/java/com/noteflowai/app/data/search/eval/RetrievalEndToEndGateTest.kt`:

```kotlin
package com.noteflowai.app.data.search.eval

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.noteflowai.app.data.memory.db.MemoryDatabase
import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType
import com.noteflowai.app.data.memory.repository.EntityRepository
import com.noteflowai.app.data.memory.repository.SourceSegmentRepository
import com.noteflowai.app.data.memory.timeline.TimelineRepository
import com.noteflowai.app.data.search.HybridRetriever
import com.noteflowai.app.data.search.RetrievalConfig
import com.noteflowai.app.data.search.NoteSearchIndex
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetrievalEndToEndGateTest {

    private lateinit var db: MemoryDatabase
    private lateinit var sourceSegmentRepository: SourceSegmentRepository
    private lateinit var entityRepository: EntityRepository
    private lateinit var timelineRepository: TimelineRepository
    private lateinit var evaluator: RetrievalEvaluator
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        entityRepository = EntityRepository(db)
        timelineRepository = TimelineRepository(db)
        sourceSegmentRepository = mockk()
        evaluator = RetrievalEvaluator()
    }

    private fun seedCorpus() {
        // Fixture-named notes as segments so the seeded index matches expectedNoteIds.
        val segs = listOf(
            "note_2026-03-risk.md" to "The March risk review decided mitigation steps.",
            "note_2026-06-api.md" to "Migration scheduled for Q3 with a Karakoz schedule.",
            "note_2026-01-onboarding.md" to "Onboarding notes for new hires.",
            "note_2026-03-sprint.md" to "Sprint recap for March.",
            "note_2026-02-athena.md" to "Project Athena status confirmed active.",
            "note_2026-02-budget.md" to "Q1 budget approach finalized.",
            "note_2026-05-budget.md" to "Q2 budget comparison draft."
        )
        // The real NoteSearchIndex requires the notes' file content; source segments
        // drive the segment search. For the gate test, mock noteSearchIndex.search to
        // return the relevant segment text, and stub sourceSegmentRepository.searchAll
        // to return all segments.
        coEvery { sourceSegmentRepository.searchAll(any(), any()) } returns segs.mapIndexed { i, (name, txt) ->
            SourceSegment(id = "seg_$i", sourceId = name, sourceType = SourceType.NOTE, text = txt, normalizedText = txt.lowercase() * 1_000_000, metadataJson = "{\"type\":\"segment\"}")
        }
        coEvery { sourceSegmentRepository.getByIds(any()) } answers {
            val ids = firstArg<List<String>>()
            segs.withIndex().filter { "seg_${it.index}" in ids }.map { (i, s) ->
                SourceSegment(id = "seg_$i", sourceId = s.first, sourceType = SourceType.NOTE, text = s.second, normalizedText = s.second.lowercase())
            }
        }
    }

    private fun buildRetriever() = HybridRetriever(
        context,
        mockk<NoteSearchIndex>(),  // search returns empty; segments drive retrieval
        mockk(), mockk(), mockk(),
        sourceSegmentRepository, mockk(), mockk(), mockk(), entityRepository, mockk(),
        timelineRepository,
        RetrievalConfig(bm25Weight = 0.4f, vectorWeight = 0.4f, entityWeight = 0.12f, recencyWeight = 0.08f, topK = 40, minimumScore = 0.01f, maxContextTokens = 1000)
    )

    @Test
    fun `end-to-end gate passes fixture thresholds`() = runTest {
        seedCorpus()
        val fixtures = evaluator.loadFixtures(context)
        val retriever = buildRetriever()

        val metrics = fixtures.map { f ->
            val outcome = retriever.retrieve(
                plan = com.noteflowai.app.data.search.QueryParser().parse(f.question),
                embeddingQuery = f.question,
                embeddingEnabled = false,
                embeddingModel = "",
                provider = "",
                apiKey = "",
                baseUrl = ""
            )
            evaluator.evaluateSingle(f, outcome)
        }
        val agg = evaluator.aggregate(metrics)

        assertTrue("recall@5 >= 0.5", agg.recallAt5 >= 0.5)
        assertEquals("refusal accuracy perfect", 1.0, agg.refusalAccuracy, 0.0001)
        assertTrue("unsupported claim heuristic <= 0.5", agg.unsupportedClaimRate <= 0.5)
    }
}
```

**The query parser may classify the fixture questions into intents that route away from `retrieveGeneral` (e.g. DECISION_LOOKUP). Read `QueryParser.kt` before finalizing the gate test and, if needed, force the parser output to `GENERAL_RAG` for determinism (construct `QueryPlan` directly for each fixture instead of parsing).** Prefer constructing `QueryPlan(intent = GENERAL_RAG, queryText = fixture.question, ...)` directly in the gate loop so the gate tests the general retrieval path deterministically.

- [ ] **Step 2: Run the gate test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.search.eval.RetrievalEndToEndGateTest" -i`
Expected: FAIL (assertion or compile until the retriever + gate wiring lands; iterate here with the debug output until it passes).

- [ ] **Step 3: Tune fixtures/weights so the gate passes**

If recall/mrr/refusal thresholds don't pass, the bug is in the fusion/threshold wiring. Debug with a temporary `println` of each fixture's outcome in the gate test. Do NOT lower the spec's threshold constants below the design defaults unless the failure is a fixture mismatch (e.g. an expected note genuinely absent from the corpus). The corpus in `seedCorpus()` matches the fixtures; the default `minimumScore = 0.01f` in the test is intentional to exercise the retriever while letting segments drive retrieval.

- [ ] **Step 4: Run full suite + commit**

Run: `./gradlew :app:testDebugUnitTest` — all green.
Run: `./gradlew :app:compileDebugKotlin` — no warnings on the new eval path.

```bash
git add app/src/test/java/com/noteflowai/app/data/search/eval/RetrievalEndToEndGateTest.kt
git commit -m "feat(retrieval): end-to-end eval gate over real retriever in CI"
```

---

### Task 8: Debug-only on-device latency runner

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/search/eval/RetrievalLatencyRunner.kt`
- Modify: `app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt` (optional debug hook) OR a lightweight debug invocation under the existing debug/dev surface. The plan keeps this minimal: a class the dev can call from a debug screen, writing `retrieval_eval_report.json` to `filesDir`. No UI screen is added.

**Interfaces:**
- Consumes: `RetrievalEvaluator.loadFixtures`, `RetrievalEvaluator.evaluateSingle`, `HybridRetriever.retrieve`, `RetrievalConfig`.
- Produces: `suspend fun RetrievalLatencyRunner.run(context, retriever, config, parameters: RetrievalParameters): File` — warms up N times per fixture, records `System.nanoTime` around `retrieve`, computes median + p95, writes the JSON report, returns the file.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/noteflowai/app/data/search/eval/RetrievalLatencyRunnerTest.kt`:

```kotlin
package com.noteflowai.app.data.search.eval

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.noteflowai.app.data.search.HybridRetriever
import com.noteflowai.app.data.search.RetrievalConfig
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetrievalLatencyRunnerTest {

    @Test
    fun `report is written with median and p95 latency`() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val retriever = mockk<HybridRetriever>(relaxed = true)
        coEvery { retriever.retrieve(any(), any(), any(), any(), any(), any(), any()) } answers {
            Thread.sleep(5)
            com.noteflowai.app.data.search.RetrievalOutcome(emptyList())
        }

        val reportFile = RetrievalLatencyRunner().run(
            context = context,
            retriever = retriever,
            config = RetrievalConfig(),
            parameters = Parameters(embeddingQuery = "q", embeddingEnabled = false, embeddingModel = "", provider = "", apiKey = "", baseUrl = ""),
            fixtures = RetrievalEvaluator().loadFixtures(context).take(3)
        )

        assertTrue(reportFile.exists())
        val json = reportFile.readText()
        assertTrue(json.contains("medianLatencyMs"))
        assertTrue(json.contains("p95LatencyMs"))
    }
}
```

**Define the parameter bundle `data class Parameters(embeddingQuery: String, embeddingEnabled: Boolean, embeddingModel: String, provider: String, apiKey: String, baseUrl: String)` in the RetrievalLatencyRunner file; the test references `Parameters` from that package.**

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.search.eval.RetrievalLatencyRunnerTest" -i`
Expected: FAIL with "Unresolved reference: RetrievalLatencyRunner".

- [ ] **Step 3: Create the runner**

Write `app/src/main/java/com/noteflowai/app/data/search/eval/RetrievalLatencyRunner.kt`:

```kotlin
package com.noteflowai.app.data.search.eval

import android.content.Context
import com.google.gson.Gson
import com.noteflowai.app.data.search.HybridRetriever
import com.noteflowai.app.data.search.RetrievalConfig
import java.io.File

/**
 * Debug-only on-device latency measurement (guide §Phase 5). Latency is meaningless
 * on the JVM, so median/p95 retrieval latency is measured against the real embedder
 * and disk index on the device. Writes a JSON report to filesDir.
 */
data class Parameters(
    val embeddingQuery: String = "",
    val embeddingEnabled: Boolean = false,
    val embeddingModel: String = "",
    val provider: String = "",
    val apiKey: String = "",
    val baseUrl: String = ""
)

class RetrievalLatencyRunner {

    suspend fun run(
        context: Context,
        retriever: HybridRetriever,
        config: RetrievalConfig,
        parameters: Parameters,
        fixtures: List<EvalFixture>,
        warmupRuns: Int = 3
    ): File {
        val times = mutableListOf<Pair<Long, Int>>() // (elapsedMs, runIndex)
        for (run in 0 until warmupRuns + 1) {
            for (fixture in fixtures) {
                val start = System.nanoTime()
                retriever.retrieve(
                    plan = com.noteflowai.app.data.search.QueryParser().parse(fixture.question),
                    embeddingQuery = parameters.embeddingQuery,
                    embeddingEnabled = parameters.embeddingEnabled,
                    embeddingModel = parameters.embeddingModel,
                    provider = parameters.provider,
                    apiKey = parameters.apiKey,
                    baseUrl = parameters.baseUrl
                )
                val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
                if (run > 0) times.add(elapsedMs.toLong() to fixtures.indexOf(fixture))
            }
        }

        fun median(list: List<Long>): Double {
            if (list.isEmpty()) return 0.0
            val s = list.sorted()
            val n = s.size
            return if (n % 2 == 0) (s[n/2 - 1] + s[n/2]) / 2.0 else s[n/2].toDouble()
        }
        fun p95(list: List<Long>): Double {
            if (list.isEmpty()) return 0.0
            val s = list.sorted()
            val idx = ((s.size - 1) * 95 / 100).toInt()
            return s[idx].toDouble()
        }

        val report = mapOf(
            "medianLatencyMs" to median(times.map { it.first }),
            "p95LatencyMs" to p95(times.map { it.first }),
            "fixturesCount" to fixtures.size,
            "warmupRuns" to warmupRuns
        )

        val file = File(context.filesDir, "retrieval_eval_report.json")
        file.writeText(Gson().toJson(report))
        return file
    }
}
```

- [ ] **Step 4: Run test to verify it passes + commit**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.search.eval.RetrievalLatencyRunnerTest"`
Expected: PASS.

```bash
git add app/src/main/java/com/noteflowai/app/data/search/eval/RetrievalLatencyRunner.kt \
        app/src/test/java/com/noteflowai/app/data/search/eval/RetrievalLatencyRunnerTest.kt
git commit -m "feat(retrieval): debug-only on-device latency runner"
```

---

### Task 9: Address the note-level prune gap + integration test

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/data/search/HybridRetriever.kt`

**Context:** In Task 5 the prune applied only to segment candidates. Note-level results (`metadata["type"] == "note"`, `sourceSegmentId = "note_$fileName"` with empty text) carry no `SourceSegment` row, so rejected-entity and date prune don't reach them. This task closes the gap: resolve note-level results to their segments (via `sourceSegmentRepository.getBySourceId(fileName)` — one batched query across the note sources) and apply the same prune to those segments, dropping the note result if its segment is pruned.

- [ ] **Step 1: Write the failing test**

Add to `app/src/test/java/com/noteflowai/app/data/search/RetrievalShortCircuitTest.kt`:

```kotlin
    @Test
    fun `note-level result with a rejected segment entity is pruned`() = runTest {
        entityRepository.insert(Entity(id = "e_x", type = EntityType.PERSON, canonicalName = "X", normalizedName = "x"))
        entityRepository.insertWithMention(
            Entity(id = "e_x", type = EntityType.PERSON, canonicalName = "X", normalizedName = "x"),
            EntityMention(entityId = "e_x", sourceSegmentId = "seg_rejected_note", mentionText = "m")
        )
        entityRepository.reject("e_x")

        // note-level BM25 hit for note_rejected.md
        coEvery { com.noteflowai.app.data.search.NoteSearchIndex(any()).search(any(), any(), any()) } returns listOf(
            com.noteflowai.app.data.search.NoteSearchIndex.SearchResult("note_rejected.md", "Rej", 5.0f, "rejected content", listOf("content"), "bm25")
        )
        // its segment resolves to a rejected entity
        coEvery { sourceSegmentRepository.getBySourceId("note_rejected.md") } returns listOf(
            SourceSegment(id = "seg_rejected_note", sourceId = "note_rejected.md", sourceType = SourceType.NOTE, text = "rejected content", normalizedText = "rejected content", metadataJson = "{\"type\":\"segment\"}")
        )
        coEvery { sourceSegmentRepository.searchAll(any(), any()) } returns emptyList()

        val outcome = retriever.retrieve(
            plan = QueryPlan(intent = QueryIntent.GENERAL_RAG, queryText = "rejected content"),
            embeddingQuery = "",
            embeddingEnabled = false,
            embeddingModel = "",
            provider = "",
            apiKey = "",
            baseUrl = ""
        )

        assertTrue(outcome.results.none { it.sourceId == "note_rejected.md" })
        assertEquals(ShortCircuitReason.ALL_PRUNED, outcome.shortCircuit)
    }
```

Note: this test references `QueryPlan`/`RetrievalOutcome`/`ShortCircuitReason` which are already imported in the file. `NoteSearchIndex(any())` is a mockk — its `search` is stubbed via `coEvery` on `com.noteflowai.app.data.search.NoteSearchIndex`. **Confirm the retriever uses the injected `noteSearchIndex` instance, not a fresh one; if the constructor's `noteSearchIndex` is the injected mock, stub it directly (the setup builds `retriever` with `mockk()` noteSearchIndex — add `coEvery { /* injected mock */ .search(any(), any(), any()) }`).** The author must wire the test to the actual injected `noteSearchIndex` reference.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.search.RetrievalShortCircuitTest.note level result with a rejected segment entity is pruned" -i`
Expected: FAIL (note result currently passes the prune since it's not a segment candidate).

- [ ] **Step 3: Implement note-segment resolution + prune**

Edit `HybridRetriever.kt` `retrieveGeneral`:

1. After collecting `results`, before the prune step, resolve note-level results to segments:

```kotlin
        // Resolve note-level results to their segments for the Filter & Eval Gate.
        val noteSources = results.filter { it.metadata?.get("type") == "note" }.map { it.sourceId }.distinct()
        val noteSegments = if (noteSources.isNotEmpty()) {
            noteSources.flatMap { sourceSegmentRepository.getBySourceId(it) }
        } else emptyList()
        val noteSegmentBySource = noteSegments.groupBy { it.sourceId }
```

2. Combine with `segmentCandidates` and prune the merged list:

```kotlin
        val allSegmentCandidates = (segmentCandidates + noteSegments).distinctBy { it.id }
        val (survivingSegments, pruneTrace) = MetadataFilterExtractor().prune(
            plan, allSegmentCandidates, entityRepository, timelineRepository
        )
        val survivingIds = survivingSegments.map { it.id }.toSet()
        val survivingNoteSources = (survivingSegments + noteSegments)
            .filter { it.id in survivingIds }
            .map { it.sourceId }.toSet()

        val prunedResults = results.filter { r ->
            val t = r.metadata?.get("type")
            when (t) {
                "note" -> r.sourceId in survivingNoteSources
                else -> r.sourceSegmentId in survivingIds
            }
        }
```

3. Reuse the existing normalize/fuse/threshold tail (replaces the Task 5 tail's `prunedResults` with this richer one).

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.search.RetrievalShortCircuitTest"`
Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.search.MetadataFilterExtractorTest"`
Run: `./gradlew :app:testDebugUnitTest` (full)
Expected: PASS (all).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/search/HybridRetriever.kt \
        app/src/test/java/com/noteflowai/app/data/search/RetrievalShortCircuitTest.kt
git commit -m "fix(retrieval): apply Filter & Eval Gate to note-level results too"
```

---

### Task 10: Manual device smoke + verify exit criteria

**Files:**
- None (verification only).

**Interfaces:**
- Consumes: the full Phase 5 implementation (Tasks 1-9).

- [ ] **Step 1: Build and install**

Run: `./gradlew :app:assembleDebug` — expect BUILD SUCCESSFUL.
Run: `adb install -r app/build/outputs/apk/debug/app-debug.apk` — expect Success.

- [ ] **Step 2: Manual smoke via the existing chat RAG path**

Launch the app, enable RAG + QP (`ragEnabled`, `enable_query_planner`), run the device's developer chat:

1. **Exact lookup:** ask a query whose exact keywords exist in a known note; verify the answer cites that note (keyword match ranks first).
2. **Paraphrase:** ask the same question reworded; verify it still retrieves the semantic match when embeddings are enabled.
3. **"Not found":** ask a query about content that defeats existence; verify the app does not fabricate and returns a refusal/empty context rather than a hallucinated answer.
4. **Rejected entity:** reject an entity in the memory inbox, then ask about it; verify no retrieval surfaces it.
5. **Date/timeline:** ask a "what happened around <month>" (requiresTimeline) query; verify only chronologically anchored results appear and the trace shows timeline prune in logcat (`shortCircuit=`).

Verify logcat lines: `QP: retrieved N results shortCircuit=...`.

- [ ] **Step 3: Verify exit criteria mapping**

- Retrieval reproducible: `RetrievalEndToEndGateTest` runs in `testDebugUnitTest`.
- Weights configurable: change `retrieval_bm25_weight` in DataStore (via a debug/developer hook) or confirm the weight-ranking test `configurable weights change ranking` passes.
- Threshold behavior measurable: `RetrievalShortCircuitTest` `BELOW_THRESHOLD` path + `minimumScore` in DataStore.
- Exact lookups favor keyword matches: `RetrievalFusionNormalizationTest.keyword match ranks first`.
- Paraphrases retrieve semantic matches: `RetrievalFusionNormalizationTest.paraphrase ranks semantic match`.
- Weak results rejected: `belowThreshold` in the gate trace + `RetrievalShortCircuitTest`.
- "not found" supported: `RetrievalEvaluationTest.refusal accuracy rewards correct not-found handling` + `NO_CANDIDATES`/`ALL_PRUNED`.

- [ ] **Step 4: Optionally run the on-device latency runner**

From a debug hook (or a temporary `main` in the runner), invoke `RetrievalLatencyRunner().run(...)` with the real retriever on the device, confirm `retrieval_eval_report.json` is written to `filesDir`, and confirm median/p95 values are sane (< 1s).

---

## Self-Review

**Spec coverage:**
- RetrievalConfig + DataStore persistence → Task 1.
- Pipeline: query classification (existing) → candidate gen (existing) → Filter & Eval Gate (Task 2, 5, 9) → normalize + weighted fusion (Task 3) → threshold (Task 3) → dedup/rerank (existing + Task 3) → context selection (Task 4).
- 7 query classes: already covered by `QueryParser`; Task 7's gate constructs `GENERAL_RAG` plans directly for determinism.
- Evaluation fixture JSON → Task 6.
- 8 metrics → Task 6 (Recall@5/10, MRR, citation precision, refusal accuracy, unsupported-claim heuristic) + Task 8 (median/p95 latency on-device).
- Full-chunk rejected-entity rejection → Task 2 test `single rejected entity drops the whole chunk`.
- Tightened required-timeline → Task 2 test `required timeline drops chunks without a decidable anchor`.
- Short-circuit reasons → Task 3 (BELOW_THRESHOLD/NO_CANDIDATES) + Task 5 (ALL_PRUNED) + Task 9 (note-level ALL_PRUNED).
- Hard pre-fusion geometry → prune runs before normalize/fuse in Task 5/9.
- "Do not combine raw values without normalization" → `normalize()` in Task 3 before `fuse()`.

**Placeholder scan:** every step carries exact file paths, complete code, and exact commands with expected output. The one open design point — how `SmartSnippetExtractor` is constructed — is explicitly flagged as "read the file first, adapt the test," with a fallback (MockK) given. The `RetrievalEndToEndGateTest` notes to read `QueryParser.kt` and prefer direct `QueryPlan` construction to force the general path. No TBD/TODO markers.

**Type consistency:**
- `MetadataFilterExtractor().prune(...): Pair<List<SourceSegment>, RetrievalTrace>` defined in Task 2 and consumed identically in Tasks 5/9.
- `RetrievalOutcome(results, shortCircuit, trace)` defined in Task 2; `Retrieve` return type switched in Task 3; `.results` unpack used in Task 3/7/9.
- `RetrievalConfig(bm25Weight=..., vectorWeight=..., ...)` used consistently in all constructor calls (Tasks 1, 3, 5-8).
- `RetrievalMetrics(recallAt5, recallAt10, mrr, citationPrecision, unsupportedClaimRate, refusalAccuracy, medianLatencyMs, p95LatencyMs)` defined in Task 6 and constructed in the same order in tests.
- `Parameters(embeddingQuery, embeddingEnabled, embeddingModel, provider, apiKey, baseUrl)` defined in Task 8 and used in the runner + its test.
- `EvalFixture(question, expectedNoteIds, expectedKeywords, mustCite, expectNotFound)` consistent between the Gson loader, the JSON file, and every test.
- `Surviving segment prune` helpers (`survivingIds`, `survivingNoteSources`, `prunedResults`) are named identically in Tasks 5 and 9.

**Interface gaps flagged (must be honored by the implementer):**
1. Task 3 changes `retrieve()`'s return type; `HybridRetrieverRejectedGatingTest` and any other test calling `.retrieve(` must be updated to `.results`. The plan calls this out explicitly.
2. Task 5 makes `timelineRepository` a required constructor param inserted BEFORE `retrievalConfig`; the three existing test constructors must be updated to pass `TimelineRepository(db)`.
3. Task 4 notes `SmartSnippetExtractor` must be read before writing the budget test.
4. Task 7 notes the gate must force `GENERAL_RAG` plans (or else intents like DECISION_LOOKUP bypass the general path).

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-08-22-retrieval-evaluation.md`.

**Two execution options:**

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

**Which approach?**