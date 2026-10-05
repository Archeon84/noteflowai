# Phase 6: Citation and Grounding Enforcement — Design (Spec)

**Date:** 2026-08-26
**Phase:** 6 of `docs/superpowers/plans/NoteFlowAI-Agentic-Implementation-Guide.md` (guide lines 1040-1117)
**Status:** Design approved by user; awaiting written-review gate before writing-plans.

## 1. Purpose

Phase 6 makes every user-data claim in a grounded chat answer trace back to a specific,
valid, openable source passage. It reworks the Phase 5 partial grounding stack (structured
output + a weak validator) into the guide-literal schema with two-phase enforcement,
honest failure handling, and a citation UI that lets the user open the exact source.

This is NOT greenfield: Phase 5 already built `GroundedChatResponse`/`Claim`,
`CitationValidator`, `UnsupportedClaimDetector`, the `answer_citations` Room table, and
per-message RAG source chips. Phase 6 replaces the weak parts with the guide's schema and
adds the enforcement breadth and the citation UI. Guide requirements preserved verbatim:
every user-data claim has a valid citation; citation taps open the correct passage;
unsupported claims are removed or rewritten; empty retrieval produces a useful refusal;
conflicting sources are surfaced.

## 2. Guide-literal schema

The guide defines citations as first-class objects. Rework `GroundedChatModels.kt`
accordingly.

### 2.1 `Citation`

```kotlin
data class Citation(
    val id: String,                        // unique within the response, e.g. "cite-1"
    val sourceType: SourceType,            // enum, not string
    val sourceId: String,                  // parent note/recording/document identifier
    val chunkId: String,                   // SourceSegment id (the retrievable unit)
    val quoteStart: Int?,                  // advisory UTF-16 offset into chunk text (model/hydrated)
    val quoteEnd: Int?,                    // advisory UTF-16 offset into chunk text (model/hydrated)
    val relevanceScore: Float? = null      // retrieval score, surface for debugging
)
```

**Type-safety decision (user, Section 1):** `sourceType` is the `SourceType` enum directly,
not a `.name` string. `relevanceScore` is nullable — the LLM does not reliably emit it and
retrieval may not have a score for every passage; null is honest, not a failure.

**Quote-offset semantics (user, Section 1):** `quoteStart`/`quoteEnd` are **UTF-16 code-unit
offsets** into `chunkId`'s segment text, matching Kotlin `String.substring` semantics
(emoji/CJK-safe on a per-code-unit basis). These are **advisory**: model offsets are
unreliable, so the app recomputes the canonical quote range (`GroundingSupport.computeQuoteSpan`,
Section 7) and stores/displayed quotes always come from the app computation. Offsets are
validated (0 <= start <= end <= text length); out-of-range or inverted offsets are ignored
and the app falls back to the computed span.

### 2.2 `GroundedChatResponse` rework

```kotlin
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

data class Claim(
    val text: String,
    val citationIds: List<String> = emptyList(),   // references citations[].id
    val memory_object_ids: List<String> = emptyList(),  // kept separate (user decision)
    val uncertainty: UncertaintyLevel = LOW,             // enum, not string
    val confidence: Float? = null                       // optional per-claim score
)

enum class UncertaintyLevel { LOW, MEDIUM, HIGH }
```

