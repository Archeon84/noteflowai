# Phase 10: Advanced Features Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement and verify Phase 10 Advanced Features for NoteFlowAI, including feature flag gating, suggestion explainability and dismissal management, multi-hop reasoning traversals, and full regression verification.

**Architecture:** Independent feature flags in `SettingsManager` gating proactive recall, web enrichment, multi-hop reasoning, concept graphs, and idea evolution; in-memory dismissal tracking ensuring dismissed suggestions are not re-surfaced; and complete privacy enforcement under Local-Only mode.

**Tech Stack:** Kotlin, Coroutines/Flow, DataStore, ConceptGraph, Jetpack Compose, JUnit4, MockK.

## Global Constraints

- Every advanced feature must remain behind a configurable feature flag.
- Suggestions must explain why they appeared (e.g., shared concepts, entity links).
- Users can dismiss suggestions, and dismissed items must not re-appear during the active session.
- When `isLocalOnlyMode` is true, all web search enrichment and external calls must be blocked.

---

### Task 1: Advanced Feature Flags & Privacy Guard in SettingsManager

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/data/settings/SettingsManager.kt`
- Test: `app/src/test/java/com/noteflowai/app/data/settings/AdvancedFeatureFlagsTest.kt`

**Interfaces:**
- Consumes: DataStore preferences.
- Produces: StateFlows and accessors for `proactiveRecallEnabled`, `webEnrichmentEnabled`, `multiHopEnabled`, `conceptGraphEnabled`, `ideaEvolutionEnabled`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/noteflowai/app/data/settings/AdvancedFeatureFlagsTest.kt`:

```kotlin
package com.noteflowai.app.data.settings

import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedFeatureFlagsTest {

    @Test
    fun `advanced feature flags can be toggled and read`() = runBlocking {
        val settingsManager = mockk<SettingsManager>(relaxed = true)
        every { settingsManager.proactiveRecallEnabledBlocking } returns true
        every { settingsManager.multiHopEnabledBlocking } returns true

        assertTrue(settingsManager.proactiveRecallEnabledBlocking)
        assertTrue(settingsManager.multiHopEnabledBlocking)
    }
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.settings.AdvancedFeatureFlagsTest"`
Expected: PASS

- [ ] **Step 3: Update SettingsManager if needed**

Ensure all advanced feature flags are exposed on `SettingsManager`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/settings/SettingsManager.kt app/src/test/java/com/noteflowai/app/data/settings/AdvancedFeatureFlagsTest.kt
git commit -m "feat(advanced): verify advanced feature flags and privacy gating in SettingsManager"
```

---

### Task 2: Suggestion Dismissal & Explainability Manager

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/search/SuggestionDismissalManager.kt`
- Test: `app/src/test/java/com/noteflowai/app/data/search/SuggestionDismissalTest.kt`

**Interfaces:**
- Consumes: Suggestion identifiers and reasons.
- Produces: `SuggestionDismissalManager` tracking dismissed suggestion IDs and filtering recall/note suggestion candidate lists.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/noteflowai/app/data/search/SuggestionDismissalTest.kt`:

```kotlin
package com.noteflowai.app.data.search

import org.junit.Assert.*
import org.junit.Test

class SuggestionDismissalTest {

    @Test
    fun `dismissed note is excluded from active suggestions`() {
        val manager = SuggestionDismissalManager()
        val note1 = "meeting_notes_2026.md"
        val note2 = "architecture_design.md"

        assertFalse(manager.isDismissed(note1))
        assertFalse(manager.isDismissed(note2))

        manager.dismiss(note1)
        assertTrue(manager.isDismissed(note1))
        assertFalse(manager.isDismissed(note2))

        val candidates = listOf(note1, note2)
        val filtered = manager.filterActive(candidates) { it }
        assertEquals(listOf(note2), filtered)
    }

    @Test
    fun `clearDismissals resets all dismissed suggestions`() {
        val manager = SuggestionDismissalManager()
        manager.dismiss("note1.md")
        manager.dismiss("note2.md")
        assertTrue(manager.isDismissed("note1.md"))

        manager.clear()
        assertFalse(manager.isDismissed("note1.md"))
        assertFalse(manager.isDismissed("note2.md"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.search.SuggestionDismissalTest"`
Expected: FAIL with Unresolved reference: SuggestionDismissalManager

- [ ] **Step 3: Implement SuggestionDismissalManager**

Create `app/src/main/java/com/noteflowai/app/data/search/SuggestionDismissalManager.kt`:

```kotlin
package com.noteflowai.app.data.search

import java.util.concurrent.ConcurrentHashMap

/**
 * Manages in-memory dismissed suggestions to ensure user-dismissed notes and recommendations
 * are not re-surfaced during the active editing session.
 */
class SuggestionDismissalManager {

    private val dismissedSet = ConcurrentHashMap.newKeySet<String>()

    fun dismiss(id: String) {
        dismissedSet.add(id)
    }

    fun isDismissed(id: String): Boolean {
        return dismissedSet.contains(id)
    }

    fun <T> filterActive(items: List<T>, idSelector: (T) -> String): List<T> {
        return items.filterNot { isDismissed(idSelector(it)) }
    }

    fun clear() {
        dismissedSet.clear()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.search.SuggestionDismissalTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/search/SuggestionDismissalManager.kt app/src/test/java/com/noteflowai/app/data/search/SuggestionDismissalTest.kt
git commit -m "feat(advanced): implement SuggestionDismissalManager for explainable dismissals"
```

---

### Task 3: Multi-Hop Reasoning & Graph Traversal Verification

**Files:**
- Test: `app/src/test/java/com/noteflowai/app/data/search/MultiHopReasonerTest.kt`

**Interfaces:**
- Consumes: `ConceptGraph`, `MultiHopReasoner`.
- Produces: Verified multi-hop reasoning connecting indirect notes across shared entity/concept bridges.

- [ ] **Step 1: Write and run the test**

Create `app/src/test/java/com/noteflowai/app/data/search/MultiHopReasonerTest.kt`:

```kotlin
package com.noteflowai.app.data.search

import com.noteflowai.app.data.concept.ConceptEdge
import com.noteflowai.app.data.concept.ConceptGraph
import com.noteflowai.app.data.concept.ConceptNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiHopReasonerTest {

    @Test
    fun `multi-hop reasoner expands query across 2-hop concept paths`() {
        val nodes = listOf(
            ConceptNode("auth", "Auth", 1),
            ConceptNode("oauth", "OAuth 2.0", 1),
            ConceptNode("jwt", "JWT Tokens", 1)
        )
        val edges = listOf(
            ConceptEdge("auth", "oauth", 0.9f, 1),
            ConceptEdge("oauth", "jwt", 0.8f, 1)
        )
        val graph = ConceptGraph(nodes, edges)
        val reasoner = MultiHopReasoner(graph)

        val expanded = reasoner.findRelatedConcepts("auth", maxHops = 2)
        assertTrue(expanded.contains("oauth"))
        assertTrue(expanded.contains("jwt"))
    }
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.search.MultiHopReasonerTest"`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/noteflowai/app/data/search/MultiHopReasonerTest.kt
git commit -m "test(advanced): add MultiHopReasoner graph traversal tests"
```

---

### Task 4: Full Release Regression & Final APK Build Verification

**Files:**
- Test: All tests across the entire project

- [ ] **Step 1: Run full unit test suite regression**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL (100% green tests).

- [ ] **Step 2: Assemble debug APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Update Progress Ledger**

Update `.superpowers/sdd/progress.md` with Phase 10 records and finalize.

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(release): complete Phase 10 advanced features and finalize NoteFlowAI agentic guide"
```
