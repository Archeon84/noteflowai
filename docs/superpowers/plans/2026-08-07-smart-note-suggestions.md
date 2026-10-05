# Smart Note Suggestions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enhance the note editor with real-time smart suggestions: related notes to link, merge candidates when similar notes exist, and title suggestions based on content.

**Architecture:** A new `NoteSuggestionEngine` analyzes the current note being edited against existing notes using concept overlap, content similarity, and temporal proximity. It produces typed suggestions (LINK, MERGE, TITLE) that the editor surfaces in a suggestion bar above the keyboard. The existing `AutoLinker` and `NoteGraphRepository` provide the data foundation; this adds a live analysis layer that runs on note content changes with debouncing.

**Tech Stack:** Kotlin, existing AutoLinker/NoteGraphRepository/ConceptGraph data, Compose, Kotlin coroutines debounce.

## Global Constraints

- Android minSdk 26, targetSdk 34
- Kotlin 1.9+, Compose BOM 2026.06
- No new dependencies
- Suggestions must be fast (<100ms) — analysis runs in-memory on existing indexes
- No network calls — fully offline

## File Structure

| File | Responsibility |
|------|---------------|
| `data/suggestions/NoteSuggestionEngine.kt` | Analyzes note content, produces suggestion list |
| `data/suggestions/NoteSuggestion.kt` | Data classes for typed suggestions |
| `viewmodel/MainViewModel.kt` | Expose suggestions StateFlow, trigger analysis |
| `ui/screens/NoteDetailContent.kt` | Render suggestion bar in editor |

---

### Task 1: Create suggestion data classes

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/suggestions/NoteSuggestion.kt`

**Interfaces:**
- Consumes: Nothing (pure data)
- Produces: `NoteSuggestion` sealed class consumed by engine and UI

- [ ] **Step 1: Create NoteSuggestion.kt**

Create `app/src/main/java/com/noteflowai/app/data/suggestions/NoteSuggestion.kt`:

```kotlin
package com.noteflowai.app.data.suggestions

/**
 * Typed suggestion for the note editor.
 */
sealed class NoteSuggestion {

    /** Suggest linking to an existing related note. */
    data class LinkSuggestion(
        val targetFileName: String,
        val targetTitle: String,
        val reason: String,
        val strength: Float
    ) : NoteSuggestion()

    /** Suggest merging with a similar note. */
    data class MergeSuggestion(
        val targetFileName: String,
        val targetTitle: String,
        val reason: String,
        val similarity: Float
    ) : NoteSuggestion()

    /** Suggest a title for the current note based on its content. */
    data class TitleSuggestion(
        val suggestedTitle: String,
        val confidence: Float
    ) : NoteSuggestion()