**Description/justification:** the guide's example JSON has `claims: [{text, citationIds,
uncertainty}]`. Phase 5's inline `source_segment_ids` are removed; claims now reference
citations by `id`. Memory-object references move to the **separate** `memory_object_ids`
list (user decision) — validated as a distinct channel, OR with citationIds for claim
support. `uncertainty` is the `UncertaintyLevel` enum (Section 1 type-safety measure).
`Gson` bypasses constructors (documented at `AiChatModels.kt:18`), so **no `init require`
invariant enforcement** — terminal-state exclusivity and linkage integrity live in the
validator layer (Sections 4, 6).

**Terminal states are mutually exclusive (user, Section 1):** `abstained` OR
`needs_clarification`, never both. Enforced via the `GroundingDisposition` sealed projection
(Section 6 disposition resolver), which is the single point where a terminal state is
declared — the raw model `GroundedChatResponse` may legally contain both flags, but the
disposition resolver picks one and the UI consumes only the disposition.

### 2.3 Suggested actions

`SuggestedAction` (`type`/`label`/`payload`) is unchanged. `OPEN_SOURCE` is produced only
with a valid target — the action resolver requires a citation that passed validation and
carries the resolved chunk, so a suggested "open source" action can never point at nothing.

## 3. Scope: valid citation definitions

A citation is **structurally valid** when ALL of the following hold (each maps to a guide
`validate` requirement):

| # | Rule | Guide requirement |
|---|------|-------------------|
| V1 | `citation.id` exists in the response and is referenced by at least one `Claim.citationIds` | unknown rejected |
| V2 | `chunkId` resolves to a `RetrievalResult` present in the retrieved context for this turn | id in retrieved context |
| V3 | `sourceId` resolves to an existing source note/recording/document (DB existence; `note_`-prefixed segment IDs additionally map to an actual `NoteFile`) | source note exists / open correct source |
| V4 | `chunkId`'s `SourceSegment` row exists in the DB (`note_`-prefixed IDs resolve via the computed block mapping, Section 7) | chunk exists |
| V5 | The claim text is supported by the cited chunk content (entailment check, Section 4) | claim supported by cited content |
| V6 | A structurally-invalid citation reference is **rejected distinctly**, never silently dropped | malformed output doesn't crash |

A citation that fails any of V2-V6 fails **reconciliation** (marked `resolved=null`,
`authoritative=false`, Section 5). V1 is enforced at claim level — a claim whose entire
`citationIds` list resolves to nothing fails the claim's support check.

## 4. Entailment (V5)

Claim-to-source support is decided by:

```
entailment(claim, chunkText) =
    embeddingCosine(claim, chunkText) >= EMBED_COSINE_FLOOR (0.58)
    OR quotePresence(chunkText, claimSpan)          // >= QUOTE_MIN_CHARS (12) contiguous chars
                                                   // OR >= 70% of content tokens overlap
```

- **Embedding channel:** on-device `OnDeviceEmbedder` (multilingual-MiniLM-L12-v2, 384-d,
  L2-normalized, mean pooling). Cosine is computed over the segment text and the claim text.
  `EMBED_COSINE_FLOOR = 0.58f` matches the Phase 5 `MIN_OVERLAP_RATIO` spirit but replaces
  the word-overlap heuristic with semantic similarity.
- **Quote channel:** the app uses the computed quote span (Section 7) OR scans the chunk for
  the claim's content tokens when no span is available. `QUOTE_MIN_CHARS = 12`.
- **Embedder unavailable (`isReady() == false` or embed returns null):** **conservative
  rejection** — the entailment check fails closed (no false accept). This is the Phase 2
  adjudicated decision: an unavailable embedder must not silently validate claims.
- **Tokenization:** hoisted regexes (current `CitationValidator` `NON_ALNUM`/`WHITESPACE`) are
  reused for the content-token fallback path.

**Failure checks fail-closed:** any component error (embed null, DB lookup exception) counts
as unsupported, not as validated.

## 5. `CitationReconciler` and quote computation

### 5.1 Reconciler semantic

The model emits `citations` that REFERENCE the retrieved context by `id`. The reconciler
resolves each citation to its retrieval result and DB rows, and assigns an
`authoritative` flag. Resolution failure is **kept, marked invalid** (never filtered out):
the claim validator sees an `invalid` reference distinctly rather than a missing one. This is
decision (B) from Section 2 of the design discussion — the user explicitly rejected
"silently drop bad citations."

```kotlin
data class ReconciledCitation(
    val citation: Citation,
    val resolved: RetrievalResult?,      // null when V2 fails
    val segment: SourceSegment?,         // null when V4 fails
    val computedQuoteRange: QuoteRange?, // app-computed (Section 7); null when chained check fails
    val authoritative: Boolean,          // true only when all of V2/V3/V4 pass
    val conflictNote: String? = null     // Conflicts via Trigger 3 (Section 8)
)
```

### 5.2 Quote computation timing (user, Section 2-issue-5)

Explicit ownership: `GroundingSupport.computeQuoteSpan(...)` computes the canonical
`QuoteRange` (UTF-16 offsets) from either (a) the model's advisory offsets when they pass
validation, or (b) a content-token search within the chunk. It is computed ONCE. `stored`
quotes and `displayed` quotes both come from `computeQuoteSpan`, so the UI never shows a
quote the app cannot produce from the chunk text.

## 6. Disposition and pipeline

The guide's refusal breadth is the 5 triggers. Two-phase pipeline (user, Section 2-issue-1):
triggers that do NOT need a model response run **Phase A (pre-call)**; triggers that need the
model's parsed output run **Phase B (post-call)**.

### Phase A — pre-call (before the LLM call)

Runs when retrieval has already populated `lastRetrievalResults` for this turn:

| Trigger | Check |
|---------|-------|
| **T1: empty retrieval** | no `RetrievalResult`s for the turn → refuse from template `USE_GENERAL_KNOWLEDGE_REFUSAL` |
| **T2: retrieval score below floor** | all results below `GROUNDING_MIN_SCORE` (Phase 5 `minimumScore`) → refuse |
| **T3: conflicting sources** | `ConflictDetector.findConflicts(maxResults=3)` surfaces at least one conflict → refuse with "N sources conflict" template |

Phase A result is a `PreCallDecision`: `REFUSE(template, reason)` or `PROCEED`. On REFUSE,
the LLM call is skipped entirely.

### Phase B — post-call

Runs after `Gson().fromJson(finalResponse, GroundingChatResponse::class.java)` parses
successfully:

```
parser → reconciler → ClaimValidator → Trigger 4 → UnsupportedClaimDetector → Trigger 5
                                                    → GroundingDispositionResolver
