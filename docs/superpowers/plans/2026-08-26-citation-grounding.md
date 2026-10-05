# Phase 6: Citation and Grounding Enforcement — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every user-data claim in a grounded chat answer trace back to a specific, valid, openable source passage, enforced by a two-phase (pre-call / post-call) pipeline with guide-literal citations and a superscript + footer chat UI.

**Architecture:** Replace the Phase 5 weak grounding stack (`CitationValidator` + word-overlap entailment + a detector that owns terminal state) with the guide schema: top-level `citations: [Citation]`, claims referencing citations by `warning: no one has approved this yet`. A `GroundingPromptBuilder` injects a numbered evidence list with an exact `[N]` marker contract; the reconciler resolves citations to retrieval + DB rows; `ClaimValidator` runs embedding-cosine (>= 0.58) OR quote-presence entailment, fail-closed; `GroundingDispositionResolver` is the single owner of the terminal state. Phase A (T1/T2/T3) refuses before the LLM call; Phase B (post-call) parses, reconciles, validates, and resolves.

**Tech Stack:** Kotlin, Compose Material3 (BOM 2024.02.01 → Compose UI 1.6.x), Room (DB currently v7), Gson, OnDeviceEmbedder (multilingual-MiniLM-L12-v2, 384-d), Robolectric + MockK + kotlinx-coroutines-test for unit tests, gradle test runner `:app:testDebugUnitTest`.

## Global Constraints

These are binding. Every task's requirements implicitly include this section. Copy values verbatim; do not change thresholds or names.

- Entailment: `embeddingCosine(claim, chunkText) >= EMBED_COSINE_FLOOR (0.58f)` OR `quotePresence(chunkText, claim)`. Quote presence = `>= QUOTE_MIN_CHARS (12)` contiguous chars OR `>= 70%` of claim content tokens overlap the chunk. Embedder unavailable (`embed` returns null / `isReady()` false) → the entailment check FAILS CLOSED (never auto-validates).
- Quote offsets are UTF-16 code-unit indices into the chunk text (match Kotlin `String.substring` semantics; emoji/CJK-safe per code unit). Advisory model offsets are validated `(0 <= start < end <= text.length)`; out-of-range/inverted → ignored, app computes fallback span.
- `GroundingDispositionResolver` is the SINGLE owner of the terminal state and the RETRY-vs-terminal decision. `UnsupportedClaimDetector` only flags whether any claim is unsupported and builds the retry prompt's content; it NEVER emits a disposition.
- Terminal dispositions (`FULLY_VALIDATED`, `UNVERIFIED`, `ABSTAIN`, `HOLE_IN_EVIDENCE`, `UNRESPONSIVE`) are mutually exclusive by construction of the sealed type. `abstained`/`needs_clarification` are mutually exclusive: the resolver normalizes the envelope so only the chosen one is true.
- Trigger 4 (HOLE_IN_EVIDENCE) is response-level ONLY: `any claim.uncertainty == HIGH` AND zero claims have a valid quote range. NO query-intent classifier.
- Reconciler is NON-DESTRUCTIVE: a citation that fails resolution is KEPT with `resolved=null`/`authoritative=false`; it is never silently dropped.
- Prompt contract: `GroundingPromptBuilder.buildGroundingPrompt` injects evidence as a NUMBERED list (`1. [chunkId=…] [sourceId=…] "excerpt"`, 1-based `N`). The model MUST use `[N]` markers exactly; `citations[].id` MUST be `cite-<N>` matching item N. Parser resolves `[N]` → `cite-N` by regex `\[(\d+)\]`.
- Reload source-of-truth precedence: reconstruct citation UI from `ChatMessage.groundedResponse` (full envelope) FIRST; hydrate footer excerpt text from `answer_citations.quoteText` SECOND. Never the reverse.
- Retry prompt MUST contain the specific failure reason (e.g. the literal string `Chunk 'seg-4' not in retrieved context (V2)`, formatted as `Citation [2] chunkId 'seg-4' not in retrieved context`), never a generic "fix your citations".
- Room DB is at **version 7**. Phase 6 raises it to **version 8** with exactly one `ALTER TABLE answer_citations ADD COLUMN quoteText TEXT`. Migration `MIGRATION_7_8` registered. Do not bump version elsewhere.
- Gson bypasses constructors (documented at `AiChatModels.kt:18`). Models carry defaults, never invariants enforced in `init`; all invariants enforced in validator/resolver layer.
- Retrieval is untouched: `RetrievalResult` constructor (14 fields incl. per-channel scores), `RetrievalConfig` defaults (`bm25=0.4, vector=0.4, entity=0.12, recency=0.08, topK=40, minimumScore=0.28, maxContextTokens=1000`), 13-param `HybridRetriever` ctor — do not change. `lastRetrievalResults` is the private `List<RetrievalResult>` var set inside `buildFinalSystemPrompt` (MainViewModel L3303).
- Feature flag: `enable_grounded_memory_chat` (FeatureFlags `ENABLE_GROUNDED_MEMORY_CHAT`, default **false**) gates the whole pipeline; `settingsManager.enableGroundedMemoryChatBlocking` (admits current value at init). No new flag.
- Existing test conventions: Robolectric `@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])`, MockK for DAO/repo mocks (`coEvery { dao.insertAll(any()) } returns Unit`), `kotlinx.coroutines.test.runTest`. Follow `CitationValidatorTest.kt` / `UnsupportedClaimDetectorTest.kt` in `app/src/test/java/com/noteflowai/app/data/chat/`.
- No emojis, no em-dashes in strings or docs.

---

## File Structure

**New files (main):**
- `app/src/main/java/com/noteflowai/app/data/chat/GroundingSupport.kt` — stateless utilities (`QuoteRange`, `computeQuoteSpan`, `checkQuotePresence`, `findQuoteWindow`, `noteBlockIndexFromSegmentId`).
- `app/src/main/java/com/noteflowai/app/data/chat/GroundedResponseParser.kt` — `ParseResult` (Success/Failure), lenient SourceType deserializer.
- `app/src/main/java/com/noteflowai/app/data/chat/CitationReconciler.kt` — `ReconciledCitation` + resolution (V2/V3/V4).
- `app/src/main/java/com/noteflowai/app/data/chat/ClaimValidator.kt` — replaces `CitationValidator`; V1/V5 + memory channel + persistence.
- `app/src/main/java/com/noteflowai/app/data/chat/GroundingDispositionResolver.kt` — sealed `GroundingDisposition`; resolver owns terminal state + Trigger 4 + retry decision.
- `app/src/main/java/com/noteflowai/app/data/chat/GroundedChatPipeline.kt` — facade orchestrating parse→reconcile→validate→detect→resolve (testable end-to-end).
- `app/src/main/java/com/noteflowai/app/data/chat/CitationMarkupParser.kt` — `[N]` marker regex → citation reference map.

**Modified files (main):**
- `app/src/main/java/com/noteflowai/app/data/chat/GroundedChatModels.kt` — guide-literal `Citation`/`GroundedChatResponse`/`Claim`/`UncertaintyLevel`/`ValidatedClaim`/`ValidatedResponse`.
- `app/src/main/java/com/noteflowai/app/data/chat/UnsupportedClaimDetector.kt` — detection + specific retry prompt only; remove `analyze()` and old `GroundingDecision`.
- `app/src/main/java/com/noteflowai/app/data/chat/GroundingPromptBuilder.kt` — numbered evidence list + exact `[N]`/`cite-N` contract + new RESPONSE_SCHEMA.
- `app/src/main/java/com/noteflowai/app/data/memory/model/AnswerCitation.kt` — add `quoteText: String?`.
- `app/src/main/java/com/noteflowai/app/data/memory/db/MemoryDatabase.kt` — version 8 + `MIGRATION_7_8`.
- `app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt` — Phase A pre-call triggers; Phase B wiring (pipeline); initGroundedChat constructs new components; refusal message path.
- `app/src/main/java/com/noteflowai/app/ui/components/MarkdownRenderer.kt` — `[N]` superscript spans (clickable via `LinkAnnotation.Clickable`).
- `app/src/main/java/com/noteflowai/app/ui/screens/chat/ChatScreen.kt` — superscript render hookup, validated-citation footer chips, tap-to-open note.
- `app/src/main/java/com/noteflowai/app/ui/NoteFlowApp.kt` — ChatScreen gains `onOpenNote` that selects + navigates to `Screen.NOTE_DETAIL`.
- `app/src/main/res/values/strings.xml` — refusal templates + footer labels.

**Deleted files (main):**
- `app/src/main/java/com/noteflowai/app/data/chat/CitationValidator.kt` (replaced by ClaimValidator).

**Test files (all new unless noted):**
- `app/src/test/java/com/noteflowai/app/data/chat/GroundedChatModelsTest.kt`
- `app/src/test/java/com/noteflowai/app/data/chat/GroundingSupportTest.kt`
- `app/src/test/java/com/noteflowai/app/data/chat/GroundedResponseParserTest.kt`
- `app/src/test/java/com/noteflowai/app/data/chat/CitationReconcilerTest.kt`
- `app/src/test/java/com/noteflowai/app/data/chat/ClaimValidatorTest.kt`
- `app/src/test/java/com/noteflowai/app/data/chat/GroundingDispositionResolverTest.kt`
- `app/src/test/java/com/noteflowai/app/data/chat/UnsupportedClaimDetectorTest.kt` (rework existing)
- `app/src/test/java/com/noteflowai/app/data/chat/GroundedChatPipelineTest.kt`
- `app/src/test/java/com/noteflowai/app/data/chat/GroundingPromptBuilderTest.kt`
- `app/src/test/java/com/noteflowai/app/data/chat/CitationMarkupParserTest.kt`
- `app/src/test/java/com/noteflowai/app/data/memory/db/MemoryDatabaseMigrationV7ToV8Test.kt`

---

### Task 1: Guide-literal models (`GroundedChatModels.kt`)

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/data/chat/GroundedChatModels.kt` (whole file replaced)
- Test: `app/src/test/java/com/noteflowai/app/data/chat/GroundedChatModelsTest.kt` (new)

**Interfaces:**
- Produces: `GroundedChatResponse(answer, citations, claims, suggested_actions, needs_clarification, clarification_question, abstained, abstention_reason)`; `Citation(id, sourceType: SourceType, sourceId, chunkId, quoteStart: Int?, quoteEnd: Int?, relevanceScore: Float?)`; `Claim(text, citationIds, memory_object_ids, uncertainty: UncertaintyLevel, confidence: Float?)`; `enum UncertaintyLevel { LOW, MEDIUM, HIGH }`; `SuggestedAction(type: ActionType, label, payload)`; `enum ActionType { CREATE_NOTE, CREATE_TASK, CONFIRM_DECISION, OPEN_SOURCE, NONE }`; `ValidatedClaim(claim, isValid, validCitationIds, invalidCitationIds, validMemoryIds, invalidMemoryIds, hasValidQuoteRange, invalidCitationReasons: Map<String,String>, reason)`; `ValidatedResponse(response, validatedClaims, totalClaims, validClaims, unsupportedClaims)`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.noteflowai.app.data.chat

import com.google.gson.Gson
import com.noteflowai.app.data.memory.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundedChatModelsTest {

    @Test
    fun `top-level citations and claim citationIds survive Gson round-trip`() {
        val json = """
        {
          "answer": "The supplier was ACME Corp.",
          "citations": [
            {"id": "cite-1", "sourceType": "NOTE", "sourceId": "note.md",
             "chunkId": "note_note.md_block2_abc", "quoteStart": 0, "quoteEnd": 12, "relevanceScore": 0.81}
          ],
          "claims": [
            {"text": "The supplier was ACME Corp.", "citationIds": ["cite-1"],
             "uncertainty": "LOW", "confidence": 0.9}
          ]
        }
        """.trimIndent()

        val response = Gson().fromJson(json, GroundedChatResponse::class.java)

        assertEquals(1, response.citations.size)
        assertEquals(SourceType.NOTE, response.citations[0].sourceType)
        assertEquals("cite-1", response.citations[0].id)
        assertEquals("note.md", response.citations[0].sourceId)
        assertEquals(0, response.citations[0].quoteStart)
        assertEquals(12, response.citations[0].quoteEnd)
        assertEquals(1, response.claims.size)
        assertEquals(listOf("cite-1"), response.claims[0].citationIds)
        assertEquals(UncertaintyLevel.LOW, response.claims[0].uncertainty)
    }

    @Test
    fun `absent optional fields default to empty lists and LOW uncertainty`() {
        val json = """{"answer": "No claims here."}""".trimIndent()
        val response = Gson().fromJson(json, GroundedChatResponse::class.java)
        assertTrue(response.citations.isEmpty())
        assertTrue(response.claims.isEmpty())
        assertTrue(!response.abstained)
    }

    @Test
    fun `memory object ids stay a separate claim channel`() {
        val response = Gson().fromJson(
            """{"answer":"x","claims":[{"text":"t","memory_object_ids":["mem_1"]}]}""".trimIndent(),
            GroundedChatResponse::class.java
        )
        assertEquals(listOf("mem_1"), response.claims[0].memory_object_ids)
        assertTrue(response.claims[0].citationIds.isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.chat.GroundedChatModelsTest"`
Expected: FAIL — `GroundedChatResponse` has no `citations` field; `Claim` has no `citationIds`/`uncertainty`; `SourceType` import already exists but types mismatch.

- [ ] **Step 3: Write minimal implementation** — replace the whole file:

```kotlin
package com.noteflowai.app.data.chat

import com.noteflowai.app.data.memory.model.SourceType

/**
 * Phase 6: Guide-literal structured response models for grounded, evidence-first chat.
 *
 * Gson bypasses constructors (see AiChatModels.kt), so these models carry only
 * defaults, never init-time invariant checks. Invariants live in the validator/
 * resolver layer.
 */

/** Full structured response from the model. */
data class GroundedChatResponse(
    val answer: String,
    val citations: List<Citation> = emptyList(),
    val claims: List<Claim> = emptyList(),
    val suggested_actions: List<SuggestedAction> = emptyList(),
    val needs_clarification: Boolean = false,
    val clarification_question: String? = null,
    val abstained: Boolean = false,
    val abstention_reason: String? = null
)

/**
 * A first-class citation object (guide schema). quoteStart/quoteEnd are advisory
 * UTF-16 code-unit offsets into the resolved chunk text; the app recomputes the
 * canonical span via GroundingSupport.computeQuoteSpan.
 */
data class Citation(
    val id: String,
    val sourceType: SourceType,
    val sourceId: String,
    val chunkId: String,
    val quoteStart: Int? = null,
    val quoteEnd: Int? = null,
    val relevanceScore: Float? = null
)

/** A single factual claim; citations by id, memory references in a separate channel. */
data class Claim(
    val text: String,
    val citationIds: List<String> = emptyList(),
    val memory_object_ids: List<String> = emptyList(),
    val uncertainty: UncertaintyLevel = UncertaintyLevel.LOW,
    val confidence: Float? = null
)

enum class UncertaintyLevel { LOW, MEDIUM, HIGH }

/** An action the model suggests the user might want to take. */
data class SuggestedAction(
    val type: ActionType,
    val label: String,
    val payload: Map<String, Any> = emptyMap()
)

enum class ActionType { CREATE_NOTE, CREATE_TASK, CONFIRM_DECISION, OPEN_SOURCE, NONE }

/** A claim with validation status attached. */
data class ValidatedClaim(
    val claim: Claim,
    val isValid: Boolean,
    val validCitationIds: List<String>,
    val invalidCitationIds: List<String>,
    val validMemoryIds: List<String> = emptyList(),
    val invalidMemoryIds: List<String> = emptyList(),
    val hasValidQuoteRange: Boolean,
    /** citationId -> specific failure reason (for the retry prompt). */
    val invalidCitationReasons: Map<String, String> = emptyMap(),
    val reason: String? = null
)

/** The full response with validation results attached. */
data class ValidatedResponse(
    val response: GroundedChatResponse,
    val validatedClaims: List<ValidatedClaim>,
    val totalClaims: Int,
    val validClaims: Int,
    val unsupportedClaims: Int
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.chat.GroundedChatModelsTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/chat/GroundedChatModels.kt app/src/test/java/com/noteflowai/app/data/chat/GroundedChatModelsTest.kt
git commit -m "feat(chat): guide-literal citation models for Phase 6"
```

---

### Task 2: GroundingSupport utilities

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/chat/GroundingSupport.kt`
- Test: `app/src/test/java/com/noteflowai/app/data/chat/GroundingSupportTest.kt`

**Interfaces:**
- Produces: `data class QuoteRange(start: Int, end: Int)`; `fun computeQuoteSpan(text: String, advisoryStart: Int?, advisoryEnd: Int?): QuoteRange?`; `fun checkQuotePresence(text: String, claimText: String): Boolean`; `fun findQuoteWindow(text: String, claimText: String): QuoteRange?`; `fun noteBlockIndexFromSegmentId(segmentId: String): Int?`; `const EMBED_COSINE_FLOOR = 0.58f`; `const QUOTE_MIN_CHARS = 12`; `const QUOTE_TOKEN_RATIO = 0.7f`.
- Consumes: nothing (pure). Constants consumed by `ClaimValidator` (Task 5) and `GroundingDispositionResolver` (Task 8).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.noteflowai.app.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundingSupportTest {

    @Test
    fun `valid advisory offsets produce a quote range`() {
        val range = GroundingSupport.computeQuoteSpan("hello world this is a long-enough chunk", 6, 11)
        assertNotNull(range)
        assertEquals(6, range.start)
        assertEquals(11, range.end)
    }

    @Test
    fun `out-of-range or inverted advisory offsets are rejected`() {
        assertNull(GroundingSupport.computeQuoteSpan("abc", 0, 99))
        assertNull(GroundingSupport.computeQuoteSpan("abc", 9, 2))
        assertNull(GroundingSupport.computeQuoteSpan("abc", -1, 2))
        assertNull(GroundingSupport.computeQuoteSpan("abc", 2, 2))
        assertNull(GroundingSupport.computeQuoteSpan("abc", null, null))
    }

    @Test
    fun `contiguous quote of at least QUOTE_MIN_CHARS passes`() {
        assertTrue(GroundingSupport.checkQuotePresence(
            "The supplier was ACME Corporation, based in Berlin.",
            "The supplier was ACME Corporation"
        ))
    }

    @Test
    fun `short string passes via 70 percent content-token overlap`() {
        // claim has 4 content tokens; chunk contains all 4 -> ratio 1.0 >= 0.7
        assertTrue(GroundingSupport.checkQuotePresence(
            "ACME Corporation is the supplier and the price quote.",
            "ACME Corporation supplier quote"
        ))
    }

    @Test
    fun `claim sharing few tokens fails`() {
        assertFalse(GroundingSupport.checkQuotePresence(
            "The weather in Berlin is rainy today.",
            "The supplier was a completely unrelated company"
        ))
    }

    @Test
    fun `findQuoteWindow locates the first content token in the chunk`() {
        val range = GroundingSupport.findQuoteWindow(
            "The supplier was ACME Corporation, based in Berlin.",
            "ACME Corporation"
        )
        assertNotNull(range)
        assertTrue(range.start in 0..range.end)
        assertTrue(range.end <= "The supplier was ACME Corporation, based in Berlin.".length)
    }

    @Test
    fun `note block index parsed from segment id`() {
        assertEquals(2, GroundingSupport.noteBlockIndexFromSegmentId("note_meeting_block2_abc"))
        assertEquals(0, GroundingSupport.noteBlockIndexFromSegmentId("note_x_block0_uuid"))
        assertNull(GroundingSupport.noteBlockIndexFromSegmentId("seg_123"))
    }

    @Test
    fun `unicode code units counted per utf-16 (emoji safe length)`() {
        // "🎉abc" length is 5 code units (surrogate pair + 3 chars)
        val text = "🎉abc"
        assertEquals(5, text.length)
        val span = GroundingSupport.computeQuoteSpan(text, 2, 5)
        assertNotNull(span)
        assertEquals("abc", text.substring(span.start, span.end))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.chat.GroundingSupportTest"`
Expected: FAIL — `GroundingSupport` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.noteflowai.app.data.chat

/**
 * Phase 6: Stateless grounding helpers. All offsets are UTF-16 code units into
 * the segment text (match Kotlin String.substring semantics; emoji/CJK-safe per
 * code unit).
 */
object GroundingSupport {

    /** Minimum embedding cosine for semantic entailment (fail-closed below). */
    const val EMBED_COSINE_FLOOR = 0.58f

    /** Minimum contiguous-quote length (code units) to count as quote presence. */
    const val QUOTE_MIN_CHARS = 12

    /** Minimum fraction of claim content tokens present in the chunk to pass via overlap. */
    const val QUOTE_TOKEN_RATIO = 0.7f

    private val NON_ALNUM = Regex("[^a-z0-9\\s]")
    private val WHITESPACE = Regex("\\s+")

    data class QuoteRange(val start: Int, val end: Int)

    /**
     * Canonical quote span. Prefers valid advisory offsets; otherwise returns null
     * (callers may fall back to [findQuoteWindow] for a token-window quote).
     */
    fun computeQuoteSpan(text: String, advisoryStart: Int?, advisoryEnd: Int?): QuoteRange? {
        if (advisoryStart != null && advisoryEnd != null &&
            advisoryStart >= 0 && advisoryEnd <= text.length && advisoryStart < advisoryEnd
        ) {
            return QuoteRange(advisoryStart, advisoryEnd)
        }
        return null
    }

    /**
     * Quote-presence entailment channel: a contiguous mention of the claim text
     * (>= QUOTE_MIN_CHARS) OR >= QUOTE_TOKEN_RATIO of the claim's content tokens
     * present in the chunk.
     */
    fun checkQuotePresence(text: String, claimText: String): Boolean {
        val trimmedClaim = claimText.trim()
        if (trimmedClaim.length >= QUOTE_MIN_CHARS && text.contains(trimmedClaim)) return true

        val claimTokens = contentTokens(trimmedClaim)
        val chunkTokens = contentTokens(text)
        if (claimTokens.isEmpty() || chunkTokens.isEmpty()) return false

        val overlap = claimTokens.intersect(chunkTokens).size
        return overlap.toFloat() / claimTokens.size >= QUOTE_TOKEN_RATIO
    }

    /** First contiguous window in [text] covering the claim's content tokens, else null. */
    fun findQuoteWindow(text: String, claimText: String): QuoteRange? {
        val claimTokens = contentTokens(claimText)
        val firstToken = claimTokens.firstOrNull() ?: return null
        val lower = text.lowercase()
        val index = lower.indexOf(firstToken)
        if (index < 0) return null
        return QuoteRange(index, (index + firstToken.length).coerceAtMost(text.length))
    }

    /** Content tokenizer: lowercase, strip non-alnum, keep words longer than 3 chars. */
    fun contentTokens(text: String): Set<String> =
        text.lowercase()
            .replace(NON_ALNUM, "")
            .split(WHITESPACE)
            .filter { it.length > 3 }
            .toSet()

