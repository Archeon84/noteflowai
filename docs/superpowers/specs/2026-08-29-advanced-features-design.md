# Design Specification: Phase 10 Advanced Features

**Date:** 2026-08-29
**Status:** Approved
**Target Phase:** Phase 10 of NoteFlowAI Agentic Guide (lines 1316-1343)

---

## 1. Overview and Objectives

Phase 10 consolidates and hardens advanced intelligence capabilities in NoteFlowAI under strict privacy and non-intrusive UX rules:
1. **Proactive Recall & Contextual Reminders:** Suggests relevant past notes when editing or capturing without interrupting active input.
2. **Web Enrichment:** Optional grounded web search enrichment for chat queries, respecting Local-Only mode isolation.
3. **Multi-Hop Reasoning:** Multi-stage graph and conceptual traversals linking disparate notes across entities and concepts.
4. **Concept Memory Graph:** Force-directed visualization and clustering of shared topics and entity relationships.
5. **Smart Background Suggestions:** Contextual action chips with transparent reasoning ("Why this appeared") and explicit user dismissal/mute controls.
6. **Idea Evolution Analysis:** Historical timeline tracking changes in user perspective across note iterations.

---

## 2. Architectural Invariants and Rules

Per Guide section 7 (Phase 10 Rules):
- **Feature Flags:** Every advanced feature is toggled via `SettingsManager` DataStore keys.
- **Explainability:** All suggestions and proactively recalled notes carry an explanation (`whySuggested`, `sharedConcepts`, `confidence`).
- **Dismissal & Mute:** Users can dismiss individual suggestions or mute suggestion categories.
- **Non-Intrusive:** No active typing, audio recording, or dialog flow may be blocked or interrupted by background suggestion computation.
- **Privacy Enforcement:** All web enrichment and cloud enhancements are strictly disabled when `isLocalOnlyMode` is active.

---

## 3. Component Design

### 3.1 Advanced Feature Flags (`SettingsManager`)
- `PROACTIVE_RECALL_ENABLED`: Toggles proactive note suggestions.
- `WEB_ENRICHMENT_ENABLED`: Toggles web search enrichment.
- `MULTI_HOP_ENABLED`: Toggles multi-hop retrieval and reasoning.
- `CONCEPT_GRAPH_ENABLED`: Toggles concept memory graph visualization.
- `IDEA_EVOLUTION_ENABLED`: Toggles change and perspective evolution analysis.

### 3.2 Suggestion Explanations & Dismissal Store
- `SuggestionExplanation`:
  ```kotlin
  data class SuggestionExplanation(
      val noteId: String,
      val reason: String,
      val sharedConcepts: List<String>,
      val score: Float,
      val dismissed: Boolean = false
  )
  ```
- Memory in-memory dismissal set preventing dismissed notes from reappearing in the current editing session.

---

## 4. Test Matrix & Verification

1. `AdvancedFeaturesFlagsTest`: Verifies feature flag defaults, toggles, and Local-Only mode override behavior.
2. `MultiHopReasonerTest`: Verifies multi-hop concept traversal connects notes across indirect references.
3. `SuggestionDismissalTest`: Verifies that dismissed suggestions are excluded from subsequent recall suggestions.
4. Full Test Suite Regression: Executes complete project unit tests with 100% green exit code.