    /** Suggest adding a tag/category based on content. */
    data class TagSuggestion(
        val tag: String,
        val confidence: Float
    ) : NoteSuggestion()
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/suggestions/NoteSuggestion.kt
git commit -m "feat: add NoteSuggestion data classes"
```

---

### Task 2: Create NoteSuggestionEngine

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/suggestions/NoteSuggestionEngine.kt`

**Interfaces:**
- Consumes: `ConceptGraphRepository`, `NoteGraphRepository`, `NoteSearchIndex`, `List<NoteFile>` (all existing)
- Produces: `fun analyze(noteFileName: String, content: String, tags: List<String>): List<NoteSuggestion>`

- [ ] **Step 1: Create NoteSuggestionEngine.kt**

Create `app/src/main/java/com/noteflowai/app/data/suggestions/NoteSuggestionEngine.kt`:

```kotlin
package com.noteflowai.app.data.suggestions

import com.noteflowai.app.data.NoteFile
import com.noteflowai.app.data.concept.ConceptExtractor
import com.noteflowai.app.data.concept.ConceptGraphRepository
import com.noteflowai.app.data.graph.NoteGraphRepository
import com.noteflowai.app.data.search.NoteSearchIndex

/**
 * Analyzes a note being edited and produces smart suggestions:
 * related notes to link, merge candidates, title ideas, and tag suggestions.
 */
class NoteSuggestionEngine(
    private val conceptGraph: ConceptGraphRepository,
    private val noteGraph: NoteGraphRepository,
    private val searchIndex: NoteSearchIndex,
    private val conceptExtractor: ConceptExtractor
) {

    companion object {
        private const val MAX_LINK_SUGGESTIONS = 3
        private const val MAX_MERGE_SUGGESTIONS = 2
        private const val MERGE_SIMILARITY_THRESHOLD = 0.6f
        private const val MIN_CONTENT_LENGTH = 20
    }

    /**
     * Analyze the given note content and return suggestions.
     * Fast enough to run on content changes with debouncing.
     */
    fun analyze(
        noteFileName: String,
        content: String,
        tags: List<String>,
        allNotes: List<NoteFile> = emptyList()
    ): List<NoteSuggestion> {
        if (content.length < MIN_CONTENT_LENGTH) return emptyList()

        val suggestions = mutableListOf<NoteSuggestion>()

        // 1. Title suggestions
        suggestions.addAll(suggestTitle(content))

        // 2. Tag suggestions (only if current tags are sparse)
        if (tags.size < 3) {
            suggestions.addAll(suggestTags(content, tags))
        }

        // 3. Related note links via concept overlap
        suggestions.addAll(suggestLinks(noteFileName, content))

        // 4. Merge candidates via content similarity
        if (allNotes.isNotEmpty()) {
            suggestions.addAll(suggestMerges(noteFileName, content, allNotes))
        }

        return suggestions
    }

    /**
     * Suggest a title based on the first meaningful line of content.
     */
    private fun suggestTitle(content: String): List<NoteSuggestion.TitleSuggestion> {
        // Take first non-empty line that isn't a markdown heading
        val firstLine = content.lines()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") && it.length in 5..80 }
            ?: return emptyList()

        // Clean up: remove markdown formatting
        val cleanTitle = firstLine
            .replace(Regex("[*_`~]"), "")
            .replace(Regex("^[-*>\\s]+"), "")
            .take(60)

        if (cleanTitle.length < 5) return emptyList()

        return listOf(
            NoteSuggestion.TitleSuggestion(
                suggestedTitle = cleanTitle,
                confidence = 0.7f
            )
        )
    }

    /**
     * Suggest tags based on extracted concepts not already in the tag list.
     */
    private fun suggestTags(content: String, existingTags: List<String>): List<NoteSuggestion.TagSuggestion> {
        val existingLower = existingTags.map { it.lowercase() }.toSet()
        val concepts = conceptExtractor.extractConcepts(content)

        return concepts
            .filter { it.displayForm.lowercase() !in existingLower }
            .take(3)
            .map { concept ->
                NoteSuggestion.TagSuggestion(
                    tag = concept.displayForm,
                    confidence = concept.importance
                )
            }
    }

    /**
     * Suggest linking to related notes via concept graph connections.
     */
    private fun suggestLinks(
        noteFileName: String,
        content: String
    ): List<NoteSuggestion.LinkSuggestion> {
        // Extract concepts from the current note
        val currentConcepts = conceptExtractor.extractConcepts(content)
            .map { it.canonicalForm }

        if (currentConcepts.isEmpty()) return emptyList()

        // Find notes connected via concept graph
        val relatedNotes = mutableMapOf<String, Float>()  // fileName -> max strength

        for (concept in currentConcepts.take(10)) {
            val conceptNotes = conceptGraph.getNotesForConcept(concept)
            for (relatedNote in conceptNotes) {
                if (relatedNote == noteFileName) continue
                val currentStrength = relatedNotes[relatedNote] ?: 0f
                relatedNotes[relatedNote] = maxOf(currentStrength, 0.5f)
            }
        }

        // Also check note graph connections
        val noteConnections = noteGraph.getConnectedNotes(noteFileName)
        for ((connectedNote, strength) in noteConnections) {
            val currentStrength = relatedNotes[connectedNote] ?: 0f
            relatedNotes[connectedNote] = maxOf(currentStrength, strength)
        }

        return relatedNotes.entries
            .sortedByDescending { it.value }
            .take(MAX_LINK_SUGGESTIONS)
            .map { (fileName, strength) ->
                NoteSuggestion.LinkSuggestion(
                    targetFileName = fileName,
                    targetTitle = fileName.removeSuffix(".md"),
                    reason = "Shares concepts with current note",
                    strength = strength
                )
            }
    }

    /**
     * Suggest merging when content is highly similar to an existing note.
     */
    private fun suggestMerges(
        noteFileName: String,
        content: String,
        allNotes: List<NoteFile>
    ): List<NoteSuggestion.MergeSuggestion> {
        val results = mutableListOf<NoteSuggestion.MergeSuggestion>()

        for (note in allNotes) {
            if (note.fileName == noteFileName) continue
            if (note.bodyContent.isEmpty()) continue

            // Quick similarity check: shared unique words ratio
            val currentWords = extractKeyWords(content)
            val noteWords = extractKeyWords(note.bodyContent)

            if (currentWords.isEmpty() || noteWords.isEmpty()) continue

            val intersection = currentWords.intersect(noteWords)
            val union = currentWords.union(noteWords)
            val jaccard = intersection.size.toFloat() / union.size

            if (jaccard >= MERGE_SIMILARITY_THRESHOLD) {
                results.add(
                    NoteSuggestion.MergeSuggestion(
                        targetFileName = note.fileName,
                        targetTitle = note.title,
                        reason = "${(jaccard * 100).toInt()}% word overlap",
                        similarity = jaccard
                    )
                )
            }
        }

        return results.sortedByDescending { it.similarity }.take(MAX_MERGE_SUGGESTIONS)
    }

    /**
     * Extract meaningful words (remove stopwords, short words).
     */
    private fun extractKeyWords(text: String): Set<String> {
        val stopwords = setOf(
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "could",
            "should", "may", "might", "shall", "can", "to", "of", "in", "for",
            "on", "with", "at", "by", "from", "as", "into", "through", "during",
            "before", "after", "above", "below", "between", "and", "but", "or",
            "not", "no", "nor", "so", "yet", "both", "either", "neither", "each",
            "every", "all", "any", "few", "more", "most", "other", "some", "such",
            "than", "too", "very", "just", "also", "now", "then", "here", "there",
            "when", "where", "how", "what", "which", "who", "whom", "this", "that",
            "these", "those", "it", "its", "i", "me", "my", "we", "our", "you",
            "your", "he", "him", "his", "she", "her", "they", "them", "their"
        )

        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 4 && it !in stopwords }
            .toSet()
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/suggestions/NoteSuggestionEngine.kt
git commit -m "feat: add NoteSuggestionEngine for live note analysis"
```

---

### Task 3: Wire engine into MainViewModel

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt`