```

- **Parser:** `GroundedResponseParser.parse(json): ParseResult` — strict `Gson` parse wrapped
  in a result type. Malformed JSON → `ParseResult.Failure` → terminal `GroundingDisposition.UNRESPONSIVE`
  with "I couldn't parse the model response." (never a crash — guide "malformed output doesn't crash").
- **Reconciler:** produces `List<ReconciledCitation>` (Section 5).
- **`ClaimValidator`** (single validator, user decision): validates each claim's
  `citationIds` against the reconciled set (V1), memory-object channel separately, and runs
  the entailment check (Section 4). Produces `ValidatedResponse` with per-claim
  `isValid`/`reason` and per-citation validation.
- **Trigger 4 (**user, Section 2-issue-2**):** response-level — `uncertainty == HIGH` AND zero
  claims have a valid quote-range → `HOLE_IN_EVIDENCE` disposition. (Not a query-intent
  classifier; this is a data-conservative response-level check.)
- **`UnsupportedClaimDetector`**: reworked to drive **Trigger 5 only** — it flags *whether*
  any claim is unsupported after validation and computes the retry prompt *content* (Section
  9). It does NOT decide the terminal state; that stays with the resolver.
- **`GroundingDispositionResolver`** (thin, single owner of the terminal state AND the
  retry-vs-terminal decision, user, Section 2-issue-3): projects the pipeline outcome to one
  sealed `GroundingDisposition`. Trigger-4 eligibility is evaluated here too (a
  `HOLE_IN_EVIDENCE` input). The RETRY decision is made here: when the detector flags
  unsupported claims AND `maxAttempts` (2) is not exhausted, it emits `RETRY`; when the
  detector flags unsupported claims AND the final retry attempt is exhausted, it emits the
  terminal `UNVERIFIED` with those unsupported claims. The detector never emits a
  disposition.

```kotlin
sealed class GroundingDisposition {
    data object FULLY_VALIDATED : GroundingDisposition()
    data class UNVERIFIED(val unsupportedClaims: List<ValidatedClaim>) : GroundingDisposition()
    data class ABSTAIN(val reason: String) : GroundingDisposition()
    data class RETRY(val retryPrompt: String) : GroundingDisposition()
    data object HOLE_IN_EVIDENCE : GroundingDisposition()   // Trigger 4
    data object UNRESPONSIVE : GroundingDisposition()       // malformed parse
}
```

- **Trigger 5:** if `RETRY` and `maxAttempts` (2) not exhausted → re-run with the retry
  prompt. `maxAttempts=2` (one retry) keeps a cap like Phase 5's `maxAttempts=2`.
- Terminal dispositions (`FULLY_VALIDATED`, `UNVERIFIED`, `ABSTAIN`, `HOLE_IN_EVIDENCE`,
  `UNRESPONSIVE`) are mutually exclusive by construction of the sealed type.

The current `ChatMessage.groundedResponse` envelope stays the full-envelope store (Section 10).

## 7. `GroundingSupport` utilities

Small, stateless, testable helpers:

- `computeQuoteSpan(segmentText, advisoryStart?, advisoryEnd?): QuoteRange?` — UTF-16 offsets.
  If advisory values are present and in range → use them; else scan for the claim token
  window → first match; else null.
- `QuoteRange(start: Int, end: Int)` — code-unit range into the segment text.
- `checkQuotePresence(chunkText, claim): Boolean` — the quote-channel entailment (Section 4).
- `noteBlockIndexFromSegmentId(segmentId): Int?` — parse `note_<file>_block<index>_...` /
  read `metadataJson.blockIndex` to map a NOTE segment back to the `$index`th paragraph of the
  source note (paragraph split by `\n\n+`). This is the passage-open mechanism for notes.
- `resolveSourceId(segment): String?` — the parent `sourceId` for navigation.

## 8. Refusal behaviors

Templates are consolidated in a new `grounding_templates` string-resource set:

| Trigger | Template (string resource) | Guide intent |
|---------|---------------------------|--------------|
| T1 empty retrieval | `I don't have any notes covering this. Can you rephrase or add information first?` | useful refusal |
| T2 below-floor | `I found some notes, but none of them cover this confidently enough to answer.` | — |
| T3 conflict | `I found <N> notes mentioning this, but they conflict.` | conflicting sources surfaced |
| Trigger 4 (HOLE_IN_EVIDENCE) | `I couldn't find solid evidence for that in your notes.` | uncertainty guard |
| UNRESPONSIVE | `I couldn't produce a grounded answer. Please try again.` | malformed answer |