    /**
     * Map a NOTE segment id back to its paragraph index: `note_<file>_block<index>_<uuid>`.
     * Returns null for non-note segment ids.
     */
    fun noteBlockIndexFromSegmentId(segmentId: String): Int? {
        val match = Regex("block(\\d+)").find(segmentId) ?: return null
        return match.groupValues[1].toIntOrNull()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.chat.GroundingSupportTest"`
Expected: PASS (all 8 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/chat/GroundingSupport.kt app/src/test/java/com/noteflowai/app/data/chat/GroundingSupportTest.kt
git commit -m "feat(chat): grounding support utilities (quote spans, entailment, block index)"
```

---

### Task 3: GroundedResponseParser

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/chat/GroundedResponseParser.kt`
- Test: `app/src/test/java/com/noteflowai/app/data/chat/GroundedResponseParserTest.kt`

**Interfaces:**
- Produces: `object GroundedResponseParser { fun parse(json: String): ParseResult }`; `sealed class ParseResult { Success(GroundedChatResponse) / Failure(reason: String) }`.
- Consumes: models from Task 1 (`GroundedChatResponse`, `Citation`), `SourceType`.
- Notes: lenient `SourceType` deserializer maps unknown/absent type strings to `SourceType.NOTE` (the reconciler's resolved `RetrievalResult` is the authoritative type); blank answer → Failure.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.noteflowai.app.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundedResponseParserTest {

    @Test
    fun `well-formed envelope parses to Success`() {
        val result = GroundedResponseParser.parse(
            """{"answer":"ok","citations":[{"id":"cite-1","sourceType":"NOTE","sourceId":"n.md","chunkId":"c1"}]}"""
        )
        assertTrue(result is ParseResult.Success)
        val success = result as ParseResult.Success
        assertEquals("ok", success.response.answer)
        assertEquals("cite-1", success.response.citations[0].id)
    }

    @Test
    fun `malformed json produces Failure not a crash`() {
        val result = GroundedResponseParser.parse("{ not json !!")
        assertTrue(result is ParseResult.Failure)
    }

    @Test
    fun `blank answer produces Failure`() {
        assertTrue(GroundedResponseParser.parse("{}") is ParseResult.Failure)
        assertTrue(GroundedResponseParser.parse("") is ParseResult.Failure)
    }

    @Test
    fun `absent answer produces Failure`() {
        assertTrue(GroundedResponseParser.parse("""{"claims":[]}""".trimIndent()) is ParseResult.Failure)
    }

    @Test
    fun `unknown sourceType string does not crash, defaults to NOTE`() {
        val result = GroundedResponseParser.parse("""{"answer":"a","citations":[{"id":"c","sourceType":"BOGUS","sourceId":"s","chunkId":"k"}]}""")
        assertTrue(result is ParseResult.Success)
        assertEquals(com.noteflowai.app.data.memory.model.SourceType.NOTE, (result as ParseResult.Success).response.citations[0].sourceType)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.chat.GroundedResponseParserTest"`
Expected: FAIL — `GroundedResponseParser` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.noteflowai.app.data.chat

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.noteflowai.app.data.memory.model.SourceType
import java.lang.reflect.Type

/** Result of parsing the model's answer body; Failure never crashes the pipeline. */
sealed class ParseResult {
    data class Success(val response: GroundedChatResponse) : ParseResult()
    data class Failure(val reason: String) : ParseResult()
}

object GroundedResponseParser {

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(SourceType::class.java, LenientSourceTypeDeserializer)
        .create()

    fun parse(json: String): ParseResult {
        if (json.isBlank()) return ParseResult.Failure("Empty or blank answer body")
        return try {
            val parsed = gson.fromJson(json, GroundedChatResponse::class.java)
            if (parsed == null || parsed.answer.isBlank()) {
                ParseResult.Failure("Answer field missing or blank")
            } else {
                ParseResult.Success(parsed)
            }
        } catch (e: Exception) {
            ParseResult.Failure("Malformed structured response: ${e.message}")
        }
    }

    /** Lenient: an unknown or missing sourceType string maps to NOTE (advisory only). */
    private object LenientSourceTypeDeserializer : JsonDeserializer<SourceType> {
        override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): SourceType {
            if (!json.isJsonPrimitive) return SourceType.NOTE
            return runCatching { json.asString }
                .getOrNull()
                ?.let { name -> SourceType.entries.firstOrNull { it.name == name } }
                ?: SourceType.NOTE
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.chat.GroundedResponseParserTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/chat/GroundedResponseParser.kt app/src/test/java/com/noteflowai/app/data/chat/GroundedResponseParserTest.kt
git commit -m "feat(chat): strict-but-safe grounded response parser"
```

---

### Task 4: CitationReconciler

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/chat/CitationReconciler.kt`
- Test: `app/src/test/java/com/noteflowai/app/data/chat/CitationReconcilerTest.kt`

**Interfaces:**
- Consumes: `Citation`/`GroundedChatResponse` (Task 1), `GroundingSupport.noteBlockIndexFromSegmentId`/`computeQuoteSpan` (Task 2), `com.noteflowai.app.data.search.RetrievalResult`, `com.noteflowai.app.data.search.SourceSegmentRepository` (`suspend fun getById(id): SourceSegment?`), `com.noteflowai.app.data.memory.model.SourceSegment`.
- Produces: `data class ReconciledCitation(citation: Citation, resolved: RetrievalResult?, segment: SourceSegment?, computedQuoteRange: GroundingSupport.QuoteRange?, authoritative: Boolean, conflictNote: String? = null)`; `class CitationReconciler(sourceSegmentRepository: SourceSegmentRepository)` with `suspend fun reconcile(response: GroundedChatResponse, retrievalResults: List<RetrievalResult>): List<ReconciledCitation>`.
- Authority rules (spec V2/V3-consistency/V4): non-authoritative when (a) chunkId absent from retrieval, or (b) `citation.sourceId != resolved.sourceId`, or (c) no persistent SourceSegment row AND the chunkId is not note_-block mappable. Note_-prefixed ids (`note_<file>_block<index>_<uuid>` — block index parseable) are considered persistent via the computed block mapping (spec V4); a missing segment row alone does not invalidate them. `conflictNote` stays null in Phase B (T3 conflict detection is Phase A only, Task 10); the field is reserved by the spec schema.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.noteflowai.app.data.chat

import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType
import com.noteflowai.app.data.search.RetrievalResult
import com.noteflowai.app.data.search.SourceSegmentRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CitationReconcilerTest {

    private val segmentRepo = mockk<SourceSegmentRepository>()

    private fun citation(id: String, chunkId: String, sourceId: String, quoteStart: Int? = null, quoteEnd: Int? = null) =
        Citation(id = id, sourceType = SourceType.NOTE, sourceId = sourceId, chunkId = chunkId, quoteStart = quoteStart, quoteEnd = quoteEnd)

    private fun retrieval(chunkId: String, sourceId: String, text: String, score: Float = 0.9f) =
        RetrievalResult(sourceSegmentId = chunkId, sourceId = sourceId, text = text, score = score, rank = 0)

    private val response = GroundedChatResponse("answer", citations = listOf(
        citation("cite-1", "seg-1", "note.md", 0, 7),
        citation("cite-2", "seg-missing", "note.md"),
        citation("cite-3", "seg-wrong-source", "other.md"),
        citation("cite-4", "note_note.md_block2_abc", "note.md"),
        citation("cite-5", "seg-1", "note.md", 900, 5) // inverted advisory range
    ))

    @Test
    fun `authoritative when chunk retrieved, source matches, and segment row exists`() = runTest {
        coEvery { segmentRepo.getById("seg-1") } returns SourceSegment(id = "seg-1", sourceId = "note.md", text = "abcdefg", sourceType = SourceType.NOTE)
        val rec = CitationReconciler(segmentRepo).reconcile(response, listOf(retrieval("seg-1", "note.md", "abcdefg")))
            .first { it.citation.id == "cite-1" }
        assertTrue(rec.authoritative)
        assertEquals("seg-1", rec.resolved?.sourceSegmentId)
        assertNotNull(rec.segment)
        assertEquals(0, rec.computedQuoteRange?.start)
        assertEquals(7, rec.computedQuoteRange?.end)
        assertNull(rec.conflictNote)
    }

    @Test
    fun `chunk missing from retrieval is not authoritative`() = runTest {
        val rec = CitationReconciler(segmentRepo).reconcile(response, listOf(retrieval("seg-1", "note.md", "abcdefg")))
            .first { it.citation.id == "cite-2" }
        assertFalse(rec.authoritative)
        assertNull(rec.resolved)
        assertNull(rec.segment)
        assertNull(rec.computedQuoteRange)
    }

    @Test
    fun `source id mismatch is not authoritative`() = runTest {
        coEvery { segmentRepo.getById("seg-wrong-source") } returns SourceSegment(id = "seg-wrong-source", sourceId = "other.md", text = "abcdefg", sourceType = SourceType.NOTE)
        val rec = CitationReconciler(segmentRepo).reconcile(response, listOf(retrieval("seg-wrong-source", "other.md", "abcdefg")))
            .first { it.citation.id == "cite-3" }
        assertFalse(rec.authoritative)
    }

    @Test
    fun `note block id without segment row stays authoritative via block mapping`() = runTest {
        coEvery { segmentRepo.getById("note_note.md_block2_abc") } returns null
        val rec = CitationReconciler(segmentRepo).reconcile(response, listOf(retrieval("note_note.md_block2_abc", "note.md", "abcdefg")))
            .first { it.citation.id == "cite-4" }
        assertTrue(rec.authoritative)
        assertNull(rec.segment)
    }

    @Test
    fun `inverted advisory offsets produce null quote range`() = runTest {
        coEvery { segmentRepo.getById("seg-1") } returns SourceSegment(id = "seg-1", sourceId = "note.md", text = "abcdefg", sourceType = SourceType.NOTE)
        val rec = CitationReconciler(segmentRepo).reconcile(response, listOf(retrieval("seg-1", "note.md", "abcdefg")))
            .first { it.citation.id == "cite-5" }
        assertNull(rec.computedQuoteRange)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.chat.CitationReconcilerTest"`
Expected: FAIL — `CitationReconciler` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.noteflowai.app.data.chat

import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.search.RetrievalResult
import com.noteflowai.app.data.search.SourceSegmentRepository

/**
 * A citation after resolution against the retrieval context and the segment DB.
 * A failed resolution is KEPT (resolved=null) and marked non-authoritative -
 * it is never silently dropped (spec: non-destructive reconciliation).
 */
data class ReconciledCitation(
    val citation: Citation,
    val resolved: RetrievalResult?,
    val segment: SourceSegment?,
    val computedQuoteRange: GroundingSupport.QuoteRange?,
    val authoritative: Boolean,
    val conflictNote: String? = null
)

class CitationReconciler(
    private val sourceSegmentRepository: SourceSegmentRepository
) {
    suspend fun reconcile(
        response: GroundedChatResponse,
        retrievalResults: List<RetrievalResult>
    ): List<ReconciledCitation> {
        val byChunkId = retrievalResults.associateBy { it.sourceSegmentId }
        return response.citations.map { citation ->
            val resolved = byChunkId[citation.chunkId]
            val segment = resolved?.let { sourceSegmentRepository.getById(it.sourceSegmentId) }
            val blockMappable = GroundingSupport.noteBlockIndexFromSegmentId(citation.chunkId) != null
            val authoritative = when {
                resolved == null -> false                                    // V2: chunk not retrieved
                citation.sourceId != resolved.sourceId -> false              // V3-consistency
                segment == null && !blockMappable -> false                   // V4: no persistent segment
                else -> true
            }
            val computedQuoteRange = resolved?.let {
                GroundingSupport.computeQuoteSpan(it.text, citation.quoteStart, citation.quoteEnd)
            }
            ReconciledCitation(
                citation = citation,
                resolved = resolved,
                segment = segment,
                computedQuoteRange = computedQuoteRange,
                authoritative = authoritative
            )
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.chat.CitationReconcilerTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/chat/CitationReconciler.kt app/src/test/java/com/noteflowai/app/data/chat/CitationReconcilerTest.kt
git commit -m "feat(chat): citation reconciler resolving claims against retrieval context"
```

---

### Task 5: ClaimValidator (replaces CitationValidator)

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/chat/ClaimValidator.kt`
- Delete: `app/src/main/java/com/noteflowai/app/data/chat/CitationValidator.kt` and `app/src/test/java/com/noteflowai/app/data/chat/CitationValidatorTest.kt`
- Test: `app/src/test/java/com/noteflowai/app/data/chat/ClaimValidatorTest.kt`

> NOTE: `MainViewModel` still references `citationValidator`/`unsupportedClaimDetector` fields (L153-160) and will not compile until Task 11 rewrites `initGroundedChat` and the chat loop. That is expected. Run only the targeted test class in Steps 2/4, not the full suite, until Task 11 lands.

**Interfaces:**
- Consumes: Task 1 models (`ValidatedClaim`, `ValidatedResponse`, `UncertaintyLevel`), `ReconciledCitation` (Task 4), `GroundingSupport` constants/methods (Task 2), `com.noteflowai.app.data.search.OnDeviceEmbedder` (`suspend fun embed(text): FloatArray?`, `fun isReady(): Boolean`), `com.noteflowai.app.data.memory.dao.AnswerCitationDao` (`insertAll(List<AnswerCitation>)`), `com.noteflowai.app.data.memory.model.AnswerCitation` (has `quoteText: String?` from Task 9 — the column ships in Task 9 but the field is additive, write it now), `com.noteflowai.app.data.search.RetrievalResult`.
- Produces: `class ClaimValidator(answerCitationDao: AnswerCitationDao, embedder: OnDeviceEmbedder, sourceNoteExists: (String) -> Boolean)` with `suspend fun validate(response: GroundedChatResponse, reconciled: List<ReconciledCitation>, retrievalResults: List<RetrievalResult>, answerId: String): ValidatedResponse`. Also persists one `AnswerCitation` row per citation reference (status VALIDATED/INVALID, `quoteText` excerpt when determinable). `ValidatedClaim.invalidCitationReasons[citationId]` values MUST be the literal specific failure strings consumed by the Task 6 retry prompt, e.g. `chunkId 'seg-4' not in retrieved context`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.noteflowai.app.data.chat

import com.noteflowai.app.data.memory.dao.AnswerCitationDao
import com.noteflowai.app.data.memory.model.AnswerCitation
import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType
import com.noteflowai.app.data.search.OnDeviceEmbedder
import com.noteflowai.app.data.search.RetrievalResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaimValidatorTest {

    private val dao = mockk<AnswerCitationDao>(relaxed = true)
    private val embedder = mockk<OnDeviceEmbedder>()

    private fun retrieval(chunkId: String, sourceId: String, text: String) =
        RetrievalResult(sourceSegmentId = chunkId, sourceId = sourceId, text = text, score = 0.9f, rank = 0)

    private fun reconciled(id: String, chunkId: String, sourceId: String, text: String, authoritative: Boolean, quoteRange: GroundingSupport.QuoteRange? = null) =
        ReconciledCitation(
            citation = Citation(id, SourceType.NOTE, sourceId, chunkId),
            resolved = retrieval(chunkId, sourceId, text),
            segment = SourceSegment(id = chunkId, sourceId = sourceId, text = text, sourceType = SourceType.NOTE),
            computedQuoteRange = quoteRange,
            authoritative = authoritative
        )

    private fun validator(noteExists: (String) -> Boolean = { true }): ClaimValidator {
        // Embed fading: quote-presence path decides, embedder is a stub that returns low vectors.
        every { embedder.isReady() } returns true
        coEvery { embedder.embed(any()) } returns FloatArray(384) // all-zero => cosine 0.0, below floor
        return ClaimValidator(dao, embedder, noteExists)
    }

    @Test
    fun `fully grounded claim validates and persists VALIDATED with quoteText`() = runTest {
        val claim = Claim(text = "The supplier was ACME Corporation", citationIds = listOf("cite-1"))
        val response = GroundedChatResponse("answer", claims = listOf(claim))
        val rec = listOf(reconciled("cite-1", "seg-1", "note.md",
            "The supplier was ACME Corporation, based in Berlin.", true, GroundingSupport.QuoteRange(0, 11)))

        val result = validator().validate(response, rec, emptyList(), "answer-1")

        assertEquals(1, result.validClaims)
        assertEquals(0, result.unsupportedClaims)
        assertTrue(result.validatedClaims[0].isValid)
        assertEquals(listOf("cite-1"), result.validatedClaims[0].validCitationIds)
        coVerify { dao.insertAll(any()) }
    }

    @Test
    fun `specific failure reason for chunk missing from retrieved context`() = runTest {
        val claim = Claim(text = "The supplier was ACME Corporation", citationIds = listOf("cite-2"))
        val response = GroundedChatResponse("answer", claims = listOf(claim))
        val rec = listOf(reconciled("cite-2", "seg-4", "note.md", "unused", authoritative = false))

        val result = validator().validate(response, rec, emptyList(), "answer-2")

        val vc = result.validatedClaims[0]
        assertFalse(vc.isValid)
        assertTrue(vc.invalidCitationIds.contains("cite-2"))
        assertEquals("chunkId 'seg-4' not in retrieved context", vc.invalidCitationReasons["cite-2"])
    }

    @Test
    fun `claim not entailed by chunk fails via quote presence and embedding fail-closed`() = runTest {
        val claim = Claim(text = "Quantum entanglement is central to teleportation", citationIds = listOf("cite-1"))
        val response = GroundedChatResponse("answer", claims = listOf(claim))
        val rec = listOf(reconciled("cite-1", "seg-1", "note.md",
            "The weather in Berlin is rainy today.", true, GroundingSupport.QuoteRange(0, 5)))

        val result = validator().validate(response, rec, emptyList(), "answer-3")

        assertFalse(result.validatedClaims[0].isValid)
        assertTrue(result.validatedClaims[0].invalidCitationReasons["cite-1"].orEmpty()
            .contains("not entailed by chunk"))
    }

    @Test
    fun `embedder unavailable fails closed for near-quote only claims`() = runTest {
        every { embedder.isReady() } returns false
        coEvery { embedder.embed(any()) } returns null
        val claim = Claim(text = "the plans for the new office building", citationIds = listOf("cite-1"))
        val response = GroundedChatResponse("answer", claims = listOf(claim))
        // Short overlap that would pass the quote-token path is absent; fail-closed must reject.
        val rec = listOf(reconciled("cite-1", "seg-1", "note.md",
            "Completely unrelated text about cooking pasta.", true, GroundingSupport.QuoteRange(0, 5)))

        val result = validator().validate(response, rec, emptyList(), "answer-4")

        assertFalse(result.validatedClaims[0].isValid)
    }

    @Test
    fun `memory-object-only claim validates when object retrieved`() = runTest {
        val claim = Claim(text = "Commitment due Friday", memory_object_ids = listOf("mem-9"))
        val response = GroundedChatResponse("answer", claims = listOf(claim))
        val memoryRetrieval = RetrievalResult(sourceSegmentId = "seg-mem", memoryObjectId = "mem-9",
            sourceId = "note.md", text = "t", score = 0.8f, rank = 1)

        val result = validator().validate(response, emptyList(), listOf(memoryRetrieval), "answer-5")

        assertTrue(result.validatedClaims[0].isValid)
        assertEquals(listOf("mem-9"), result.validatedClaims[0].validMemoryIds)
    }

    @Test
    fun `missing memory object is reported`() = runTest {
        val claim = Claim(text = "x", memory_object_ids = listOf("mem-42"))
        val response = GroundedChatResponse("answer", claims = listOf(claim))
        val result = validator().validate(response, emptyList(), emptyList(), "answer-6")
        assertFalse(result.validatedClaims[0].isValid)
        assertEquals(listOf("mem-42"), result.validatedClaims[0].invalidMemoryIds)
    }
}

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.chat.ClaimValidatorTest"`
Expected: FAIL — `ClaimValidator` unresolved (main) and no legacy compile of CitationValidator is needed for a targeted run.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.noteflowai.app.data.chat

import com.noteflowai.app.data.memory.dao.AnswerCitationDao
import com.noteflowai.app.data.memory.model.AnswerCitation
import com.noteflowai.app.data.search.OnDeviceEmbedder
import com.noteflowai.app.data.search.RetrievalResult
import java.util.UUID

/**
 * Phase 6 claim validator. Replaces CitationValidator. For each claim it checks
 * the citation references (V1 uniqueness), then per reference: the reconciled
 * citation is authoritative (V2/V3-consistency/V4, computed in CitationReconciler),
 * the source note still exists (V3-existence), and the claim is entailed by the
 * chunk (V5: embedding cosine >= floor OR quote presence). Entailment fails
 * closed when the embedder is unavailable. Validated (and invalid) citation
 * references are persisted as answer_citations rows with an excerpt quoteText.
 */
class ClaimValidator(
    private val answerCitationDao: AnswerCitationDao,
    private val embedder: OnDeviceEmbedder,
    private val sourceNoteExists: (String) -> Boolean
) {
    suspend fun validate(
        response: GroundedChatResponse,
        reconciled: List<ReconciledCitation>,
        retrievalResults: List<RetrievalResult>,
        answerId: String
    ): ValidatedResponse {
        val byId = reconciled.associateBy { it.citation.id }
        val validMemoryIds = retrievalResults.mapNotNull { it.memoryObjectId }.toSet()

        val validatedClaims = response.claims.mapIndexed { claimIndex, claim ->
            validateClaim(claim, claimIndex, byId, validMemoryIds, answerId)
        }
        val validClaims = validatedClaims.count { it.isValid }
        return ValidatedResponse(
            response = response,
            validatedClaims = validatedClaims,
            totalClaims = validatedClaims.size,
            validClaims = validClaims,
            unsupportedClaims = validatedClaims.size - validClaims
        )
    }

    private suspend fun validateClaim(
        claim: Claim,
        claimIndex: Int,
        reconciledBy: Map<String, ReconciledCitation>,
        validMemoryIds: Set<String>,
        answerId: String
    ): ValidatedClaim {
        val reasons = mutableMapOf<String, String>()
        val validCitations = mutableListOf<String>()
        val invalidCitations = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        for (citationId in claim.citationIds) {
            if (!seen.add(citationId)) {
                reasons[citationId] = "citation id '$citationId' is duplicated in citations array"
                invalidCitations.add(citationId)
                continue
            }
            val rec = reconciledBy[citationId]
            if (rec == null) {
                reasons[citationId] = "citation id '$citationId' is not present in the response citations array"
                invalidCitations.add(citationId)
                continue
            }
            if (rec.authoritative && rec.resolved != null) {
                if (sourceNoteExists(rec.resolved.sourceId) && isEntailed(claim.text, rec)) {
                    if (!validCitations.contains(citationId)) validCitations.add(citationId)
                    persist(rec, claimIndex, claim.text, answerId, "VALIDATED")
                } else {
                    val whySource = if (!sourceNoteExists(rec.resolved.sourceId)) {
                        "source '${rec.resolved.sourceId}' does not exist"
                    } else {
                        "claim text is not entailed by chunk '${rec.citation.chunkId}'"
                    }
                    reasons[citationId] = whySource
                    invalidCitations.add(citationId)
                    persist(rec, claimIndex, claim.text, answerId, "INVALID")
                }
            } else {
                reasons[citationId] = reasonFor(rec)
                invalidCitations.add(citationId)
                persist(rec, claimIndex, claim.text, answerId, "INVALID")
            }
        }

        val invalidMemory = claim.memory_object_ids.filterNot { it in validMemoryIds }
        val hasValidQuoteRange = claim.citationIds.any { cid ->
            reconciledBy[cid]?.computedQuoteRange != null && validCitations.contains(cid)
        }

        return ValidatedClaim(
            claim = claim,
            isValid = reasons.isEmpty() && invalidMemory.isEmpty(),
            validCitationIds = validCitations,
            invalidCitationIds = invalidCitations,
            validMemoryIds = claim.memory_object_ids.filter { it in validMemoryIds },
            invalidMemoryIds = invalidMemory,
            hasValidQuoteRange = hasValidQuoteRange,
            invalidCitationReasons = reasons,
            reason = reasons.values.firstOrNull() ?: invalidMemory.firstOrNull()?.let { "memory object '$it' not in retrieved memory objects" }
        )
    }

    private fun reasonFor(rec: ReconciledCitation?): String = when {
        rec == null -> "citation id resolved to no evidence entry"
        rec.resolved == null -> "chunkId '${rec.citation.chunkId}' not in retrieved context"
        rec.citation.sourceId != rec.resolved.sourceId ->
            "sourceId '${rec.citation.sourceId}' does not match resolved source '${rec.resolved.sourceId}'"
        else -> "chunkId '${rec.citation.chunkId}' has no persistent source segment"
    }

    /** FAIL-CLOSED entailment: quote presence OR (embedder-ready AND cosine >= floor). */
    private suspend fun isEntailed(claimText: String, rec: ReconciledCitation): Boolean {
        val text = rec.resolved!!.text
        if (GroundingSupport.checkQuotePresence(text, claimText)) return true
        val claimVec = embedder.embed(claimText) ?: return false
        val chunkVec = embedder.embed(text) ?: return false
        return cosine(claimVec, chunkVec) >= GroundingSupport.EMBED_COSINE_FLOOR
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    private suspend fun persist(
        rec: ReconciledCitation,
        claimIndex: Int,
        claimText: String,
        answerId: String,
        status: String
    ) {
        val text = rec.resolved?.text
        val excerpt = if (text == null) null else {
            val range = rec.computedQuoteRange
            if (range != null) text.substring(range.start, range.end)
            else GroundingSupport.findQuoteWindow(text, claimText)?.let { text.substring(it.start, it.end) }
        }
        val row = AnswerCitation(
            id = UUID.randomUUID().toString(),
            answerId = answerId,
            sourceSegmentId = rec.citation.chunkId,
            claimIndex = claimIndex,
            locationType = rec.citation.sourceType.name,
            supportStatus = status,
            quoteText = excerpt
        )
        answerCitationDao.insertAll(listOf(row))
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.chat.ClaimValidatorTest"`
Expected: PASS (all 6 tests). The `AnswerCitation` `quoteText` field compiles because the model field is added in Task 9 — but the existing `AnswerCitation.kt` lacks it, so add the field now if Step 2 failed on it (see Task 9 step 1; the two-step task order tolerates the field arriving here).

- [ ] **Step 5: Commit**

```bash
git rm app/src/main/java/com/noteflowai/app/data/chat/CitationValidator.kt app/src/test/java/com/noteflowai/app/data/chat/CitationValidatorTest.kt
git add app/src/main/java/com/noteflowai/app/data/chat/ClaimValidator.kt app/src/test/java/com/noteflowai/app/data/chat/ClaimValidatorTest.kt
git commit -m "feat(chat): claim validator, replaces CitationValidator with entailment enforcement"
```

---

### Task 6: UnsupportedClaimDetector rework (detection + specific retry prompt)

**Files:**
- Modify (rewrite): `app/src/main/java/com/noteflowai/app/data/chat/UnsupportedClaimDetector.kt` — remove `analyze()` and the old `GroundingDecision` sealed class; keeps only detection + retry-prompt building.
- Modify (rewrite): `app/src/test/java/com/noteflowai/app/data/chat/UnsupportedClaimDetectorTest.kt`

> NOTE: The old detector owns terminal state (its `analyze` returned `GroundingDecision`). Task 7 moves ALL terminal-state decisions into `GroundingDispositionResolver`. The detector here is a pure flagger + prompt-text builder.

**Interfaces:**
- Consumes: `ValidatedResponse`/`ValidatedClaim` (Task 1), `GroundingDisposition` type from Task 7 is NOT consumed here (detector never emits a disposition).
- Produces: `class UnsupportedClaimDetector { fun needsRetry(validated: ValidatedResponse): Boolean; fun buildRetryPrompt(validated: ValidatedResponse): String }`. `buildRetryPrompt` MUST emit specific per-citation reasons in the exact format `Citation [N] <reason>` where N is the 1-based index of the citation in `validated.response.citations` (closing note 3): e.g. `Citation [2] chunkId 'seg-4' not in retrieved context`. Also emits a `Memory object 'mem-9' not in retrieved memory objects` line per invalid memory id. Never a generic "fix your citations".

- [ ] **Step 1: Write the failing test** (replaces the old test file)

```kotlin
package com.noteflowai.app.data.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnsupportedClaimDetectorTest {

    private val detector = UnsupportedClaimDetector()

    private fun validated(validClaims: Int, unsupported: Int): ValidatedResponse {
        val total = validClaims + unsupported
        val vc = List(unsupported) { i ->
            ValidatedClaim(
                claim = Claim(text = "claim-$i", citationIds = listOf("cite-${i + 1}")),
                isValid = false,
                validCitationIds = emptyList(),
                invalidCitationIds = listOf("cite-${i + 1}"),
                hasValidQuoteRange = false,
                invalidCitationReasons = mapOf("cite-${i + 1}" to "chunkId 'seg-4' not in retrieved context")
            )
        }
        return ValidatedResponse(
            response = GroundedChatResponse("answer"),
            validatedClaims = vc,
            totalClaims = total,
            validClaims = validClaims,
            unsupportedClaims = unsupported
        )
    }

    @Test
    fun `needsRetry is true only when a claim is unsupported`() {
        assertTrue(detector.needsRetry(validated(validClaims = 0, unsupported = 1)))
        assertTrue(detector.needsRetry(validated(validClaims = 3, unsupported = 2)))
        assertFalse(detector.needsRetry(validated(validClaims = 1, unsupported = 0)))
    }

    @Test
    fun `retry prompt contains the SPECIFIC failure reason, not a generic directive`() {
        // cite-2 is the rejected reference; it sits at 1-based index 2 in response.citations.
        val validated = ValidatedResponse(
            response = GroundedChatResponse(
                "answer",
                citations = listOf(
                    Citation(id = "cite-1", sourceType = com.noteflowai.app.data.memory.model.SourceType.NOTE, sourceId = "note.md", chunkId = "seg-3"),
                    Citation(id = "cite-2", sourceType = com.noteflowai.app.data.memory.model.SourceType.NOTE, sourceId = "note.md", chunkId = "seg-4")
                )
            ),
            validatedClaims = listOf(ValidatedClaim(
                claim = Claim(text = "c", citationIds = listOf("cite-2")),
                isValid = false,
                validCitationIds = emptyList(),
                invalidCitationIds = listOf("cite-2"),
                hasValidQuoteRange = false,
                invalidCitationReasons = mapOf("cite-2" to "chunkId 'seg-4' not in retrieved context")
            )),
            totalClaims = 1,
            validClaims = 0,
            unsupportedClaims = 1
        )
        val prompt = detector.buildRetryPrompt(validated)

        // The [2] index is keyed to the citation's position in response.citations (1-based).
        assertTrue(prompt.contains("Citation [2] chunkId 'seg-4' not in retrieved context"))
        assertFalse(prompt.contains("fix your citations"))
    }
}
```

> The retry prompt format `Citation [N] <reason>` matches the plan-required specific-reason contract. The prompt is built by ordering `validated.response.citations`; the detector finds each numbered citation via `indexOfFirst { it.id == citationId } + 1`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.chat.UnsupportedClaimDetectorTest"`
Expected: FAIL — old `analyze`/`GroundingDecision` API gone; new methods missing.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.noteflowai.app.data.chat

/**
 * Phase 6: flags whether any claim is unsupported and builds the RETRY prompt
 * content. Detector never emits a terminal disposition - that is exclusively
 * GroundingDispositionResolver's job (spec §6). The retry prompt carries the
 * SPECIFIC failure reason for every rejected citation, formatted as
 * `Citation [N] <reason>` (N = 1-based index in response.citations).
 */
class UnsupportedClaimDetector {

    fun needsRetry(validated: ValidatedResponse): Boolean = validated.unsupportedClaims > 0

    fun buildRetryPrompt(validated: ValidatedResponse): String {
        val citationLines = validated.validatedClaims
            .flatMap { vc ->
                vc.invalidCitationReasons.map { (citationId, reason) ->
                    val index = validated.response.citations
                        .indexOfFirst { it.id == citationId } + 1
                    "Citation [$index] $reason"
                }
            }
        val memoryLines = validated.validatedClaims
            .flatMap { vc -> vc.invalidMemoryIds.map { "Memory object '$it' not in retrieved memory objects" } }
            .distinct()

        val lines = (citationLines + memoryLines).distinct()
        return buildString {
            appendLine("The previous response made claims that could not be grounded in the provided evidence.")
            appendLine("Revise the response so every claim is entailed by the evidence items, using [N] markers exactly.")
            appendLine("Rejected references:")
            lines.forEach { appendLine("- $it") }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.chat.UnsupportedClaimDetectorTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/chat/UnsupportedClaimDetector.kt app/src/test/java/com/noteflowai/app/data/chat/UnsupportedClaimDetectorTest.kt
git commit -m "refactor(chat): detector-only unsupported claim flagging with specific retry prompt"
```

---

### Task 7: GroundingDispositionResolver (single owner of terminal state)

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/chat/GroundingDispositionResolver.kt`
- Test: `app/src/test/java/com/noteflowai/app/data/chat/GroundingDispositionResolverTest.kt`

**Interfaces:**
- Consumes: `UncertaintyLevel`/`ValidatedResponse`/`ValidatedClaim`/`GroundingDisposition` (Task 1), `UnsupportedClaimDetector` (Task 6).
- Produces: `sealed class GroundingDisposition` with `data object FullyValidated`, `data class Unverified(reason: String)`, `data class Abstain(reason: String)`, `data class Retry(prompt: String)`, `data object HoleInEvidence`, `data object Unresponsive`. `class GroundingDispositionResolver(detector: UnsupportedClaimDetector)` with `fun resolve(validated: ValidatedResponse?, attempt: Int, maxAttempts: Int): GroundingDisposition` (null `validated` → `UNRESPONSIVE`, parse failure). Const `ABSTAIN_THRESHOLD = 0.5f`. Trigger 4 (response-level, NO query-intent classifier): `any claim.uncertainty == HIGH` AND zero claims with `hasValidQuoteRange` → `HoleInEvidence`.

Decision order (spec §6; single owner):
1. `validated == null` → `Unresponsive`
2. `response.abstained == true` → `Abstain(reason = abstention_reason ?: default)`
3. Trigger 4 → `HoleInEvidence`
4. `detector.needsRetry(validated)`:
   - `attempt < maxAttempts - 1` → `Retry(detector.buildRetryPrompt(validated))` — `maxAttempts = 2` (Task 11), so attempt 0 retries, attempt 1 (final) is terminal.
   - else → `Abstain` if `unsupportedRatio >= ABSTAIN_THRESHOLD and totalClaims > 0`, otherwise `Unverified`.
5. else → `FullyValidated`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.noteflowai.app.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundingDispositionResolverTest {

    private val detector = UnsupportedClaimDetector()
    private val resolver = GroundingDispositionResolver(detector)

    private fun fullyValidated() = ValidatedResponse(
        response = GroundedChatResponse("ok"),
        validatedClaims = emptyList(), totalClaims = 0, validClaims = 0, unsupportedClaims = 0
    )

    private fun withUnsupported(highUncertainty: Boolean = false, quote: Boolean = true): ValidatedResponse {
        val claims = listOf(
            Claim(text = "c", citationIds = listOf("cite-1"),
                uncertainty = if (highUncertainty) UncertaintyLevel.HIGH else UncertaintyLevel.LOW)
        )
        return ValidatedResponse(
            response = GroundedChatResponse("answer", citations = listOf(
                Citation("cite-1", com.noteflowai.app.data.memory.model.SourceType.NOTE, "n.md", "seg-1")
            ), claims = claims),
            validatedClaims = listOf(ValidatedClaim(
                claim = claims[0], isValid = false,
                validCitationIds = emptyList(), invalidCitationIds = listOf("cite-1"),
                hasValidQuoteRange = quote,
                invalidCitationReasons = mapOf("cite-1" to "chunkId 'seg-1' not in retrieved context")
            )),
            totalClaims = 1, validClaims = 0, unsupportedClaims = 1
        )
    }

    /** 4 claims total, 1 unsupported (ratio 0.25). All claims carry LOW uncertainty and valid quotes. */
    private fun ratioBelowThreshold(): ValidatedResponse {
        val claims = (1..4).map {
            Claim(text = "c$it", citationIds = listOf("cite-$it"))
        }
        val context = (1..4).map {
            Citation("cite-$it", com.noteflowai.app.data.memory.model.SourceType.NOTE, "n.md", "seg-$it")
        }
        val validatedClaims = claims.mapIndexed { idx, claim ->
            val rejected = idx == 3
            ValidatedClaim(
                claim = claim, isValid = !rejected,
                validCitationIds = if (rejected) emptyList() else listOf("cite-${idx + 1}"),
                invalidCitationIds = if (rejected) listOf("cite-4") else emptyList(),
                hasValidQuoteRange = true,
                invalidCitationReasons = if (rejected) mapOf("cite-4" to "chunkId 'seg-4' not in retrieved context") else emptyMap()
            )
        }
        return ValidatedResponse(
            response = GroundedChatResponse("answer", citations = context, claims = claims),
            validatedClaims = validatedClaims, totalClaims = 4, validClaims = 3, unsupportedClaims = 1
        )
    }

    @Test
    fun `null validated is unresponsive`() {
        assertEquals(GroundingDisposition.Unresponsive, resolver.resolve(null, 0, 2))
    }

    @Test
    fun `abstained response is abstain regardless of claims`() {
        val v = fullyValidated().let {
            it.copy(response = it.response.copy(abstained = true, abstention_reason = "no"))
        }
        assertEquals(GroundingDisposition.Abstain("no"), resolver.resolve(v, 0, 2))
    }

    @Test
    fun `trigger 4 high uncertainty with no valid quote is hole in evidence`() {
        assertEquals(
            GroundingDisposition.HoleInEvidence,
            resolver.resolve(withUnsupported(highUncertainty = true, quote = false), 1, 2)
        )
    }

    @Test
    fun `high uncertainty with a valid quote does not trigger 4`() {
        val d = resolver.resolve(withUnsupported(highUncertainty = true, quote = true), 1, 2)
        assertTrue(d is GroundingDisposition.Unverified || d is GroundingDisposition.Abstain)
    }

    @Test
    fun `unsupported claims on attempt 0 produce retry`() {
        val d = resolver.resolve(withUnsupported(), 0, 2)
        assertTrue(d is GroundingDisposition.Retry)
        assertTrue((d as GroundingDisposition.Retry).prompt.contains("Citation [1] chunkId 'seg-1' not in retrieved context"))
    }

    @Test
    fun `unsupported claims on final attempt without threshold produce unverified`() {
        // ratio 1 unsupported / 4 total = 0.25 < 0.5 -> Unverified, not Abstain.
        val d = resolver.resolve(ratioBelowThreshold(), 1, 2)
        assertEquals(GroundingDisposition.Unverified, d)
    }

    @Test
    fun `unsupported ratio at or above threshold on final attempt produces abstain`() {
        val d = resolver.resolve(withUnsupported(), 1, 2) // ratio 1/1 = 1.0 >= 0.5
        assertTrue(d is GroundingDisposition.Abstain)
    }

    @Test
    fun `fully validated response is fully validated`() {
        assertEquals(GroundingDisposition.FullyValidated, resolver.resolve(fullyValidated(), 1, 2))
    }
}
```

> The two final-attempt tests exercise both sides of the ratio branch: `ratioBelowThreshold()` has an unsupported ratio of `1/4 = 0.25 < 0.5` (Unverified), and `withUnsupported()` has `1/1 = 1.0 >= 0.5` (Abstain). The resolver's branch order (retry → ratio threshold → unverified) is what makes both pass.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.chat.GroundingDispositionResolverTest"`
Expected: FAIL — `GroundingDispositionResolver`, `GroundingDisposition` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.noteflowai.app.data.chat

/**
 * Phase 6 terminal-state owner. The ONLY component that decides between
 * FULLY_VALIDATED / UNVERIFIED / ABSTAIN / RETRY / HOLE_IN_EVIDENCE /
 * UNRESPONSIVE. Trigger 4 is response-level (no query-intent classifier) and the
 * retry-vs-terminal decision for unsupported claims lives here.
 */
sealed class GroundingDisposition {
    data object FullyValidated : GroundingDisposition()
    data class Unverified(val reason: String) : GroundingDisposition()
    data class Abstain(val reason: String) : GroundingDisposition()
    data class Retry(val prompt: String) : GroundingDisposition()
    data object HoleInEvidence : GroundingDisposition()
    data object Unresponsive : GroundingDisposition()
}

class GroundingDispositionResolver(
    private val detector: UnsupportedClaimDetector
) {
    companion object {
        const val ABSTAIN_THRESHOLD = 0.5f
        private val DEFAULT_ABSTAIN = "The response could not be grounded in the retrieved sources."
        private val DEFAULT_UNVERIFIED = "Some claims could not be verified against the retrieved sources."
    }

    fun resolve(validated: ValidatedResponse?, attempt: Int, maxAttempts: Int): GroundingDisposition {
        if (validated == null) return GroundingDisposition.Unresponsive

        if (validated.response.abstained) {
            return GroundingDisposition.Abstain(validated.response.abstention_reason ?: DEFAULT_ABSTAIN)
        }

        if (isHoleInEvidence(validated)) return GroundingDisposition.HoleInEvidence

        if (detector.needsRetry(validated)) {
            if (attempt < maxAttempts - 1) {
                return GroundingDisposition.Retry(detector.buildRetryPrompt(validated))
            }
            val ratio = unsupportedRatio(validated)
            return if (validated.totalClaims > 0 && ratio >= ABSTAIN_THRESHOLD) {
                GroundingDisposition.Abstain(DEFAULT_ABSTAIN)
            } else {
                GroundingDisposition.Unverified(DEFAULT_UNVERIFIED)
            }
        }
        return GroundingDisposition.FullyValidated
    }

    private fun isHoleInEvidence(v: ValidatedResponse): Boolean =
        v.validatedClaims.any { it.claim.uncertainty == UncertaintyLevel.HIGH } &&
            v.validatedClaims.none { it.hasValidQuoteRange }

    private fun unsupportedRatio(v: ValidatedResponse): Float =
        if (v.totalClaims == 0) 0f else v.unsupportedClaims.toFloat() / v.totalClaims
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.chat.GroundingDispositionResolverTest"`
Expected: PASS (all 8 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/chat/GroundingDispositionResolver.kt app/src/test/java/com/noteflowai/app/data/chat/GroundingDispositionResolverTest.kt
git commit -m "feat(chat): grounding disposition resolver as sole terminal-state owner"
```

---

### Task 8: GroundingPromptBuilder rework (numbered evidence list + [N] contract)

**Files:**
- Modify (rewrite `buildGroundingPrompt` and `RESPONSE_SCHEMA`): `app/src/main/java/com/noteflowai/app/data/chat/GroundingPromptBuilder.kt`
- Test: `app/src/test/java/com/noteflowai/app/data/chat/GroundingPromptBuilderTest.kt`

**Interfaces:**
- Consumes: `RetrievalResult` (`sourceSegmentId`, `sourceId`, `text`, `score`). Signature stays `fun buildGroundingPrompt(retrievalResults: List<RetrievalResult>): String` (the MainViewModel hook at L3583-3588 does not change).
- Produces: prompt fragment whose evidence items are a 1-based NUMBERED list `N. [chunkId=...] [sourceId=...] "excerpt"`; instructions require the model to cite with EXACT `[N]` markers and generate `citations[]` with `id = "cite-<N>"`; `RESPONSE_SCHEMA` updated to the guide-literal shape (`citations[]`, `claims[].citationIds`, `uncertainty`, dropped `source_segment_ids`).
- Contract (closing note 1): numbered list + exact `[1]`/`[2]` markers, documented in the prompt text itself.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.noteflowai.app.data.chat

import com.noteflowai.app.data.memory.model.SourceType
import com.noteflowai.app.data.search.RetrievalResult
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundingPromptBuilderTest {

    private val builder = GroundingPromptBuilder()

    @Test
    fun `prompt lists evidence items in a numbered 1-based list with [N] marker contract`() {
        val results = listOf(
            RetrievalResult("seg-1", sourceId = "note.md", text = "The supplier was ACME Corp.",
                score = 0.9f, rank = 0),
            RetrievalResult("seg-2", sourceId = "decision.md", text = "Decision: adopt Rust.",
                score = 0.85f, rank = 1)
        )
        val prompt = builder.buildGroundingPrompt(results)

        assertTrue(prompt.contains("1. [chunkId=seg-1] [sourceId=note.md] \"The supplier was ACME Corp.\""))
        assertTrue(prompt.contains("2. [chunkId=seg-2] [sourceId=decision.md] \"Decision: adopt Rust.\""))
        assertTrue(prompt.contains("cite-1"))
        assertTrue(prompt.contains("[1]"))
    }

    @Test
    fun `schema contains citations and citationIds not source_segment_ids`() {
        val prompt = builder.buildGroundingPrompt(emptyList())
        assertTrue(prompt.contains("citations"))
        assertTrue(prompt.contains("citationIds"))
        assertTrue(!prompt.contains("source_segment_ids"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.chat.GroundingPromptBuilderTest"`
Expected: FAIL — the current builder outputs comma-joined `Valid source IDs:` and the old schema (`source_segment_ids`).

- [ ] **Step 3: Write minimal implementation** — replace the whole file:

```kotlin
package com.noteflowai.app.data.chat

import com.noteflowai.app.data.search.RetrievalResult

/**
 * Phase 6: builds the evidence block for the grounded chat system prompt.
 * Evidence is a 1-based numbered list; the model MUST cite with exact [N]
 * markers and MUST emit citations[] with id = "cite-<N>" matching item N
 * (closing-note 1 contract). Sources outside this list are forbidden.
 */
class GroundingPromptBuilder {

    fun buildGroundingPrompt(retrievalResults: List<RetrievalResult>): String = buildString {
        appendLine("--- EVIDENCE GROUNDING RULES (STRICT) ---")
        appendLine("Answer ONLY from the evidence items numbered below. Every factual claim you make MUST cite the evidence it is based on.")
        appendLine("Evidence items (1-based; N is the item number):")
        retrievalResults.forEachIndexed { index, r ->
            val excerpt = r.text.trim()
            appendLine("${index + 1}. [chunkId=${r.sourceSegmentId}] [sourceId=${r.sourceId}] \"$excerpt\"")
        }
        appendLine()
        appendLine("STRICT CITATION RULES:")
        appendLine("- In each claim, append the marker [N] where N is the number of the evidence item supporting it. Use exactly [1], [2], etc. - no other citation syntax, no footnotes.")
        appendLine("- Emit a top-level \"citations\" array. Each citation's \"id\" MUST be \"cite-<N>\" matching the evidence item number N (item 1 -> \"cite-1\").")
        appendLine("- Each claim's \"citationIds\" MUST list the \"cite-<N>\" ids of the citations supporting it.")
        appendLine("- quoteStart/quoteEnd are character offsets (0-based) into the quoted evidence item text.")
        appendLine("- NEVER cite an item not listed above. If no item supports a claim, omit the claim.")
        appendLine()
        appendLine("RESPONSE FORMAT - valid JSON object with this exact shape:")
        appendLine("""
        {
          "answer": "string",
          "citations": [{"id": "cite-1", "sourceType": "NOTE|AUDIO|PDF|DOCUMENT|OCR|YOUTUBE", "sourceId": "...", "chunkId": "...", "quoteStart": 0, "quoteEnd": 10, "relevanceScore": 0.0}],
          "claims": [{"text": "string", "citationIds": ["cite-1"], "memory_object_ids": [], "uncertainty": "LOW|MEDIUM|HIGH"}],
          "suggested_actions": [{"type": "CREATE_NOTE|CREATE_TASK|CONFIRM_DECISION|OPEN_SOURCE|NONE", "label": "string"}],
          "needs_clarification": false,
          "clarification_question": null,
          "abstained": false,
          "abstention_reason": null
        }
        """.trimIndent())
        appendLine("Reply with ONLY the JSON object, no prose.")
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.chat.GroundingPromptBuilderTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/chat/GroundingPromptBuilder.kt app/src/test/java/com/noteflowai/app/data/chat/GroundingPromptBuilderTest.kt
git commit -m "feat(chat): numbered evidence list with exact [N] / cite-N prompt contract"
```

---

### Task 9: Room schema v7 → v8 (answer_citations.quoteText)

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/data/memory/model/AnswerCitation.kt` (add field)
- Modify: `app/src/main/java/com/noteflowai/app/data/memory/db/MemoryDatabase.kt` (bump version, add migration)
- Test: `app/src/test/java/com/noteflowai/app/data/memory/db/MemoryDatabaseMigrationV7ToV8Test.kt`

**Interfaces:**
- Consumes: existing `MemoryDatabase` (version 7, `MIGRATION_1_2..MIGRATION_6_7`), `AnswerCitation` entity (Room).
- Produces: `AnswerCitation.quoteText: String?` (nullable, no default), DB version 8, `Migration(7, 8)` adding `ALTER TABLE answer_citations ADD COLUMN quoteText TEXT` via `MIGRATION_7_8` registered in `addMigrations`. Task 5's `ClaimValidator` writes to this column in production.

- [ ] **Step 1: Add the field to the entity**

```kotlin
// AnswerCitation.kt — add as a nullable column, keep the rest unchanged
val quoteText: String? = null,
```

- [ ] **Step 2: Bump schema + register migration**

```kotlin
// MemoryDatabase.kt
// version = 7  →  version = 8
private val MIGRATION_7_8: Migration = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE answer_citations ADD COLUMN quoteText TEXT")
    }
}
// addMigrations(...): append MIGRATION_7_8 after MIGRATION_6_7
```

- [ ] **Step 3: Write the failing migration test**

```kotlin
package com.noteflowai.app.data.memory.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryDatabaseMigrationV7ToV8Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MemoryDatabase::class.java
    )

    @Test
    fun `v7 to v8 preserves data and adds quoteText column`() {
        val db = helper.createDatabase("migration-test-7-8", 7)
        db.execSQL(
            "INSERT INTO answer_citations (id, answerId, sourceSegmentId, claimIndex, locationType, supportStatus) " +
                "VALUES ('r1', 'a1', 'seg1', 0, 'NOTE', 'PENDING')"
        )
        helper.runMigrationsAndValidate("migration-test-7-8", 8, true, MemoryDatabase.MIGRATION_7_8)
        val cursor = db.query("SELECT quoteText FROM answer_citations WHERE id = 'r1'").use { c ->
            c.moveToFirst()
            c.getColumnIndexOrThrow("quoteText")
        }
        // Fresh table created at v7: existing row's quoteText is NULL (no backfill).
    }
}
```

- [ ] **Step 4: Run test to verify it fails first (migration absent)**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.memory.db.MemoryDatabaseMigrationV7ToV8Test"`
Expected: FATAL — Room opens the DB as newest version (8) but the existing-database migration 7→8 is unregistered; `IllegalStateException: Missing migration from 7 to 8`.

- [ ] **Step 5: Register the migration + bump version, then run again**

Apply Steps 1-2, then:
Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.memory.db.MemoryDatabaseMigrationV7ToV8Test"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/memory/model/AnswerCitation.kt app/src/main/java/com/noteflowai/app/data/memory/db/MemoryDatabase.kt app/src/test/java/com/noteflowai/app/data/memory/db/MemoryDatabaseMigrationV7ToV8Test.kt
git commit -m "feat(db): add answer_citations.quoteText via v7 to v8 migration"
```

---

### Task 10: Phase A pre-call triggers (T1/T2/T3)

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/chat/PreCallRefuser.kt`
- Test: `app/src/test/java/com/noteflowai/app/data/chat/PreCallRefuserTest.kt`
- Modify: `app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt` (chat loop hook)

**Interfaces:**
- Consumes: `RetrievalResult`, `ConflictDetector` (ctor `(temporalIndex, conceptGraphRepository)`, `fun findConflicts(maxResults: Int = 3): List<ConflictResult>`).
- Produces: `data class ConflictResult(concept, noteA, noteB, contextA, contextB, reason)` (existing; used for the conflict refusal reason); `sealed class PreCallDecision { data object Proceed; data class Refuse(val reasonKey: GroundingRefusalReason) }`; `enum class GroundingRefusalReason { EMPTY_RETRIEVAL, BELOW_FLOOR, CONFLICT }`; `class PreCallRefuser(conflictDetector: ConflictDetector, minimumScoreFloor: Float = 0.28f)` with `fun decide(retrievalResults: List<RetrievalResult>): PreCallDecision` (T1 empty → refuse; T2 all below floor → refuse; else T3 conflicts via detector → refuse; else Proceed).
- Consumes `Config`: the floor matches `RetrievalConfig.default.minimumScore = 0.28f` (do not change retrieval).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.noteflowai.app.data.chat

import com.noteflowai.app.data.search.RetrievalResult
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreCallRefuserTest {

    private fun ctx(empty: Boolean = false): List<RetrievalResult> =
        if (empty) emptyList()
        else listOf(RetrievalResult("seg", sourceId = "n.md", text = "t", score = 0.5f, rank = 0))

    private fun belowFloor() = listOf(RetrievalResult("seg", sourceId = "n.md", text = "t", score = 0.1f, rank = 0))

    private fun mockConflict(count: Int): ConflictDetector {
        val d = mockk<ConflictDetector>()
        every { d.findConflicts(3) } returns List(count) {
            ConflictResult("c$it", "a.md", "b.md", "ca", "cb", "why$it")
        }
        return d
    }

    @Test
    fun `empty retrieval refuses with EMPTY_RETRIEVAL`() {
        assertEquals(
            PreCallDecision.Refuse(GroundingRefusalReason.EMPTY_RETRIEVAL),
            PreCallRefuser(mockConflict(0)).decide(ctx(empty = true))
        )
    }

    @Test
    fun `all results below floor refuse with BELOW_FLOOR`() {
        assertEquals(
            PreCallDecision.Refuse(GroundingRefusalReason.BELOW_FLOOR),
            PreCallRefuser(mockConflict(0)).decide(belowFloor())
        )
    }

    @Test
    fun `conflict detected refuses with CONFLICT even when results exist`() {
        assertEquals(
            PreCallDecision.Refuse(GroundingRefusalReason.CONFLICT),
            PreCallRefuser(mockConflict(1)).decide(ctx())
        )
    }

    @Test
    fun `healthy retrieval with no conflicts proceeds`() {
        assertTrue(PreCallRefuser(mockConflict(0)).decide(ctx()) is PreCallDecision.Proceed)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.chat.PreCallRefuserTest"`
Expected: FAIL — `PreCallRefuser`, `PreCallDecision`, `GroundingRefusalReason` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.noteflowai.app.data.chat

import com.noteflowai.app.data.search.ConflictDetector
import com.noteflowai.app.data.search.RetrievalResult

sealed class PreCallDecision {
    data object Proceed : PreCallDecision()
    data class Refuse(val reasonKey: GroundingRefusalReason) : PreCallDecision()
}

enum class GroundingRefusalReason { EMPTY_RETRIEVAL, BELOW_FLOOR, CONFLICT }

/**
 * Phase A: decides BEFORE the LLM call whether to refuse. T1 empty retrieval,
 * T2 context below the quality floor, T3 semantic conflict. A refusal must skip
 * the model call entirely (the ViewModel renders the refusal message instead).
 */
class PreCallRefuser(
    private val conflictDetector: ConflictDetector,
    private val minimumScoreFloor: Float = 0.28f
) {
    fun decide(retrievalResults: List<RetrievalResult>): PreCallDecision {
        if (retrievalResults.isEmpty()) return PreCallDecision.Refuse(GroundingRefusalReason.EMPTY_RETRIEVAL)
        if (retrievalResults.all { it.score < minimumScoreFloor }) {
            return PreCallDecision.Refuse(GroundingRefusalReason.BELOW_FLOOR)
        }
        if (conflictDetector.findConflicts(3).isNotEmpty()) {
            return PreCallDecision.Refuse(GroundingRefusalReason.CONFLICT)
        }
        return PreCallDecision.Proceed
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.chat.PreCallRefuserTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Wire into MainViewModel chat loop**

In `MainViewModel.initGroundedChat()` (currently constructs `CitationValidator`, `UnsupportedClaimDetector`, `GroundingPromptBuilder` — see Task 11 note for the full replacement), add a `val preCallRefuser = PreCallRefuser(ConflictDetector(temporalIndex, conceptGraphRepository))` field. In the chat send path, immediately after `buildFinalSystemPrompt` returns (this populates `lastRetrievalResults` at L3303) and before `aiChatRepository.streamResponse(...)`, short-circuit:

```kotlin
if (groundedChatEnabled && groundedPipeline != null && lastRetrievalResults.isNotEmpty()) {
    when (val decision = preCallRefuser.decide(lastRetrievalResults)) {
        is PreCallDecision.Proceed -> Unit
        is PreCallDecision.Refuse -> {
            // Build a refusal assistant message (template from strings.xml, Task 14),
            // push it, persist via saveCurrentChat, and skip the LLM call by setting
            // the same "skipStream / displayContent" path used for non-stream text.
            val refused = aiMsg.copy(content = refusalContentFor(decision.reasonKey))
            streamingContent.value = null
            _chatMessages.value = updatedMessages + refused
            saveCurrentChat(_chatMessages.value)
            return@let                              // skip stream+grounding entirely
        }
    }
}
```

`refusalContentFor(reasonKey)` is a private method that reads the matching `R.string.grounding_refusal_*` (Task 14). Skip the streaming + validation block by the same control flow used when no stream is produced. The `return@let` targets the enclosing `runCatching { }` lambda where `aiMsg`, `updatedMessages`, `_chatMessages`, `streamingContent`, `saveCurrentChat` are in scope.

- [ ] **Step 6: Verify + commit**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.chat.PreCallRefuserTest"`
Expected: PASS.

```bash
git add app/src/main/java/com/noteflowai/app/data/chat/PreCallRefuser.kt app/src/test/java/com/noteflowai/app/data/chat/PreCallRefuserTest.kt app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt
git commit -m "feat(chat): pre-call refusal triggers (empty, below-floor, conflict)"
```

> NOTE: full MainViewModel compilation is restored only in Task 11/13; between Tasks 5 and 11 the project may not compile as a whole even though each new unit-test class runs green. The pipeline's cadence runs `:app:testDebugUnitTest --tests "<new-class>"` per task, so this is expected. Do not run the full suite until Task 11.

---

### Task 11: GroundedChatPipeline + MainViewModel Phase B wiring

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/chat/GroundedChatPipeline.kt`
- Test: `app/src/test/java/com/noteflowai/app/data/chat/GroundedChatPipelineTest.kt`
- Modify: `app/src/main/java/com/noteflowai/app/data/chat/AiChatModels.kt` (add `groundedAnswerId` to `ChatMessage`)
- Modify: `app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt` (rewrite `initGroundedChat`, replace the Phase B block in the chat loop, set `groundedAnswerId`)

**Interfaces:**
- Consumes: `ParseResult`, `GroundedResponseParser` (T3), `CitationReconciler` (T4), `ClaimValidator` (T5), `UnsupportedClaimDetector` (T6), `GroundingDispositionResolver`+`GroundingDisposition` (T7), `OnDeviceEmbedder`.
- Produces:
  - `sealed class GroundedPipelineOutcome { data class Terminal(disposition, response: GroundedChatResponse?, validated: ValidatedResponse?); data class Retry(prompt: String) }`
  - `class GroundedChatPipeline(parser, reconciler, validator, detector, resolver)` with `suspend fun run(finalResponse: String, retrievalResults: List<RetrievalResult>, answerId: String, attempt: Int, maxAttempts: Int): GroundedPipelineOutcome`. Map: parse → reconcile → validate → resolve.
  - `ChatMessage` gains `val groundedAnswerId: String? = null` (reload hook for Task 13 footer hydration; spec §10).

- [ ] **Step 1: Write the failing pipeline test**

```kotlin
package com.noteflowai.app.data.chat

import com.noteflowai.app.data.memory.dao.AnswerCitationDao
import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType
import com.noteflowai.app.data.search.OnDeviceEmbedder
import com.noteflowai.app.data.search.RetrievalResult
import com.noteflowai.app.data.search.SourceSegmentRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundedChatPipelineTest {

    private val dao = mockk<AnswerCitationDao>(relaxed = true)
    private val embedder = mockk<OnDeviceEmbedder>()
    private val segmentRepo = mockk<SourceSegmentRepository>()

    private val text = "The supplier was ACME Corporation, based in Berlin."
    private val retrieval = RetrievalResult("seg-1", sourceId = "note.md", text = text, score = 0.9f, rank = 0)

    private fun pipeline(): GroundedChatPipeline {
        every { embedder.isReady() } returns true
        coEvery { embedder.embed(any()) } returns FloatArray(384)
        coEvery { segmentRepo.getById("seg-1") } returns
            SourceSegment(id = "seg-1", sourceId = "note.md", text = text, sourceType = SourceType.NOTE)
        val reconciler = CitationReconciler(segmentRepo)
        val validator = ClaimValidator(dao, embedder) { true }
        val detector = UnsupportedClaimDetector()
        return GroundedChatPipeline(
            parser = GroundedResponseParser,
            reconciler = reconciler,
            validator = validator,
            detector = detector,
            resolver = GroundingDispositionResolver(detector)
        )
    }

    @Test
    fun `happy path resolves to FullyValidated and validates all claims`() = runTest {
        val json = """
        {"answer":"The supplier was ACME Corporation.",
         "citations":[{"id":"cite-1","sourceType":"NOTE","sourceId":"note.md","chunkId":"seg-1","quoteStart":0,"quoteEnd":11}],
         "claims":[{"text":"The supplier was ACME Corporation","citationIds":["cite-1"],"uncertainty":"LOW"}]}
        """.trimIndent()
        val outcome = pipeline().run(json, listOf(retrieval), "answer-1", attempt = 0, maxAttempts = 2)
        assertTrue(outcome is GroundedPipelineOutcome.Terminal)
        val terminal = outcome as GroundedPipelineOutcome.Terminal
        assertTrue(terminal.disposition === GroundingDisposition.FullyValidated)
        assertTrue(terminal.validated?.validClaims == 1)
    }

    @Test
    fun `malformed answer is Unresponsive`() = runTest {
        val outcome = pipeline().run("{ not json", listOf(retrieval), "answer-2", attempt = 0, maxAttempts = 2)
        assertTrue(outcome is GroundedPipelineOutcome.Terminal)
        assertTrue((outcome as GroundedPipelineOutcome.Terminal).disposition === GroundingDisposition.Unresponsive)
        assertTrue(outcome.response == null)
    }

    @Test
    fun `unsupported claim yields Retry with the specific reason`() = runTest {
        // cite-1 resolves authoritatively (chunk seg-1 retrieved + segment row exists) but the
        // claim text has no quote presence and the all-zero embed gives cosine 0.0 (< floor),
        // so entailment fails -> V5 invalid, RETRY prompt carries the specific reason.
        val json = """
        {"answer":"unverifiable",
         "citations":[{"id":"cite-1","sourceType":"NOTE","sourceId":"note.md","chunkId":"seg-1","quoteStart":0,"quoteEnd":5}],
         "claims":[{"text":"quantum teleportation is real","citationIds":["cite-1"],"uncertainty":"LOW"}]}
        """.trimIndent()
        val outcome = pipeline().run(json, listOf(retrieval), "answer-3", attempt = 0, maxAttempts = 2)
        assertTrue(outcome is GroundedPipelineOutcome.Retry)
        assertTrue((outcome as GroundedPipelineOutcome.Retry).prompt.contains("not entailed by chunk 'seg-1'"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.chat.GroundedChatPipelineTest"`
Expected: FAIL — `GroundedChatPipeline`, `GroundedPipelineOutcome` unresolved.

- [ ] **Step 3: Write the pipeline**

```kotlin
package com.noteflowai.app.data.chat

import com.noteflowai.app.data.search.RetrievalResult

sealed class GroundedPipelineOutcome {
    data class Terminal(
        val disposition: GroundingDisposition,
        val response: GroundedChatResponse?,
        val validated: ValidatedResponse?
    ) : GroundedPipelineOutcome()

    data class Retry(val prompt: String) : GroundedPipelineOutcome()
}

/**
 * Phase B orchestrator: parse -> reconcile -> validate -> resolve. The
 * ViewModel consumes only this facade, which also makes the full pipeline
 * unit-testable end-to-end.
 */
class GroundedChatPipeline(
    private val parser: GroundedResponseParser,
    private val reconciler: CitationReconciler,
    private val validator: ClaimValidator,
    private val detector: UnsupportedClaimDetector,
    private val resolver: GroundingDispositionResolver
) {
    suspend fun run(
        finalResponse: String,
        retrievalResults: List<RetrievalResult>,
        answerId: String,
        attempt: Int,
        maxAttempts: Int
    ): GroundedPipelineOutcome {
        val parsed = parser.parse(finalResponse)
        if (parsed !is ParseResult.Success) return GroundedPipelineOutcome.Terminal(GroundingDisposition.Unresponsive, null, null)

        val response = parsed.response
        val reconciled = reconciler.reconcile(response, retrievalResults)
        val validated = validator.validate(response, reconciled, retrievalResults, answerId)
        val disposition = resolver.resolve(validated, attempt, maxAttempts)
        return when (disposition) {
            is GroundingDisposition.Retry -> GroundedPipelineOutcome.Retry(disposition.prompt)
            else -> GroundedPipelineOutcome.Terminal(disposition, response, validated)
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.chat.GroundedChatPipelineTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Wire ChatMessage, initGroundedChat, and the Phase B block in MainViewModel**

**`ChatMessage`** (`AiChatModels.kt`): add `val groundedAnswerId: String? = null` to the constructor. Gson round-trips it with the existing persistence path (`ChatRepository.saveSession`); verified — `ChatMessage.groundedResponse` already survives reload, and this id joins `answer_citations` for Task 13 footer hydration (spec §10).

**`initGroundedChat()`** — replace the current construction block with:

```kotlin
private fun initGroundedChat() {
    val ctx = applicationContext
    val dao = memoryDb.answerCitationDao()
    groundedPipeline = GroundedChatPipeline(
        parser = GroundedResponseParser,
        reconciler = CitationReconciler(SourceSegmentRepository(ctx)),
        validator = ClaimValidator(
            answerCitationDao = dao,
            embedder = onDeviceEmbedder(),  // reuse the embedder instance from initQueryPlanner
            sourceNoteExists = { fileName -> noteRepository.notesFlow.value.any { it.fileName == fileName } }
        ),
        detector = UnsupportedClaimDetector(),
        resolver = GroundingDispositionResolver(UnsupportedClaimDetector())
    )
    preCallRefuser = PreCallRefuser(ConflictDetector(temporalIndex, conceptGraphRepository))
}
```

Verify the exact embedder field by reading `initQueryPlanner` and reuse its instance (or construct the same way) so model files load once. Remove the old `citationValidator`/`unsupportedClaimDetector` fields and their references.

**Phase B replacement** — in the chat loop, replace the block that currently does `citationValidator!!.validate(parsed, lastRetrievalResults, answerId)` / `unsupportedClaimDetector!!.analyze(validated)` (L2989, L2994) with:

```kotlin
if (groundedChatEnabled && groundedPipeline != null && lastRetrievalResults.isNotEmpty()) {
    val outcome = groundedPipeline!!.run(
        finalResponse = finalResponse,
        retrievalResults = lastRetrievalResults,
        answerId = answerId,
        attempt = attemptIndex,
        maxAttempts = maxAttempts
    )
    when (outcome) {
        is GroundedPipelineOutcome.Retry -> {
            retryPrompt = outcome.prompt
            // fall through to the retry loop
        }
        is GroundedPipelineOutcome.Terminal -> {
            groundedResponse = outcome.response
            groundedAnswerId = answerId
            // UNVERIFIED / FULLY_VALIDATED -> display the answer; ABSTAIN /
            // HOLE_IN_EVIDENCE / UNRESPONSIVE -> display the refusal template
            // (per disposition; the templates live in Task 14).
        }
    }
}
```

The outer `maxAttempts` (already `2` when `groundedChatEnabled`) drives the retry loop; `attemptIndex` counts 0..1 so the resolver's `maxAttempts - 1` boundary produces RETRY at attempt 0 and terminal at attempt 1. Set `ChatMessage.groundedAnswerId = groundedAnswerId` on the stored message alongside `groundedResponse`.

- [ ] **Step 6: Compile and commit**

Run: `./gradlew :app:compileDebugKotlin` (expect SUCCESS — MainViewModel restored to compiling state with the new components).

```bash
git add app/src/main/java/com/noteflowai/app/data/chat/GroundedChatPipeline.kt app/src/test/java/com/noteflowai/app/data/chat/GroundedChatPipelineTest.kt app/src/main/java/com/noteflowai/app/data/chat/AiChatModels.kt app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt
git commit -m "feat(chat): grounded chat pipeline facade and phase B wiring in MainViewModel"
```

> Note: `MainViewModel` construction sites for `onDeviceEmbedder`/`noteRepository.notesFlow` and the `_chatMessages`/`saveCurrentChat` locals must be read directly in the file before editing (they are large files); this plan pins the integration points (L153-160, L2344-2365, L2900-3117, L3583-3602) that the SessionStart-verified fact sheet confirmed.

---

### Task 12: Citation superscript rendering in MarkdownRenderer

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/ui/components/MarkdownRenderer.kt` (add clickable-superscript pass in `appendInlineMarkdown`, support for `onCitationClick`)
- Test: `app/src/test/java/com/noteflowai/app/dag/CitationMarkupParserTest.kt` (new, parser test) — actually place in `app/src/test/java/com/noteflowai/app/data/chat/CitationMarkupParserTest.kt` alongside the other chat tests.

**Interfaces:**
- Consumes: `LinkAnnotation.Clickable`, `LinkInteractionListener`, `TextLinkStyles`, `BaselineShift.Superscript` (Compose UI 1.6.x via BOM 2024.02.01). `CitationMarkupParser` regex `\[(\d+)\]`.
- Produces: `object CitationMarkupParser { data class Marker(markerIndex: Int, start: Int, end: Int); fun scan(text: String): List<Marker> }`. `MarkdownRenderer(text, ..., onCitationClick: ((Int) -> Unit)? = null)` — adds a defaulted parameter (existing callers unaffected).

- [ ] **Step 1: Write the failing parser test**

```kotlin
package com.noteflowai.app.data.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class CitationMarkupParserTest {
    @Test
    fun `extracts citation markers with positions`() {
        val markers = CitationMarkupParser.scan("See [1] and [12] for details.")
        assertEquals(2, markers.size)
        assertEquals(1, markers[0].markerIndex)
        assertEquals(12, markers[1].markerIndex)
        assertEquals(4, markers[0].start)
        assertEquals(7, markers[0].end)
    }

    @Test
    fun `no markers yields empty`() {
        assertEquals(0, CitationMarkupParser.scan("plain text").size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.chat.CitationMarkupParserTest"`
Expected: FAIL — `CitationMarkupParser` unresolved.

- [ ] **Step 3: Implement the parser**

```kotlin
package com.noteflowai.app.data.chat

object CitationMarkupParser {
    data class Marker(val markerIndex: Int, val start: Int, val end: Int)
    private val PATTERN = Regex("\\[(\\d+)\\]")

    fun scan(text: String): List<Marker> =
        PATTERN.findAll(text).map {
            Marker(markerIndex = it.groupValues[1].toInt(), start = it.range.first, end = it.range.last + 1)
        }.toList()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.chat.CitationMarkupParserTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Wire renderer**

Add `baselineShift/superscript` + `addLink` in `appendInlineMarkdown` (the private inline builder). The exact insert: after building the styled `AnnotatedString.Builder`, if `onCitationClick != null`, scan for markers and convert each `[N]` into a superscript `LinkAnnotation.Clickable`:

```kotlin
// in MarkdownRenderer (public composable signature gains the new param):
@Composable
fun MarkdownRenderer(
    text: String,
    textColor: Color,
    modifier: Modifier = Modifier,
    onChecklistToggle: ((Int) -> Unit)? = null,
    onCitationClick: ((Int) -> Unit)? = null,
    // ... existing params preserved
)
```

Inside `appendInlineMarkdown`, before pushing the final `AnnotatedString`, run a marker pass:

```kotlin
val markers = onCitationClick?.let { CitationMarkupParser.scan(rawText) }
if (markers != null) {
    markers.asReversed().forEach { m ->
        val link = LinkAnnotation.Clickable(
            tag = "citation-${m.markerIndex}",
            styles = TextLinkStyles(
                style = SpanStyle(color = citationColor, baselineShift = BaselineShift.Superscript)
            ),
            linkInteractionListener = LinkInteractionListener.Clickable {
                onCitationClick(m.markerIndex)
            }
        )
        builder.addLink(link, m.start, m.end)
    }
}
```

Render the final `AnnotatedString` with `ClickableText` when `onCitationClick != null` so taps on the superscript resolve links; otherwise keep the existing `Text` path (no behavior change for existing callers such as `NoteDetailContent`).

- [ ] **Step 6: Compile + commit**

Run: `./gradlew :app:compileDebugKotlin`
Expected: SUCCESS.

```bash
git add app/src/main/java/com/noteflowai/app/data/chat/CitationMarkupParser.kt app/src/test/java/com/noteflowai/app/data/chat/CitationMarkupParserTest.kt app/src/main/java/com/noteflowai/app/ui/components/MarkdownRenderer.kt
git commit -m "feat(chat): clickable superscript citation markers in markdown renderer"
```

---

### Task 13: ChatScreen grounded UI (superscripts, footer, passage-open navigation)

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/chat/ChatScreen.kt` (ChatBubble params + grounded UI block L1313-1428, footer chips)
- Modify: `app/src/main/java/com/noteflowai/app/ui/NoteFlowApp.kt` (CHAT branch passes `onOpenNote`)
- Modify: `app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt` (footer excerpt hydration for reload; §10)

**Interfaces:**
- Consumes: `ChatMessage.groundedAnswerId` (T11), `GroundedChatResponse.citations/claims`, `AnswerCitationDao.getByAnswerId(answerId)`, `onNoteClick`/select+`Screen.NOTE_DETAIL` navigation pattern, `ChatScreen(viewModel, onBack, ...)`.
- Produces: footer chip mapping type `data class GroundedCitationFooter(chunkId: String, sourceId: String, quoteText: String?)` (new file `app/src/main/java/com/noteflowai/app/data/chat/GroundedCitationFooter.kt`); `NoteFlowApp` passes `onOpenNote: (String) -> Unit` into `ChatScreen`.

> Reload precedence (§10): render the superscript + footer STRUCTURE from `groundedResponse.citations` (the envelope is the source of truth), and hydrate the footer EXCERPT TEXT from `answer_citations.quoteText` via `groundedAnswerId`. Envelope-first, DB-second; never the reverse. When the DB lookup is empty (e.g. Gson-reloaded message with no `groundedAnswerId`), render the envelope citation entry without excerpt text rather than a blank row.

- [ ] **Step 1: Add footer model**

```kotlin
// GroundedCitationFooter.kt
package com.noteflowai.app.data.chat

data class GroundedCitationFooter(
    val chunkId: String,
    val sourceId: String,
    val quoteText: String? = null
)
```

- [ ] **Step 2: ChatBubble signature + footer + superscript wiring**

Change `ChatBubble(message, ..., onNoteClick, ...)` to add `onCitationClick: (Int) -> Unit` and a `footerExcerpts: List<GroundedCitationFooter> = emptyList()` param. In the bubble's message text render (presently `Text(message.content)`), replace with `MarkdownRenderer(text = message.content, ..., onCitationClick = onCitationClick)`. Below the text, when `message.groundedResponse?.citations?.isNotEmpty() == true`, render a footer strip of chips:

```kotlin
message.groundedResponse?.citations?.forEachIndexed { idx, citation ->
    val excerpt = footerExcerpts.firstOrNull { it.chunkId == citation.chunkId }?.quoteText
    // chip: "cite-${idx+1} · ${citation.sourceId} ${excerpt?.let { "· \"$it\"" } ?: ""}"
    // onClick -> onCitationClick(idx) (opens the source note)
}
```

Fix the pre-existing compile break from Task 1 in the grounded UI block: replace `grounded.claims.any { it.source_segment_ids.isEmpty() }` with `message.groundedDisposition == "UNVERIFIED"` (add that flag on `ChatMessage` when storing a terminal UNVERIFIED outcome in Task 11; default null → no badge). Keep the abstention banner (`grounded.abstained`/`abstention_reason`) and suggested-actions block as-is.

- [ ] **Step 3: ViewModel footer hydration**

In `MainViewModel`, when a chat session is loaded or a grounded message is added, populate `groundedFooters: StateFlow<Map<String, List<GroundedCitationFooter>>>` keyed by `groundedAnswerId`:

```kotlin
private suspend fun hydrateFooter(answerId: String) {
    val rows = memoryDb.answerCitationDao().getByAnswerId(answerId)
    _groundedFooters.value = _groundedFooters.value + (answerId to rows.mapNotNull {
        GroundedCitationFooter(chunkId = it.sourceSegmentId, sourceId = it.locationType, quoteText = it.quoteText)
    })
}
```

(Adjust the sourceId from `locationType` — the entity's `locationType` stores the SourceType name; the footer's `sourceId` is used only as a label here, matching the entity schema: `sourceSegmentId` holds the chunkId.) ChatScreen reads `viewModel.groundedFooters[message.groundedAnswerId]` in the bubble body.

- [ ] **Step 4: NoteFlowApp navigation**

In `NoteFlowApp.kt`, the `Screen.CHAT` branch currently is `ChatScreen(viewModel = viewModel, onBack = { currentScreen = previousScreen })`. Pass a note-opener that SELECTS the note then NAVIGATES (mirrors the GRAPH-branch pattern at L309-316):

```kotlin
ChatScreen(
    viewModel = viewModel,
    onBack = { currentScreen = previousScreen },
    onOpenNote = { fileName ->
        viewModel.selectNoteByFileName(fileName)
        currentScreen = Screen.NOTE_DETAIL
    }
)
```

ChatScreen wires `onCitationClick = { idx -> message.groundedResponse?.citations?.getOrNull(idx)?.let { onOpenNote(it.sourceId) } }` so a superscript or footer chip opens the exact source note (verifiable promise: opens correct source + shows quoted passage; note-body scroll-to-highlight remains out of scope per spec §16).

- [ ] **Step 5: Compile + commit**

Run: `./gradlew :app:compileDebugKotlin`
Expected: SUCCESS.

```bash
git add app/src/main/java/com/noteflowai/app/data/chat/GroundedCitationFooter.kt app/src/main/java/com/noteflowai/app/ui/screens/chat/ChatScreen.kt app/src/main/java/com/noteflowai/app/ui/NoteFlowApp.kt app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt
git commit -m "feat(chat): citation superscripts, footer chips, and passage-open navigation"
```

---

### Task 14: strings, integration regression, exit-criteria verification

**Files:**
- Modify: `app/src/main/res/values/strings.xml` (refusal templates + footer labels)
- Run: full unit suite + exit-criteria mapping

**Interfaces:**
- Consumes: all Tasks 1-13.
- Produces: tested, integrated feature.

- [ ] **Step 1: Add string resources**

```xml
<string name="grounding_refusal_empty">I could not find any relevant notes to answer that. Try rephrasing or asking about something you have captured.</string>
<string name="grounding_refusal_below_floor">No retrieved source was relevant enough to answer confidently. I am not answering to keep the grounding guarantee.</string>
<string name="grounding_refusal_conflict">Your notes contain conflicting information on this topic. I am not answering rather than risk propagating the conflict.</string>
<string name="grounding_hole_in_evidence">Your notes do not contain enough verifiable evidence for this answer. I am abstaining.</string>
<string name="grounding_unresponsive">I could not produce a well-formed grounded answer. Please try again.</string>
<string name="grounding_unverified_badge">Some claims could not be verified against your notes.</string>
<string name="grounding_footer_open_note">Open source</string>
```

- [ ] **Step 2: Run the full unit suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — all existing tests (retrieval eval/gate, Phase 4 timeline, Phase 1-3 suites) plus the new Tasks 1-13 tests are green. Resolution: Tasks 5-11 replace old code; any remaining reference to `source_segment_ids` or `CitationValidator` in tests must be updated in the same task that removed them.

- [ ] **Step 3: Exit-criteria mapping (verification, not new code)**

Map spec §15 to test files on disk:
- Every user-data claim traceable → `ClaimValidatorTest` (entailment quote + embed paths) + `GroundedChatPipelineTest` (end-to-end FullyValidated).
- Refusal when no evidence → `PreCallRefuserTest` (EMPTY_RETRIEVAL/BELOW_FLOOR/CONFLICT).
- Retry specific-reason → `UnsupportedClaimDetectorTest` (closing-note 3).
- No citation points at missing source → `CitationReconcilerTest` (V2/V3/V4) + `ClaimValidatorTest` (sourceNoteExists).
- Superscript opens source → covered by UI wiring (Task 13) + `MarkdownRenderer` compile; device smoke recommended per prior phases.

- [ ] **Step 4: Manual smoke (deferred to user)** — the user reproduces on-device per the standing debugging rule: toggle `enable_grounded_memory_chat` on, ask a grounded question, observe superscript + footer + tap-to-open + abstention on an unsupported prompt.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/values/strings.xml
git commit -m "feat(chat): grounding refusal templates and footer strings"
```

---

### Plan self-review

**Spec coverage:** §2 models → Task 1; §3 V1-V6 validity table → Tasks 3-5 (V6 parser leniency in Task 3); §4 entailment → Task 2 constants + Task 5; §5 reconciler + quote spans → Task 4 (+ Task 2); §6 disposition + two-phase → Tasks 6, 7, 10, 11; §7 utilities → Task 2; §8 refusal templates → Task 14; §9 numbered-list prompt contract → Task 8; §10 reload precedence → Tasks 11 + 13; §11 citation UI → Tasks 12, 13; §12 wiring → Tasks 10, 11; §13 component map → Tasks 1-13; §14 tests → Tasks 1-13 + §15 exit criteria → Task 14. §16 out-of-scope (scroll-to-highlight) honored; §17 risks → folded into Global Constraints + per-task notes.

**Type consistency:** `Citation(id=cite-N)` ↔ `Claim.citationIds` ↔ `ValidatedClaim.invalidCitationReasons[cite-N]` ↔ `UnsupportedClaimDetector` index lookup ↔ `CitationMarkupParser` `[N]` markers are all consistent. `GroundedDisposition` sealed variants match resolver output and pipeline outcome mapping. `ChatMessage.groundedAnswerId` ↔ footer hydrate via `AnswerCitationDao.getByAnswerId`. `PreCallRefuser` produced `GroundingRefusalReason` enum consumed only in MainViewModel (templates in Task 14). One intentional plan decision: `ReconciledCitation.conflictNote` is reserved (always null in Phase B; T3 is Phase A). No placeholder steps remain.