**Interfaces:**
- Consumes: `NoteSuggestionEngine` (from Task 2)
- Produces: `val noteSuggestions: StateFlow<List<NoteSuggestion>>`, `fun analyzeCurrentNote(content, tags)`

- [ ] **Step 1: Add suggestion engine and StateFlow**

After the dailyDigest fields (from the Daily Digest plan):

```kotlin
private val noteSuggestionEngine = NoteSuggestionEngine(
    conceptGraphRepository, noteGraphRepository, noteSearchIndex, conceptExtractor
)
private val _noteSuggestions = MutableStateFlow<List<NoteSuggestion>>(emptyList())
val noteSuggestions: StateFlow<List<NoteSuggestion>> = _noteSuggestions.asStateFlow()
```

Add imports:
```kotlin
import com.noteflowai.app.data.suggestions.NoteSuggestion
import com.noteflowai.app.data.suggestions.NoteSuggestionEngine
```

- [ ] **Step 2: Add analyze method with debounce support**

```kotlin
private var suggestionJob: Job? = null

fun analyzeCurrentNote(noteFileName: String, content: String, tags: List<String>) {
    suggestionJob?.cancel()
    suggestionJob = viewModelScope.launch {
        kotlinx.coroutines.delay(500)  // debounce 500ms
        val suggestions = noteSuggestionEngine.analyze(
            noteFileName = noteFileName,
            content = content,
            tags = tags,
            allNotes = savedNotes.value
        )
        _noteSuggestions.value = suggestions
    }
}

fun clearSuggestions() {
    _noteSuggestions.value = emptyList()
}
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt
git commit -m "feat: wire NoteSuggestionEngine into MainViewModel with debounced analysis"
```

---