The guide's preferred refusal template ("I found two notes mentioning the supplier, but
neither records the final price.") is the T3 conflict template shape (T3 fills `<N>` and the
conflict summary).

Suggested-action templates (`SUGGESTED_ACTIONS`) unchanged, but `OPEN_SOURCE` is gated on
Section 2.3.

## 9. Prompt contract — citation index convention (user closing note 1, locked)

`GroundingPromptBuilder.buildGroundingPrompt(retrievalResults)` is reworked so the citation
injection is a **numbered list** and marker usage is explicit:

- Inject each distinct retrieval result as `N. <text excerpt>` (1-based `N`), NOT a
  comma-joined ID list. The model's `[N]` markers are **exactly** this 1-based index into the
  injected numbered list.
- The prompt states verbatim: "Cite claims with [N] markers where N is the number of the
  evidence item from the numbered list above. Use [1], [2] exactly — no other citation
  syntax."
- `citations[].id` values are `cite-<N>` (derived from the same index) so citation markers and
  citation objects stay consistent.
- The numbered list includes the segment `text` excerpt, the source note title (`sourceId`),
  and the `chunkId` (`sourceSegmentId`), e.g.
  `1. [segmentId=note_meeting_block2_xxx] [note=meeting-2026-08-01] "quote excerpt…"`.

The parser accepts `[N]` markers (regular expression `\[(\d+)\]`) and resolves `N` → the
`cite-<N>` citation id. Documented contract: **the LLM MUST place `[N]` in the claim text
prose; the app resolves the marker to a citation; a marker that does not match a numbered
item is an invalid reference (V1/V2).**

## 10. Reload source-of-truth precedence (user closing note 2)

- **Primary source of truth for citation UI on reload:** `ChatMessage.groundedResponse`
  (Gson envelope, full `citations`/`claims`). Superscripts and footer chips are reconstructed
  from this envelope first.
