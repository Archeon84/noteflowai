# Proactive Insights (Conflict Detection) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend RecallPredictor to detect contradicting notes on the same concept and surface them as a new `CONFLICT_DETECTED` suggestion type, visible in HomeScreen, chat, and daily notifications.

**Architecture:** A new `ConflictDetector` class analyzes concept evolution contexts from TemporalIndex — when a concept appears in notes with opposing sentiment/context signals (e.g., "love" vs "hate" in same concept's appearances), it flags a conflict. RecallPredictor gains a new `CONFLICT_DETECTED` RecallType. The existing HomeScreen card and notification receiver already handle new RecallType values generically.

**Tech Stack:** Kotlin, existing RecallPredictor + TemporalIndex + ConceptGraph data, Compose Material3 for UI rendering.

## Global Constraints

- Android minSdk 26, targetSdk 34
- Kotlin 1.9+, Compose BOM 2026.06
- No new dependencies
- Conflict detection must be offline and fast (runs in-memory on existing indexes)
- Privacy: conflict text never leaves device

## File Structure

| File | Responsibility |
|------|---------------|
| `data/search/ConflictDetector.kt` | Analyzes concept contexts for contradictions |
| `data/search/RecallPredictor.kt:~80-120` | Add CONFLICT_DETECTED to RecallType and detection loop |
| `ui/screens/HomeScreen.kt:~366` | Render conflict suggestions with distinct icon/color |
| `RecallReminderReceiver.kt:~50-55` | Include conflict suggestions in notification |

---

### Task 1: Create ConflictDetector

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/search/ConflictDetector.kt`

**Interfaces:**
- Consumes: `TemporalIndex` (for `getEvolution`), `ConceptGraphRepository` (for `getNotesForConcept`)
- Produces: `List<ConflictResult>` where `ConflictResult(concept, noteA, noteB, contextA, contextB, reason)`

- [ ] **Step 1: Create ConflictDetector.kt**

Create `app/src/main/java/com/noteflowai/app/data/search/ConflictDetector.kt`:

```kotlin
package com.noteflowai.app.data.search

import com.noteflowai.app.data.concept.ConceptGraphRepository
import com.noteflowai.app.data.temporal.TemporalIndex

/**
 * Detects contradicting notes about the same concept by analyzing
 * context windows from TemporalIndex appearances.
 */
class ConflictDetector(
    private val temporalIndex: TemporalIndex,
    private val conceptGraph: ConceptGraphRepository
) {

    data class ConflictResult(
        val concept: String,
        val noteA: String,
        val noteB: String,
        val contextA: String,
        val contextB: String,
        val reason: String
    )

    // Simple sentiment/negation lexicon for offline conflict detection
    private val positiveSignals = setOf(
        "love", "great", "excellent", "amazing", "perfect", "best", "enjoy",
        "happy", "wonderful", "fantastic", "brilliant", "recommend", "pro",
        "advantage", "benefit", "positive", "yes", "agree", "support"
    )

    private val negativeSignals = setOf(
        "hate", "terrible", "awful", "worst", "horrible", "bad", "poor",
        "disappointing", "frustrating", "annoying", "con", "disadvantage",
        "negative", "no", "disagree", "oppose", "avoid", "problem", "issue",
        "fail", "broken", "useless", "waste", "wrong"
    )

    private val negationWords = setOf("not", "no", "never", "don't", "doesn't", "isn't", "wasn't")

    /**
     * Find concepts with contradicting notes.
     * Only checks concepts with 2+ notes (need at least 2 to conflict).
     */
    fun findConflicts(maxResults: Int = 3): List<ConflictResult> {
        val allConcepts = conceptGraph.getAllConcepts()
        val results = mutableListOf<ConflictResult>()

        for (concept in allConcepts) {
            if (concept.noteCount < 2) continue
            if (results.size >= maxResults) break

            val evolution = temporalIndex.getEvolution(concept.canonicalForm) ?: continue
            val appearances = evolution.appearances
            if (appearances.size < 2) continue

            // Compare each pair of appearances for opposing signals
            for (i in appearances.indices) {
                for (j in i + 1 until appearances.size) {
                    val a = appearances[i]
                    val b = appearances[j]

                    // Skip if same note
                    if (a.noteFileName == b.noteFileName) continue

                    val scoreA = computeSentimentScore(a.context)
                    val scoreB = computeSentimentScore(b.context)

                    // Conflict: one positive, one negative, with enough signal
                    if (scoreA > 0.3f && scoreB < -0.3f) {
                        results.add(
                            ConflictResult(
                                concept = concept.displayForm,
                                noteA = a.noteFileName,
                                noteB = b.noteFileName,
                                contextA = a.context.take(100),
                                contextB = b.context.take(100),
                                reason = "Positive in ${a.noteFileName.removeSuffix(".md")}, negative in ${b.noteFileName.removeSuffix(".md")}"
                            )
                        )
                        break  // one conflict per concept is enough
                    } else if (scoreA < -0.3f && scoreB > 0.3f) {
                        results.add(
                            ConflictResult(
                                concept = concept.displayForm,
                                noteA = a.noteFileName,
                                noteB = b.noteFileName,
                                contextA = a.context.take(100),
                                contextB = b.context.take(100),
                                reason = "Negative in ${a.noteFileName.removeSuffix(".md")}, positive in ${b.noteFileName.removeSuffix(".md")}"
                            )
                        )
                        break
                    }
                    if (results.size >= maxResults) break
                }
                if (results.size >= maxResults) break
            }
        }

        return results
    }

    /**
     * Simple lexicon-based sentiment score for a text context.
     * Returns a value in [-1.0, 1.0].
     */
    private fun computeSentimentScore(text: String): Float {
        val words = text.lowercase()
            .replace(Regex("[^a-z\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }

        var score = 0f
        var negated = false

        for (word in words) {
            if (word in negationWords) {
                negated = true
                continue
            }
            if (word in positiveSignals) {
                score += if (negated) -0.3f else 0.3f
                negated = false
            } else if (word in negativeSignals) {
                score += if (negated) 0.2f else -0.3f
                negated = false
            } else {
                negated = false
            }
        }

        return score.coerceIn(-1f, 1f)
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/search/ConflictDetector.kt
git commit -m "feat: add ConflictDetector for contradicting note detection"
```

---

### Task 2: Extend RecallPredictor with CONFLICT_DETECTED

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/data/search/RecallPredictor.kt:~30-40` — add enum value
- Modify: `app/src/main/java/com/noteflowai/app/data/search/RecallPredictor.kt:~60-70` — add ConflictDetector field
- Modify: `app/src/main/java/com/noteflowai/app/data/search/RecallPredictor.kt:~80-120` — add conflict detection call

**Interfaces:**
- Consumes: `ConflictDetector` (from Task 1)
- Produces: Extended `RecallSuggestion` list that may include CONFLICT_DETECTED entries

- [ ] **Step 1: Add CONFLICT_DETECTED to RecallType enum**

In `RecallPredictor.kt`, find the `RecallType` enum (~line 30):

```kotlin
enum class RecallType { STALE_CONCEPT, FOLLOW_UP, UNRESOLVED_IDEA, CONFLICT_DETECTED }
```

- [ ] **Step 2: Add ConflictDetector field**

In `RecallPredictor` class constructor, after the existing fields:

```kotlin
class RecallPredictor(
    private val temporalIndex: TemporalIndex,
    private val conceptGraph: ConceptGraphRepository
) {
    private val conflictDetector = ConflictDetector(temporalIndex, conceptGraph)
```

Add import: `import com.noteflowai.app.data.search.ConflictDetector`

- [ ] **Step 3: Add conflict detection to findRecallSuggestions**

At the end of `findRecallSuggestions()`, before the final `return`, add:

```kotlin
    // ... existing code that builds suggestions list ...

    // Add conflict detections
    val conflicts = conflictDetector.findConflicts(maxResults = 2)
    for (conflict in conflicts) {
        suggestions.add(
            RecallSuggestion(
                concept = conflict.concept,
                type = RecallType.CONFLICT_DETECTED,
                detail = "Conflicting views: ${conflict.reason}",
                relatedNotes = listOf(conflict.noteA, conflict.noteB),
                stalenessDays = 0L
            )
        )
    }

    return suggestions.sortedByDescending { it.stalenessDays }.take(MAX_SUGGESTIONS)
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/search/RecallPredictor.kt
git commit -m "feat: extend RecallPredictor with CONFLICT_DETECTED type"
```

---

### Task 3: Update HomeScreen to render conflict suggestions

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/HomeScreen.kt:~366` — add conflict icon in RecallSuggestionsCard

**Interfaces:**
- Consumes: `RecallSuggestion` with `CONFLICT_DETECTED` type (from Task 2)
- Produces: Visual distinction for conflicts in the RecallSuggestionsCard

- [ ] **Step 1: Add conflict icon mapping**

In `HomeScreen.kt`, find the RecallSuggestionsCard composable where it renders each suggestion with an icon. The existing code maps types to icons:

```kotlin
val typeIcon = when (suggestion.type) {
    RecallPredictor.RecallType.STALE_CONCEPT -> Icons.Default.Schedule
    RecallPredictor.RecallType.FOLLOW_UP -> Icons.Default.ArrowForward
    RecallPredictor.RecallType.UNRESOLVED_IDEA -> Icons.Default.HelpOutline
    RecallPredictor.RecallType.CONFLICT_DETECTED -> Icons.Default.Warning
}
```

- [ ] **Step 2: Add conflict color**

Where the icon color is determined, add:

```kotlin
val typeColor = when (suggestion.type) {
    RecallPredictor.RecallType.STALE_CONCEPT -> MaterialTheme.colorScheme.primary
    RecallPredictor.RecallType.FOLLOW_UP -> MaterialTheme.colorScheme.tertiary
    RecallPredictor.RecallType.UNRESOLVED_IDEA -> MaterialTheme.colorScheme.secondary
    RecallPredictor.RecallType.CONFLICT_DETECTED -> Color(0xFFFF6B35)  // orange-red
}
```

Add import: `import androidx.compose.ui.graphics.Color`

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/ui/screens/HomeScreen.kt
git commit -m "feat: render conflict suggestions with warning icon in HomeScreen"
```

---

### Task 4: Update notification to include conflicts

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/RecallReminderReceiver.kt:~50-55` — handle CONFLICT_DETECTED in notification text

**Interfaces:**
- Consumes: `RecallSuggestion` with `CONFLICT_DETECTED` type
- Produces: Notification title differentiated for conflicts

- [ ] **Step 1: Update notification title for conflicts**

In `RecallReminderReceiver.kt`, after line 51 where `val title` is set, update:

```kotlin
val topSuggestions = suggestions.take(3)
val title = if (topSuggestions.any { it.type == RecallPredictor.RecallType.CONFLICT_DETECTED }) {
    "Conflicting notes found"
} else {
    "Revisit: ${topSuggestions.first().concept}"
}
```

Add import if needed: `import com.noteflowai.app.data.search.RecallPredictor`

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/RecallReminderReceiver.kt
git commit -m "feat: differentiate notification title for conflict suggestions"
```

---

### Task 5: End-to-end test

- [ ] **Step 1: Build and install**

Run: `./gradlew installDebug`
Expected: App installs

- [ ] **Step 2: Manual verification**

1. Create two notes about the same concept with opposing sentiments (e.g., "I love Product X" and "Product X is terrible")
2. Wait for graph rebuild or trigger via search
3. Open HomeScreen → Revisit card → verify conflict suggestion appears with warning icon
4. Open chat → search for the concept → verify suggestions include conflict

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat: proactive insights conflict detection complete"
```