### Task 4: Add suggestion bar to NoteDetailContent

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/NoteDetailContent.kt`

**Interfaces:**
- Consumes: `viewModel.noteSuggestions`, `viewModel.analyzeCurrentNote()`, `viewModel.selectNoteByFileName()`
- Produces: Suggestion bar rendered above keyboard in note editor

- [ ] **Step 1: Collect suggestions state**

In `NoteDetailContent.kt`, find where ViewModel state is collected (near other `collectAsStateWithLifecycle` calls). Add:

```kotlin
val suggestions by viewModel.noteSuggestions.collectAsStateWithLifecycle()
```

- [ ] **Step 2: Trigger analysis on content changes**

Where the note content `TextField` or editor handles text changes, add a side-effect:

```kotlin
// After the content state is updated:
LaunchedEffect(currentContent) {
    viewModel.analyzeCurrentNote(
        noteFileName = note.fileName,
        content = currentContent,
        tags = currentTags
    )
}
```

- [ ] **Step 3: Add suggestion bar composable**

After the editor TextField and before the Connected Notes section, add:

```kotlin
// Smart Suggestions Bar
if (suggestions.isNotEmpty()) {
    SmartSuggestionBar(
        suggestions = suggestions,
        onLinkClick = { suggestion ->
            viewModel.selectNoteByFileName(suggestion.targetFileName)
        },
        onMergeClick = { suggestion ->
            // Navigate to target note for manual merge
            viewModel.selectNoteByFileName(suggestion.targetFileName)
        },
        onTitleClick = { suggestion ->
            viewModel.updateNoteTitle(suggestion.suggestedTitle)
        },
        onTagClick = { suggestion ->
            viewModel.addTagToCurrentNote(suggestion.tag)
        },
        onDismiss = { viewModel.clearSuggestions() }
    )
}
```

- [ ] **Step 4: Create SmartSuggestionBar composable**

In the same file or a new file, add:

```kotlin
@Composable
fun SmartSuggestionBar(
    suggestions: List<NoteSuggestion>,
    onLinkClick: (NoteSuggestion.LinkSuggestion) -> Unit,
    onMergeClick: (NoteSuggestion.MergeSuggestion) -> Unit,
    onTitleClick: (NoteSuggestion.TitleSuggestion) -> Unit,
    onTagClick: (NoteSuggestion.TagSuggestion) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Suggestions",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                TextButton(onClick = onDismiss, contentPadding = PaddingValues(0.dp)) {
                    Text("Dismiss", style = MaterialTheme.typography.labelSmall)
                }
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Title suggestions
                suggestions.filterIsInstance<NoteSuggestion.TitleSuggestion>().forEach { suggestion ->
                    item {
                        SuggestionChip(
                            onClick = { onTitleClick(suggestion) },
                            label = {
                                Column {
                                    Text("Title", style = MaterialTheme.typography.labelSmall)
                                    Text(
                                        suggestion.suggestedTitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1
                                    )
                                }
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Title, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        )
                    }
                }

                // Tag suggestions
                suggestions.filterIsInstance<NoteSuggestion.TagSuggestion>().forEach { suggestion ->
                    item {
                        SuggestionChip(
                            onClick = { onTagClick(suggestion) },
                            label = {
                                Text("+${suggestion.tag}", style = MaterialTheme.typography.bodySmall)
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Label, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        )
                    }
                }

                // Link suggestions
                suggestions.filterIsInstance<NoteSuggestion.LinkSuggestion>().forEach { suggestion ->
                    item {
                        SuggestionChip(
                            onClick = { onLinkClick(suggestion) },
                            label = {
                                Column {
                                    Text("Link", style = MaterialTheme.typography.labelSmall)
                                    Text(
                                        suggestion.targetTitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1
                                    )
                                }
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        )
                    }
                }

                // Merge suggestions
                suggestions.filterIsInstance<NoteSuggestion.MergeSuggestion>().forEach { suggestion ->
                    item {
                        SuggestionChip(
                            onClick = { onMergeClick(suggestion) },
                            label = {
                                Column {
                                    Text("Merge?", style = MaterialTheme.typography.labelSmall)
                                    Text(
                                        suggestion.targetTitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1
                                    )
                                }
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Merge, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer
                            )
                        )
                    }
                }
            }
        }
    }
}
```

Add imports:
```kotlin
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Merge
import com.noteflowai.app.data.suggestions.NoteSuggestion
```

- [ ] **Step 5: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/ui/screens/NoteDetailContent.kt
git commit -m "feat: add SmartSuggestionBar to note editor"
```

---

### Task 5: Add helper methods to MainViewModel

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt`

**Interfaces:**
- Produces: `fun updateNoteTitle(title)`, `fun addTagToCurrentNote(tag)`

- [ ] **Step 1: Add updateNoteTitle method**

```kotlin
fun updateNoteTitle(title: String) {
    val note = currentNote.value ?: return
    val updated = note.copy(title = title, lastModifiedEpoch = System.currentTimeMillis())
    currentNote.value = updated
    saveNote(updated)
}
```

- [ ] **Step 2: Add addTagToCurrentNote method**

```kotlin
fun addTagToCurrentNote(tag: String) {
    val note = currentNote.value ?: return
    if (tag in note.tags) return
    val updated = note.copy(
        tags = note.tags + tag,
        lastModifiedEpoch = System.currentTimeMillis()
    )
    currentNote.value = updated
    saveNote(updated)
}
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt
git commit -m "feat: add updateNoteTitle and addTagToCurrentNote methods"
```

---

### Task 6: End-to-end test

- [ ] **Step 1: Build and install**

Run: `./gradlew installDebug`

- [ ] **Step 2: Manual verification**

1. Create a new note with content mentioning concepts from existing notes
2. Wait ~500ms after typing → verify suggestion bar appears
3. Verify title suggestion shows based on first line
4. Verify tag suggestions appear for extracted concepts not yet tagged
5. Verify link suggestions appear for notes sharing concepts
6. Create two notes with >60% word overlap → verify merge suggestion appears
7. Tap a link suggestion → verify navigation to target note
8. Tap a title suggestion → verify title updates
9. Tap a tag suggestion → verify tag is added
10. Tap "Dismiss" → verify suggestion bar hides

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat: smart note suggestions complete"
```