- **`answer_citations` table** (Room) is the **secondary** source: it hydrates the footer
  chip **excerpt text** (`quoteText`) when the message is reloaded from disk and the
  envelope's segment text is not held in memory. It stores the validated subset of the
  envelope's citations (V2-V5 pass), not the raw envelope.
- Precedence documented in the spec: on reload, reconstruct from `groundedResponse` first;
  fall back to `answer_citations` for excerpt text. Never the reverse.

### 10.1 Persistence schema for citations

Add `quoteText: String?` to `AnswerCitation` (new column) → **Room DB migration v7→v8**
(`MemoryDatabase.kt` version bump 7→8, `MIGRATION_7_8` with
`ALTER TABLE answer_citations ADD COLUMN quoteText TEXT`). Retroactive backfill: after
migration, existing rows get `quoteText` from the normalized source segment text when the
segment still exists; rows without a resolvable segment keep `quoteText = null`.

`AnswerCitation` (v7) already carries `answerId`, `sourceSegmentId`, `claimIndex`,
`locationType`, `startMs/endMs`, `pageNumber`, `supportStatus` (VALIDATED/INVALID/PENDING),
`url`, `createdAt`. Phase 6 stores one `AnswerCitation` row per validated citation with
`supportStatus = VALIDATED`, `quoteText` = the app-computed quote span substring.

## 11. Citation UI

### 11.1 Superscripts

Parsed from the answer prose (`[N]` markers) by a `CitationMarkupParser` (Section 9). Rendered
in `ChatScreen` assistant content: the `MarkdownRenderer` pass recognizes `[N]` markers and
emits superscript `Text` spans (small, colored, clickable) instead of literal `[N]`. Clicking
a superscript opens the source (Section 11.3). Superscripts only render for citations that
survived validation (valid `ReconciledCitation`s); invalid references render nothing
(they were filtered by the disposition/UI gate).

### 11.2 Source footer

Per-message chips below the answer (extends the existing per-message RAG source chips at
`ChatScreen.kt:1217-1294`). Each chip: source title (note/recording/document name),
relevance, and the excerpt quote (`quoteText` from the validated citation). Tapping a chip
opens the source. Chips are derived from `GroundedChatResponse.citations` validated set, not
from the raw `RetrievalResult` list.

### 11.3 Passage open

Tapping a superscript or footer chip:
- NOTE sources: open `Screen.NOTE_DETAIL` via `NoteFlowApp`'s `selectedNote` navigation
  (same `onNoteClick` path). The target note = `sourceId`; the app additionally resolves the
  `chunkId`'s block index (`noteBlockIndexFromSegmentId`) and scrolls to the passage when
  rendering supports it. Current `NoteDetailContent` renders the note body as ONE
  `MarkdownRenderer` pass (not per-block addressable items), so the **verifiable promise is
  "opens the correct source note and shows the quoted passage"** — the quote is displayed in
  the footer chip and (where the renderer allows) an initial scroll to the block is attempted.
  Full scroll-to-highlight inside the note body is explicitly OUT of scope for Phase 6
  (would require refactoring `MarkdownRenderer` into addressable block items); the block-index
  mapping exists and is wired, so a later phase can upgrade to highlight.
- AUDIO/VIDEO sources: open the source with `startMs/endMs` seek position (existing
  `SourceType`/`AnswerCitation` time fields).
- PDF/DOCUMENT/OCR: open the source with `pageNumber` when present.

This satisfies the exit criterion "citation taps open correct source" (the correct source =
the source note) and, for notes, surfaces the exact passage via the block index.

## 12. Wiring

`MainViewModel` (current grounded chat loop L2900-3110) changes:

1. **Phase A short-circuit** before `streamResponse`/`getResponse` when
   `groundedChatEnabled && lastRetrievalResults` available: T1/T2/T3 checks produce a
   refusal `ChatMessage` and skip the LLM call.
2. Grounded parse (`Gson().fromJson(finalResponse, GroundedChatResponse::class.java)`) →
   `CitationReconciler.reconcile(...)` → `ClaimValidator.validate(...)` →
   `UnsupportedClaimDetector.buildRetryPrompt(...)` on needs-retry → `GroundingDispositionResolver`.
3. `maxAttempts=2` retry loop for Trigger 5.
4. `appendCitations` (L3593) becomes the validated-citation footer (Section 11), using the
   reconciled citations, not raw web-search-only sources.
5. On terminal disposition, `ChatMessage.groundedResponse` is set to the (possibly
   reconstructed) envelope; validated citations are persisted to `answer_citations` with
   `quoteText`.

### 12.1 Feature flag

Existing `enable_grounded_memory_chat` (`FeatureFlags.kt`, default **false**) gates the entire
Phase 6 pipeline. No new flag.

## 13. Component map

| Component | New/Changed | Responsibility |
|-----------|-------------|----------------|
| `GroundedChatModels.kt` | Changed | Guide-literal `Citation`, reworked `GroundedChatResponse`/`Claim`, `UncertaintyLevel`, `ReconciledCitation` |
| `GroundedResponseParser` | New | Strict JSON parse → `ParseResult` (no crash on malformed) |
| `CitationReconciler` | New | Map citations → `ReconciledCitation` (resolved/authoritative, V2-V6) |
| `ClaimValidator` | Changed | Single claim validator (V1 + entailment V5 + memory channel) |
| `CitationValidator` | Replaced | Folded into `ClaimValidator` + `CitationReconciler` |
| `UnsupportedClaimDetector` | Changed | Trigger 5 detection only; `buildRetryPrompt` kept |
| `GroundingDispositionResolver` | New | Single owner of terminal `GroundingDisposition` (exclusive states) |
| `GroundingSupport` | New | `computeQuoteSpan`/`checkQuotePresence`/`noteBlockIndexFromSegmentId`/`resolveSourceId` |
| `GroundingPromptBuilder` | Changed | Numbered-list citation injection + exact `[N]` contract (Section 9) |
| `CitationMarkupParser` | New | `[N]` marker regex → citation reference map |
| `CitationValidator` (old) | Deleted | Superseded; delete after new pipeline green |
| `AnswerCitation` + migration | Changed | `quoteText` column; Room v7→v8 |
| `ChatScreen.kt` | Changed | Superscript render + footer chips + tap-to-open |
| `MarkdownRenderer.kt` | Changed | `[N]` → superscript span pass |
| `MainViewModel.kt` | Changed | Phase A/B wiring, disposition, persistence |
| `strings.xml` | Changed | `grounding_templates_*` + UI labels |

## 14. Tests

### 14.1 Unit

- **`CitationMarkupParserTest`** — parses `[1]`, `[2]`; rejects `[0]`, `[x]`, unnumbered
  brackets; out-of-range marker → invalid reference; answer with no markers → no citations.
- **`GroundingPromptBuilderTest`** — numbered list (1., 2., …); excerpt text present; exact
  `[N]` verbatim instruction present; segment ID + note title present; no comma-joined ID list.
- **`GroundingSupportTest`** — `computeQuoteSpan` accepts valid advisory offsets, rejects
  out-of-range/inverted; token-window fallback; `checkQuotePresence` ≥12-char quote passes,
  short/skim quote fails; `noteBlockIndexFromSegmentId` parses `block<index>` and
  `metadataJson.blockIndex`.
- **`CitationReconcilerTest`** — citation with unretrieved `chunkId` → `resolved=null,
  authoritative=false` (kept, not dropped); nonexistent source note → V3 fail; nonexistent
  segment row → V4 fail; valid → `authoritative=true` + `computedQuoteRange` present.
- **`ClaimValidatorTest`** — V1: unknown citation id rejected; claim with all-invalid
  `citationIds` → `isValid=false`; memory-object OR channel; entailment via embed cosine
  (≥0.58) and via quote presence; embedder-unavailable → conservative reject; malformed
  envelope never crashes.
- **`UnsupportedClaimDetectorTest`** — unsupported ratio → Trigger 5 RETRY; empty claims →
  no retry; `buildRetryPrompt` output is specific (below).
- **Retry-specificity test (user closing note 3):** `buildRetryPrompt(validated)` whose
  validated set contains `Citation [2] chunkId 'seg-4' not in retrieved context` produces a
  prompt that contains that exact failure reason string (assert `prompt.contains("Citation
  [2] chunkId 'seg-4' not in retrieved context")` — a generic "fix your citations" does NOT
  pass).
- **`GroundingDispositionResolverTest`** — abstained+clarification both true → ONE terminal
  disposition (mutual exclusivity); malformed parse → `UNRESPONSIVE`; trigger 4
  (HIGH + no valid quote range) → `HOLE_IN_EVIDENCE`; unsupported within threshold →
  `UNVERIFIED`; supported → `FULLY_VALIDATED`; retry-eligible → `RETRY`.
- **`AnswerCitation` migration test** — v6→v7 baseline; v7→v8 adds `quoteText`; backfill
  pragma-checked (`table_info`).

### 14.2 DB migration

`MemoryDatabaseMigrationV7ToV8Test` (mirror `MemoryDatabaseMigrationTest` pattern):
`helper.createDatabase(TEST_DB, 7)` → `runMigrationsAndValidate(..., 8, true)` → assert
`quoteText` column exists, old rows have `quoteText = null`, new inserts with `quoteText`
round-trip.

### 14.3 Integration / end-to-end

- `GroundedChatPipelineTest` — real model-less fixture pipeline: retrieval fixture →
  reconciler → validator → disposition; empty retrieval → `REFUSE` (T1, LLM never called);
  below-floor → T2; conflict fixture → T3; well-formed answer → `FULLY_VALIDATED` with
  validated citations persisted to `answer_citations`; malformed JSON → `UNRESPONSIVE`.
- `ChatScreenCitationTest` (Robolectric) — answer with `[1]` renders superscript; footer chip
  shows quote; tapping chip calls the note-open callback with the source note.
- `GroundedRetryLoopTest` — retry re-invokes the pipeline; `maxAttempts=2` cancels after one
  retry; the retry prompt passed to the model carries the specific failure reason.

## 15. Exit criteria mapping

| Guide exit criterion | Spec coverage |
|----------------------|---------------|
| Every user-data claim has a valid citation | `ClaimValidator.isValid` gate; `AnswerCitation` rows VALIDATED only |
| Citation taps open correct passage | Section 11.3: NOTE→source note + block-index passage; audio→seek; PDF→page |
| Unsupported claims removed or rewritten | Trigger 5 retry (`RETRY`), `maxAttempts=2` |
| Empty retrieval → useful refusal | T1 (`USE_GENERAL_KNOWLEDGE_REFUSAL` template) |
| Conflicting sources surfaced | T3 (ConflictDetector reuse, `<N>` confict template) |
| Unknown/malformed citation doesn't crash | V1/V6 + `ParseResult` + `UNRESPONSIVE` |

## 16. Out of scope

- Note-body scroll-to-highlight (deferred; block-index mapping wired, per Section 11.3).
- PDF highlight rendering inside a PDF viewer (page-number open only).
- Query-intent classifier for Trigger 4 (removed by user decision; response-level check used).
- Backfilling historical `answer_citations` rows with quote ranges beyond the v8 backfill.
- Web-search sources: excluded from the citation footer (existing web-search-only
  `appendCitations` behavior is superseded by the validated-citation footer but web results do
  not produce `Citation` objects).

## 17. Risks

- **Entailment floor tuning:** `0.58` cosine is the Phase 5 analogue; precise mult-lingual
  floor may need on-device calibration. Fail-closed design keeps it safe.
- **Model offset unreliability:** advisory offsets may be wrong; `computeQuoteSpan` overrides —
  displayed quotes are app-computed.
- **Retry model divergence:** the retried model may produce a different envelope; `maxAttempts`
  bounds it; `UNRESPONSIVE` terminates.
- **`MarkdownRenderer` superscript pass:** a rendering regression affects all chat markdown;
  cover with `ChatScreenCitationTest` and keep the parser pure/unit-tested.
- **Embedder dependency:** entailment depends on `OnDeviceEmbedder.isReady()`; fail-closed
  avoids false accepts but may over-reject on devices where the embedder is slow to warm.