# NoteFlowAI RAG Second Brain v2 -- Unique Differentiators

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transform NoteFlowAI's already-functional RAG system into a true second brain with unique capabilities no other note-taking app offers: concept extraction, idea evolution tracking, multi-hop reasoning, proactive recall, and search transparency.

**Architecture:** Build on top of the existing BM25 + embedding + graph system. New layers: a ConceptExtractor that identifies ideas/entities across notes, a TemporalIndex that tracks when topics appear, a SearchExplainer that shows why results were retrieved, a QueryDecomposer that breaks complex questions into sub-queries, and a ProactiveRecall system that surfaces relevant content before the user searches. All new components follow existing patterns (file-based JSON persistence, coroutines, no DI).

**Tech Stack:** Kotlin, ONNX Runtime (existing), OkHttp (existing), Gson (existing), Compose (existing). No new Gradle dependencies required.

## Global Constraints

- minSdk 26, targetSdk 35, compileSdk 35
- No Hilt/Dagger -- singletons via `companion object` + `getInstance(context)`
- File-based JSON persistence (no Room/SQLite), same as existing search/graph
- ONNX Runtime 1.23.2 already available for any on-device ML
- No new Gradle dependencies -- everything builds on existing stack
- Must not break existing RAG functionality (BM25, embeddings, graph, attribution)

---

## What Exists Today (Don't Rebuild)

| Component | File | Status |
|-----------|------|--------|
| BM25 search with field boosting | `data/search/NoteSearchIndex.kt` | Done |
| Embedding index (remote + on-device) | `data/search/EmbeddingIndex.kt` | Done |
| Remote embedding client | `data/search/RemoteEmbeddingClient.kt` | Done |
| On-device embedder (MiniLM-L6) | `data/search/OnDeviceEmbedder.kt` | Done |
| Conversation-aware query builder | `data/search/ConversationQueryBuilder.kt` | Done |
| Knowledge graph (auto-linking) | `data/graph/AutoLinker.kt`, `NoteGraphRepository.kt` | Done |
| Hybrid search fusion (0.3/0.7) | `viewmodel/MainViewModel.kt` hybridSearch() | Done |
| Graph-enhanced retrieval | `viewmodel/MainViewModel.kt` buildFinalSystemPrompt() | Done |
| Per-message attribution (RagSource) | `data/chat/AiChatModels.kt` | Done |
| Citation toggle | `data/settings/SettingsManager.kt` | Done |
| Index persistence (BM25 + embeddings) | `data/search/NoteIndexPersistence.kt` | Done |
| Source chips in ChatScreen | `ui/screens/chat/ChatScreen.kt` | Done |

---

## What Makes This Unique (The Plan)

### Phase 1: Concept Memory Graph

Most RAG systems index documents. This extracts *concepts* -- the ideas, entities, and themes that span multiple notes -- and builds a concept-centric knowledge graph.

**Why it's unique:** When a user asks "What do I know about product launches?", a document-centric RAG finds notes containing "product launch". A concept graph finds notes about "go-to-market strategy", "launch checklist", "beta testing timeline" -- even if none contain the exact phrase "product launch" -- because they all share the extracted concept `[product_launch]`.

### Phase 2: Idea Evolution Tracking

Track how the user's thinking on a topic changes over time. Surface the progression: "You first explored X in January, shifted to Y in March, and currently hold Z."

**Why it's unique:** No note-taking app does this. It turns scattered notes into a narrative of intellectual growth.

### Phase 3: Multi-Hop Reasoning

When an answer spans multiple notes, chain the connections. Note A mentions concept X, concept X is defined in note B, note B relates to note C. Present the full reasoning chain.

**Why it's unique:** Standard RAG retrieves documents independently. Multi-hop follows the *relationships* between ideas across notes.

### Phase 4: Query Decomposition & Search Transparency

Break complex questions into sub-queries. Show WHY each result was retrieved. "This note was found because it shares tags #marketing and contains the phrase 'Q1 goals'."

**Why it's unique:** Most RAG is a black box. Transparency builds trust and helps users understand their own knowledge.

### Phase 5: Proactive Recall & Smart Snippets

Surface relevant content *before* the user searches. Extract the most relevant sentence from each note (not fixed-window excerpts). Detect knowledge gaps and suggest creating notes.

**Why it's unique:** Proactive recall turns the app from a retrieval tool into a thinking partner.

---

## File Structure

| Action | File | Responsibility |
|--------|------|---------------|
| Create | `data/concept/ConceptExtractor.kt` | Extracts concepts/entities from note text using NLP heuristics |
| Create | `data/concept/ConceptGraph.kt` | Concept-centric graph (concept -> notes, concept -> concept) |
| Create | `data/concept/ConceptGraphRepository.kt` | Persistence + reactive StateFlow for concept graph |
| Create | `data/temporal/TemporalIndex.kt` | Tracks when concepts/topics appear across notes over time |
| Create | `data/search/QueryDecomposer.kt` | Breaks complex questions into sub-queries |
| Create | `data/search/SearchExplainer.kt` | Explains why each result was retrieved |
| Create | `data/search/SmartSnippetExtractor.kt` | Extracts the most relevant sentence, not fixed windows |
| Create | `data/search/ProactiveRecall.kt` | Surfaces relevant content proactively |
| Create | `data/search/MultiHopReasoner.kt` | Chains connections across notes for complex queries |
| Create | `ui/components/SearchExplanationDialog.kt` | UI for search transparency |
| Create | `ui/components/IdeaEvolutionPanel.kt` | UI showing how an idea evolved over time |
| Modify | `viewmodel/MainViewModel.kt` | Integrate new components into RAG pipeline |
| Modify | `data/search/NoteSearchIndex.kt` | Expose field-level match details for search explanation |
| Modify | `data/settings/SettingsManager.kt` | Add settings for new features |
| Modify | `ui/screens/SettingsSections.kt` | Add concept graph + advanced RAG settings |
| Modify | `ui/screens/chat/ChatScreen.kt` | Add idea evolution panel, search explanations |

---

## Phase 1: Concept Memory Graph

### Task 1: ConceptExtractor

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/concept/ConceptExtractor.kt`

**Interfaces:**
- Consumes: `NoteSearchIndex.tokenize()` for tokenization
- Produces: `ConceptExtractor.extractConcepts(text: String): List<ExtractedConcept>`

**What it does:** Extracts named concepts from note text using NLP heuristics (no external API). Identifies:
- **Named entities:** Proper nouns, capitalized phrases (people, places, products)
- **Domain terms:** Technical jargon, recurring terms across the user's notes
- **Action concepts:** Verb phrases ("launching the product", "fixing the bug")
- **Temporal concepts:** Time references ("Q1 2026", "next week", "last meeting")

- [ ] **Step 1: Create ConceptExtractor.kt**

```kotlin
package com.noteflowai.app.data.concept

import com.noteflowai.app.data.NoteFile
import com.noteflowai.app.data.search.NoteSearchIndex

/**
 * Extracts concepts, entities, and themes from note text.
 *
 * Uses lightweight NLP heuristics (no external API):
 * - Named entity recognition via capitalization patterns
 * - Recurring term extraction across the user's corpus
 * - Verb phrase detection for action concepts
 * - Temporal expression parsing
 *
 * Each concept gets a canonical form (lowercased, deduplicated) and a
 * frequency count across the corpus for importance weighting.
 */
class ConceptExtractor(private val searchIndex: NoteSearchIndex) {

    companion object {
        /** Minimum times a term must appear across corpus to be a "concept". */
        private const val MIN_CONCEPT_FREQUENCY = 2

        /** Max concepts per note to avoid noise. */
        private const val MAX_CONCEPTS_PER_NOTE = 15

        /** Min token length to consider as a concept. */
        private const val MIN_TOKEN_LENGTH = 3
    }

    /**
     * A single extracted concept from a note.
     */
    data class ExtractedConcept(
        val canonicalForm: String,      // lowercase, deduplicated
        val displayForm: String,        // original casing for display
        val type: ConceptType,
        val sourceNote: String,         // fileName
        val context: String,            // surrounding text snippet
        val importance: Float           // 0.0-1.0 based on corpus frequency
    )

    enum class ConceptType {
        ENTITY,         // Named entity (person, place, product)
        THEME,          // Recurring topic/theme
        ACTION,         // Verb phrase (doing something)
        TEMPORAL,       // Time reference
        TECHNICAL       // Domain-specific term
    }

    /**
     * Extract concepts from a single note.
     */
    fun extractFromNote(note: NoteFile): List<ExtractedConcept> {
        val text = note.content.take(4000)
        val tokens = searchIndex.tokenize(text)
        val concepts = mutableListOf<ExtractedConcept>()

        // 1. Named entities (capitalized multi-word phrases)
        concepts.addAll(extractEntities(text, note.fileName))

        // 2. Recurring themes (tokens that appear frequently in this note)
        concepts.addAll(extractThemes(tokens, note.fileName, text))

        // 3. Action concepts (verb + noun patterns)
        concepts.addAll(extractActions(text, note.fileName))

        // 4. Temporal concepts
        concepts.addAll(extractTemporal(text, note.fileName))

        return concepts
            .distinctBy { it.canonicalForm }
            .take(MAX_CONCEPTS_PER_NOTE)
    }

    /**
     * Extract concepts from the entire corpus.
     * Returns a map of concept -> list of notes it appears in.
     */
    fun extractCorpusConcepts(notes: List<NoteFile>): Map<String, List<ExtractedConcept>> {
        val allConcepts = mutableMapOf<String, MutableList<ExtractedConcept>>()

        for (note in notes) {
            val concepts = extractFromNote(note)
            for (concept in concepts) {
                allConcepts.getOrPut(concept.canonicalForm) { mutableListOf() }.add(concept)
            }
        }

        // Compute importance based on corpus frequency
        val totalNotes = notes.size.toFloat()
        return allConcepts.mapValues { (_, conceptList) ->
            val frequency = conceptList.size
            val importance = (frequency / totalNotes).coerceIn(0.1f, 1.0f)
            conceptList.map { it.copy(importance = importance) }
        }
    }

    // ── Named Entity Extraction ────────────────────────────────

    private fun extractEntities(text: String, fileName: String): List<ExtractedConcept> {
        val entities = mutableListOf<ExtractedConcept>()

        // Pattern: 2-4 consecutive capitalized words (excluding sentence starts)
        val entityPattern = Regex("(?<!\\.\\s)(?:\\b([A-Z][a-z]+(?:\\s[A-Z][a-z]+){0,3})\\b)")

        // Also capture ALL-CAPS terms (acronyms, product names)
        val acronymPattern = Regex("\\b([A-Z]{2,6})\\b")

        for (match in entityPattern.findAll(text)) {
            val entity = match.value.trim()
            if (entity.length >= MIN_TOKEN_LENGTH && !isStopPhrase(entity)) {
                val snippet = extractContext(text, match.range.first, 60)
                entities.add(ExtractedConcept(
                    canonicalForm = entity.lowercase(),
                    displayForm = entity,
                    type = ConceptType.ENTITY,
                    sourceNote = fileName,
                    context = snippet,
                    importance = 0.5f
                ))
            }
        }

        for (match in acronymPattern.findAll(text)) {
            val entity = match.value
            val snippet = extractContext(text, match.range.first, 60)
            entities.add(ExtractedConcept(
                canonicalForm = entity.lowercase(),
                displayForm = entity,
                type = ConceptType.ENTITY,
                sourceNote = fileName,
                context = snippet,
                importance = 0.6f
            ))
        }

        return entities
    }

    // ── Theme Extraction ───────────────────────────────────────

    private fun extractThemes(tokens: List<String>, fileName: String, text: String): List<ExtractedConcept> {
        val counts = mutableMapOf<String, Int>()
        for (token in tokens) {
            if (token.length >= MIN_TOKEN_LENGTH) {
                counts[token] = (counts[token] ?: 0) + 1
            }
        }

        return counts.entries
            .filter { it.value >= 2 }  // appears at least twice in this note
            .sortedByDescending { it.value }
            .take(5)
            .map { (term, count) ->
                val idx = text.lowercase().indexOf(term)
                val snippet = if (idx >= 0) extractContext(text, idx, 60) else ""
                ExtractedConcept(
                    canonicalForm = term,
                    displayForm = term,
                    type = ConceptType.THEME,
                    sourceNote = fileName,
                    context = snippet,
                    importance = (count.toFloat() / tokens.size).coerceIn(0.1f, 1.0f)
                )
            }
    }

    // ── Action Extraction ──────────────────────────────────────

    private fun extractActions(text: String, fileName: String): List<ExtractedConcept> {
        val actions = mutableListOf<ExtractedConcept>()
        val actionPatterns = listOf(
            Regex("\\b(\\w+ing\\s+\\w+(?:\\s+\\w+)?)", RegexOption.IGNORE_CASE),  // "launching the product"
            Regex("\\b(to\\s+\\w+\\s+\\w+)", RegexOption.IGNORE_CASE),              // "to fix the bug"
            Regex("\\b(should|need to|must|going to|plan to|want to)\\s+(\\w+(?:\\s+\\w+){0,2})", RegexOption.IGNORE_CASE)
        )

        for (pattern in actionPatterns) {
            for (match in pattern.findAll(text)) {
                val action = match.value.trim()
                if (action.length in 5..50) {
                    val snippet = extractContext(text, match.range.first, 60)
                    actions.add(ExtractedConcept(
                        canonicalForm = action.lowercase(),
                        displayForm = action,
                        type = ConceptType.ACTION,
                        sourceNote = fileName,
                        context = snippet,
                        importance = 0.4f
                    ))
                }
            }
        }

        return actions.distinctBy { it.canonicalForm }.take(3)
    }

    // ── Temporal Extraction ────────────────────────────────────

    private fun extractTemporal(text: String, fileName: String): List<ExtractedConcept> {
        val temporals = mutableListOf<ExtractedConcept>()
        val temporalPatterns = listOf(
            Regex("\\b(Q[1-4]\\s*\\d{4})\\b", RegexOption.IGNORE_CASE),           // "Q1 2026"
            Regex("\\b(\\d{4}-\\d{2}-\\d{2})\\b"),                                    // "2026-03-15"
            Regex("\\b(January|February|March|April|May|June|July|August|September|October|November|December)\\s+\\d{1,2}(?:st|nd|rd|th)?,?\\s*\\d{0,4}", RegexOption.IGNORE_CASE),
            Regex("\\b(last|next|this)\\s+(week|month|quarter|year|meeting|monday|tuesday|wednesday|thursday|friday)", RegexOption.IGNORE_CASE),
            Regex("\\b(\\d{1,2})\\s*(days?|weeks?|months?)\\s*(ago|from now|before|after)", RegexOption.IGNORE_CASE)
        )

        for (pattern in temporalPatterns) {
            for (match in pattern.findAll(text)) {
                val temporal = match.value.trim()
                val snippet = extractContext(text, match.range.first, 60)
                temporals.add(ExtractedConcept(
                    canonicalForm = temporal.lowercase(),
                    displayForm = temporal,
                    type = ConceptType.TEMPORAL,
                    sourceNote = fileName,
                    context = snippet,
                    importance = 0.3f
                ))
            }
        }

        return temporals.distinctBy { it.canonicalForm }.take(3)
    }

    // ── Helpers ────────────────────────────────────────────────

    private fun extractContext(text: String, index: Int, window: Int): String {
        val start = (index - window).coerceAtLeast(0)
        val end = (index + window).coerceAtMost(text.length)
        val snippet = text.substring(start, end).trim()
        val prefix = if (start > 0) "..." else ""
        val suffix = if (end < text.length) "..." else ""
        return "$prefix$snippet$suffix"
    }

    private fun isStopPhrase(phrase: String): Boolean {
        val stopStarts = setOf("The ", "And ", "But ", "For ", "Not ", "You ", "All ", "Can ", "Had ", "Her ", "Was ", "One ", "Our ", "Out ")
        return stopStarts.any { phrase.startsWith(it) }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/concept/ConceptExtractor.kt
git commit -m "feat: add ConceptExtractor for extracting ideas from notes"
```

---

### Task 2: ConceptGraph + Repository

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/concept/ConceptGraph.kt`
- Create: `app/src/main/java/com/noteflowai/app/data/concept/ConceptGraphRepository.kt`

**Interfaces:**
- Consumes: `ConceptExtractor`, `NoteFile`
- Produces: `ConceptGraphRepository.getInstance()`, `.getConcepts(query)`, `.getNotesForConcept(concept)`, `.getRelatedConcepts(concept)`

- [ ] **Step 1: Create ConceptGraph.kt**

```kotlin
package com.noteflowai.app.data.concept

import androidx.compose.runtime.Immutable

/**
 * A concept-centric knowledge graph.
 * Nodes are concepts (ideas, entities, themes), not files.
 * Edges connect concepts that co-occur in the same notes.
 */
@Immutable
data class ConceptNode(
    val canonicalForm: String,
    val displayForm: String,
    val type: ConceptExtractor.ConceptType,
    val noteCount: Int,              // how many notes mention this concept
    val importance: Float,           // corpus-wide importance
    val sampleNotes: List<String>,   // top notes containing this concept
    val firstSeen: Long,             // earliest note timestamp
    val lastSeen: Long               // latest note timestamp
)

@Immutable
data class ConceptEdge(
    val sourceConcept: String,
    val targetConcept: String,
    val strength: Float,             // co-occurrence strength [0,1]
    val coOccurrenceCount: Int       // how many notes contain both
)

@Immutable
data class ConceptGraph(
    val nodes: Map<String, ConceptNode> = emptyMap(),
    val edges: List<ConceptEdge> = emptyList(),
    val lastComputed: Long = 0L
) {
    /** Get all notes that mention a concept. */
    fun getNotesForConcept(concept: String): List<String> {
        return nodes[concept]?.sampleNotes ?: emptyList()
    }

    /** Get concepts related to a given concept (via edges). */
    fun getRelatedConcepts(concept: String, maxResults: Int = 5): List<Pair<String, Float>> {
        return edges
            .filter { it.sourceConcept == concept || it.targetConcept == concept }
            .map { edge ->
                val other = if (edge.sourceConcept == concept) edge.targetConcept else edge.sourceConcept
                other to edge.strength
            }
            .distinctBy { it.first }
            .sortedByDescending { it.second }
            .take(maxResults)
    }

    /** Search concepts by name (substring match). */
    fun searchConcepts(query: String, maxResults: Int = 10): List<ConceptNode> {
        val queryLower = query.lowercase()
        return nodes.values
            .filter { it.canonicalForm.contains(queryLower) || it.displayForm.lowercase().contains(queryLower) }
            .sortedByDescending { it.importance }
            .take(maxResults)
    }
}
```

- [ ] **Step 2: Create ConceptGraphRepository.kt**

```kotlin
package com.noteflowai.app.data.concept

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.noteflowai.app.data.NoteFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * File-based persistence for the concept graph.
 * Stores in [Context.filesDir]/graph/concept_graph.json
 */
class ConceptGraphRepository(private val extractor: ConceptExtractor) {

    companion object {
        private const val TAG = "ConceptGraphRepository"
        private const val DIR_NAME = "graph"
        private const val FILE_NAME = "concept_graph.json"
    }

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private val _graph = MutableStateFlow(ConceptGraph())
    val graph: StateFlow<ConceptGraph> = _graph.asStateFlow()

    fun loadFromDisk(context: Context): Boolean {
        val file = getFile(context)
        if (!file.exists()) return false
        return try {
            val raw = file.readText()
            val graph = gson.fromJson(raw, ConceptGraph::class.java)
            if (graph != null) {
                _graph.value = graph
                Log.i(TAG, "Loaded concept graph: ${graph.nodes.size} nodes, ${graph.edges.size} edges")
                true
            } else false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load concept graph: ${e.message}")
            false
        }
    }

    fun persistToDisk(context: Context) {
        val file = getFile(context)
        try {
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(_graph.value))
            Log.i(TAG, "Persisted concept graph: ${_graph.value.nodes.size} nodes")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist concept graph: ${e.message}")
        }
    }

    /**
     * Rebuild the concept graph from all notes.
     */
    fun rebuildGraph(notes: List<NoteFile>, context: Context) {
        val start = System.currentTimeMillis()
        val corpusConcepts = extractor.extractCorpusConcepts(notes)

        // Build nodes
        val nodes = mutableMapOf<String, ConceptNode>()
        for ((concept, extractions) in corpusConcepts) {
            val display = extractions.maxByOrNull { it.importance }?.displayForm ?: concept
            val type = extractions.maxByOrNull { it.importance }?.type
                ?: ConceptExtractor.ConceptType.THEME
            val sampleNotes = extractions.map { it.sourceNote }.distinct().take(5)

            nodes[concept] = ConceptNode(
                canonicalForm = concept,
                displayForm = display,
                type = type,
                noteCount = extractions.size,
                importance = extractions.maxOf { it.importance },
                sampleNotes = sampleNotes,
                firstSeen = 0L,  // TODO: get from NoteFile.lastModifiedEpoch
                lastSeen = 0L
            )
        }

        // Build edges (concepts that co-occur in the same notes)
        val edges = mutableListOf<ConceptEdge>()
        val conceptByNote = mutableMapOf<String, MutableSet<String>>()
        for ((concept, extractions) in corpusConcepts) {
            for (extraction in extractions) {
                conceptByNote.getOrPut(extraction.sourceNote) { mutableSetOf() }.add(concept)
            }
        }

        val edgeCounts = mutableMapOf<Pair<String, String>, Int>()
        for ((_, conceptSet) in conceptByNote) {
            val conceptList = conceptSet.toList()
            for (i in conceptList.indices) {
                for (j in i + 1 until conceptList.size) {
                    val key = if (conceptList[i] < conceptList[j])
                        conceptList[i] to conceptList[j]
                    else
                        conceptList[j] to conceptList[i]
                    edgeCounts[key] = (edgeCounts[key] ?: 0) + 1
                }
            }
        }

        for ((pair, count) in edgeCounts) {
            val maxPossible = (nodes[pair.first]?.noteCount ?: 1)
                .coerceAtMost(nodes[pair.second]?.noteCount ?: 1)
            val strength = (count.toFloat() / maxPossible).coerceIn(0.1f, 1.0f)
            if (strength >= 0.2f) {  // Only meaningful connections
                edges.add(ConceptEdge(
                    sourceConcept = pair.first,
                    targetConcept = pair.second,
                    strength = strength,
                    coOccurrenceCount = count
                ))
            }
        }

        _graph.value = ConceptGraph(
            nodes = nodes,
            edges = edges.sortedByDescending { it.strength },
            lastComputed = System.currentTimeMillis()
        )
        persistToDisk(context)

        val elapsed = System.currentTimeMillis() - start
        Log.i(TAG, "Concept graph built: ${nodes.size} nodes, ${edges.size} edges in ${elapsed}ms")
    }

    fun getConcepts(query: String): List<ConceptNode> {
        return _graph.value.searchConcepts(query)
    }

    fun getNotesForConcept(concept: String): List<String> {
        return _graph.value.getNotesForConcept(concept)
    }

    fun getRelatedConcepts(concept: String): List<Pair<String, Float>> {
        return _graph.value.getRelatedConcepts(concept)
    }

    fun nodeCount(): Int = _graph.value.nodes.size
    fun edgeCount(): Int = _graph.value.edges.size

    private fun getFile(context: Context): File {
        return File(context.filesDir, "$DIR_NAME/$FILE_NAME")
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/concept/ConceptGraph.kt \
       app/src/main/java/com/noteflowai/app/data/concept/ConceptGraphRepository.kt
git commit -m "feat: add ConceptGraph and repository for concept-centric knowledge"
```

---

### Task 3: Integrate Concept Graph into RAG Pipeline

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt`

**Interfaces:**
- Consumes: `ConceptGraphRepository`
- Produces: Concept-aware search results in `buildFinalSystemPrompt()`

- [ ] **Step 1: Add concept graph to MainViewModel fields**

In `MainViewModel.kt`, add these fields near the existing `noteSearchIndex`, `embeddingIndex`, etc.:

```kotlin
    private val conceptExtractor = com.noteflowai.app.data.concept.ConceptExtractor(noteSearchIndex)
    private val conceptGraphRepository = com.noteflowai.app.data.concept.ConceptGraphRepository(conceptExtractor)
```

- [ ] **Step 2: Expand search results with concept-connected notes**

In `buildFinalSystemPrompt()`, after the existing graph expansion block (around line 2142), add concept expansion:

```kotlin
                    // Concept expansion: find notes sharing extracted concepts
                    val conceptNodes = conceptGraphRepository.getConcepts(searchQuery)
                    for (concept in conceptNodes.take(3)) {
                        val conceptNotes = conceptGraphRepository.getNotesForConcept(concept.canonicalForm)
                            .filter { it !in primaryFiles }
                            .take(1)
                        for (noteFileName in conceptNotes) {
                            val note = savedNotes.value.find { it.fileName == noteFileName }
                            if (note != null) {
                                graphExpanded.add(com.noteflowai.app.data.search.NoteSearchIndex.SearchResult(
                                    fileName = noteFileName,
                                    title = note.fileName.removeSuffix(".md"),
                                    score = concept.importance * 0.4f,
                                    excerpt = note.preview.take(200)
                                ))
                            }
                        }
                    }
```

- [ ] **Step 3: Rebuild concept graph when notes change**

In `MainViewModel.kt`, find where `noteSearchIndex.rebuildIndex()` is called and add concept graph rebuild after it:

```kotlin
            // After search index rebuild
            conceptGraphRepository.rebuildGraph(notes, getApplication())
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt
git commit -m "feat: integrate concept graph into RAG pipeline"
```

---

## Phase 2: Idea Evolution Tracking

### Task 4: TemporalIndex

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/temporal/TemporalIndex.kt`

**Interfaces:**
- Consumes: `ConceptExtractor`, `NoteFile`
- Produces: `TemporalIndex.getEvolution(concept)`, `TemporalIndex.getTimeline(notes)`

- [ ] **Step 1: Create TemporalIndex.kt**

```kotlin
package com.noteflowai.app.data.temporal

import com.noteflowai.app.data.NoteFile
import com.noteflowai.app.data.concept.ConceptExtractor
import java.io.File
import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import android.util.Log

/**
 * Tracks when concepts appear across notes over time.
 * Enables "idea evolution" queries: how has the user's thinking on topic X changed?
 */
class TemporalIndex(private val extractor: ConceptExtractor) {

    companion object {
        private const val TAG = "TemporalIndex"
        private const val DIR_NAME = "graph"
        private const val FILE_NAME = "temporal_index.json"
    }

    private val gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * A concept's appearance in a specific note at a point in time.
     */
    data class ConceptAppearance(
        val noteFileName: String,
        val timestamp: Long,
        val context: String,      // snippet where concept appears
        val importance: Float
    )

    /**
     * Timeline of a concept across notes.
     */
    data class ConceptEvolution(
        val concept: String,
        val appearances: List<ConceptAppearance>,
        val noteCount: Int,
        val firstSeen: Long,
        val lastSeen: Long,
        val summary: String       // generated summary of the evolution
    )

    @Volatile
    private var appearances: Map<String, List<ConceptAppearance>> = emptyMap()

    fun loadFromDisk(context: Context): Boolean {
        val file = getFile(context)
        if (!file.exists()) return false
        return try {
            val raw = file.readText()
            @Suppress("UNCHECKED_CAST")
            appearances = gson.fromJson(raw, Map::class.java) as? Map<String, List<ConceptAppearance>>
                ?: emptyMap()
            Log.i(TAG, "Loaded temporal index: ${appearances.size} concepts")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load temporal index: ${e.message}")
            false
        }
    }

    fun persistToDisk(context: Context) {
        val file = getFile(context)
        try {
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(appearances))
            Log.i(TAG, "Persisted temporal index: ${appearances.size} concepts")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist temporal index: ${e.message}")
        }
    }

    /**
     * Build the temporal index from all notes.
     */
    fun rebuildIndex(notes: List<NoteFile>, context: Context) {
        val start = System.currentTimeMillis()
        val newAppearances = mutableMapOf<String, MutableList<ConceptAppearance>>()

        for (note in notes) {
            val concepts = extractor.extractFromNote(note)
            for (concept in concepts) {
                newAppearances.getOrPut(concept.canonicalForm) { mutableListOf() }
                    .add(ConceptAppearance(
                        noteFileName = note.fileName,
                        timestamp = note.lastModifiedEpoch,
                        context = concept.context,
                        importance = concept.importance
                    ))
            }
        }

        // Sort each concept's appearances by timestamp
        for ((concept, list) in newAppearances) {
            newAppearances[concept] = list.sortedBy { it.timestamp }.toMutableList()
        }

        appearances = newAppearances
        persistToDisk(context)

        val elapsed = System.currentTimeMillis() - start
        Log.i(TAG, "Temporal index built: ${appearances.size} concepts in ${elapsed}ms")
    }

    /**
     * Get the evolution of a concept over time.
     */
    fun getEvolution(concept: String): ConceptEvolution? {
        val apps = appearances[concept.lowercase()] ?: return null
        if (apps.isEmpty()) return null

        val summary = buildString {
            appendLine("Your thinking on '$concept' has evolved over ${apps.size} notes:")
            for ((i, app) in apps.withIndex()) {
                val timeStr = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US)
                    .format(java.util.Date(app.timestamp))
                appendLine("  ${i + 1}. $timeStr (${app.noteFileName.removeSuffix(".md")}): ${app.context.take(80)}")
            }
        }

        return ConceptEvolution(
            concept = concept,
            appearances = apps,
            noteCount = apps.map { it.noteFileName }.distinct().size,
            firstSeen = apps.first().timestamp,
            lastSeen = apps.last().timestamp,
            summary = summary
        )
    }

    /**
     * Get concepts that appear in a specific time range.
     */
    fun getConceptsInRange(startMs: Long, endMs: Long): Map<String, List<ConceptAppearance>> {
        return appearances.mapValues { (_, apps) ->
            apps.filter { it.timestamp in startMs..endMs }
        }.filter { it.value.isNotEmpty() }
    }

    /**
     * Get the most active concepts in recent notes.
     */
    fun getRecentConcepts(noteCount: Int = 5): List<Pair<String, Int>> {
        return appearances.map { (concept, apps) ->
            val recentNotes = apps.map { it.noteFileName }.distinct().take(noteCount)
            concept to recentNotes.size
        }.sortedByDescending { it.second }
    }

    fun conceptCount(): Int = appearances.size

    private fun getFile(context: Context): File {
        return File(context.filesDir, "$DIR_NAME/$FILE_NAME")
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/temporal/TemporalIndex.kt
git commit -m "feat: add TemporalIndex for idea evolution tracking"
```

---

### Task 5: IdeaEvolutionPanel UI

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/ui/components/IdeaEvolutionPanel.kt`

**Interfaces:**
- Consumes: `TemporalIndex.ConceptEvolution`
- Produces: `IdeaEvolutionPanel` composable

- [ ] **Step 1: Create IdeaEvolutionPanel.kt**

```kotlin
package com.noteflowai.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Timeline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.noteflowai.app.data.temporal.TemporalIndex
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shows how a concept evolved across notes over time.
 * Displays as a timeline with timestamps and context snippets.
 */
@Composable
fun IdeaEvolutionPanel(
    evolution: TemporalIndex.ConceptEvolution,
    onNoteClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Filled.Timeline,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "How your thinking evolved",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                "${evolution.noteCount} notes spanning ${formatTimeRange(evolution.firstSeen, evolution.lastSeen)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Timeline
            LazyColumn(
                modifier = Modifier.heightIn(max = 200.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(evolution.appearances) { index, appearance ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNoteClick(appearance.noteFileName) },
                        verticalAlignment = Alignment.Top
                    ) {
                        // Timeline dot + line
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(24.dp)
                        ) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = if (index == evolution.appearances.lastIndex)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(8.dp)
                            ) {}
                            if (index < evolution.appearances.lastIndex) {
                                Surface(
                                    modifier = Modifier
                                        .width(2.dp)
                                        .height(16.dp),
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                ) {}
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                SimpleDateFormat("MMM d, yyyy", Locale.US)
                                    .format(Date(appearance.timestamp)),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                appearance.noteFileName.removeSuffix(".md").replace("_", " "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { onNoteClick(appearance.noteFileName) }
                            )
                            Text(
                                appearance.context.take(100),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                maxLines = 2
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatTimeRange(first: Long, last: Long): String {
    val diffMs = last - first
    val days = diffMs / (1000 * 60 * 60 * 24)
    return when {
        days < 1 -> "today"
        days < 7 -> "$days days"
        days < 30 -> "${days / 7} weeks"
        days < 365 -> "${days / 30} months"
        else -> "${days / 365} years"
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/ui/components/IdeaEvolutionPanel.kt
git commit -m "feat: add IdeaEvolutionPanel composable"
```

---

## Phase 3: Multi-Hop Reasoning

### Task 6: MultiHopReasoner

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/search/MultiHopReasoner.kt`

**Interfaces:**
- Consumes: `ConceptGraphRepository`, `NoteSearchIndex`, `EmbeddingIndex`
- Produces: `MultiHopReasoner.findConnections(query, startNotes): MultiHopResult`

- [ ] **Step 1: Create MultiHopReasoner.kt**

```kotlin
package com.noteflowai.app.data.search

import com.noteflowai.app.data.concept.ConceptGraphRepository

/**
 * Chains connections across notes for complex queries.
 *
 * When an answer spans multiple notes, follows the reasoning chain:
 * Note A mentions concept X -> concept X appears in note B -> note B relates to note C
 *
 * Returns the chain with explanations for each hop.
 */
class MultiHopReasoner(
    private val conceptGraph: ConceptGraphRepository,
    private val searchIndex: NoteSearchIndex
) {

    /**
     * A single hop in a multi-hop reasoning chain.
     */
    data class Hop(
        val noteFileName: String,
        val noteTitle: String,
        val reason: String,          // why this note was included
        val viaConcept: String?,     // concept that connected to this note
        val strength: Float
    )

    /**
     * Result of multi-hop reasoning.
     */
    data class MultiHopResult(
        val hops: List<Hop>,
        val chainExplanation: String,  // human-readable explanation of the chain
        val confidence: Float          // overall confidence in the chain
    )

    companion object {
        private const val MAX_HOPS = 4
        private const val MIN_HOP_STRENGTH = 0.3f
    }

    /**
     * Find multi-hop connections starting from initial search results.
     *
     * @param query The original search query
     * @param startNotes The initial BM25/embedding search results
     * @return Multi-hop reasoning chain, or empty if no connections found
     */
    fun findConnections(
        query: String,
        startNotes: List<NoteSearchIndex.SearchResult>
    ): MultiHopResult? {
        if (startNotes.isEmpty()) return null

        val visitedNotes = mutableSetOf<String>()
        val hops = mutableListOf<Hop>()

        // Start with initial results
        for (note in startNotes.take(2)) {
            hops.add(Hop(
                noteFileName = note.fileName,
                noteTitle = note.title,
                reason = "Direct match for query",
                viaConcept = null,
                strength = note.score
            ))
            visitedNotes.add(note.fileName)
        }

        // Follow concept connections
        for (i in 0 until MAX_HOPS - hops.size) {
            val lastNote = hops.lastOrNull()?.noteFileName ?: break

            // Find concepts in the last note
            val noteConcepts = conceptGraph.getConcepts(lastNote.replace(".md", ""))
            if (noteConcepts.isEmpty()) break

            // Find notes connected via the strongest concept
            var bestNext: Pair<String, Float>? = null
            var bestConcept: String? = null

            for (concept in noteConcepts.take(3)) {
                val relatedNotes = conceptGraph.getNotesForConcept(concept.canonicalForm)
                for (relatedNote in relatedNotes) {
                    if (relatedNote !in visitedNotes) {
                        val edgeStrength = concept.importance
                        if (edgeStrength >= MIN_HOP_STRENGTH) {
                            if (bestNext == null || edgeStrength > bestNext.second) {
                                bestNext = relatedNote to edgeStrength
                                bestConcept = concept.displayForm
                            }
                        }
                    }
                }
            }

            if (bestNext == null) break

            visitedNotes.add(bestNext.first)
            hops.add(Hop(
                noteFileName = bestNext.first,
                noteTitle = bestNext.first.removeSuffix(".md"),
                reason = "Connected via concept: $bestConcept",
                viaConcept = bestConcept,
                strength = bestNext.second
            ))
        }

        if (hops.size <= 1) return null  // No actual multi-hop found

        val chainExplanation = buildString {
            appendLine("Reasoning chain:")
            for ((i, hop) in hops.withIndex()) {
                if (i == 0) {
                    appendLine("  ${i + 1}. \"${hop.noteTitle}\" -- ${hop.reason}")
                } else {
                    appendLine("  ${i + 1}. \"${hop.noteTitle}\" -- ${hop.reason}")
                }
            }
        }

        val avgStrength = hops.map { it.strength }.average().toFloat()

        return MultiHopResult(
            hops = hops,
            chainExplanation = chainExplanation,
            confidence = avgStrength
        )
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/search/MultiHopReasoner.kt
git commit -m "feat: add MultiHopReasoner for chained reasoning across notes"
```

---

## Phase 4: Query Decomposition & Search Transparency

### Task 7: QueryDecomposer

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/search/QueryDecomposer.kt`

**Interfaces:**
- Consumes: `NoteSearchIndex.tokenize()`
- Produces: `QueryDecomposer.decompose(query): DecomposedQuery`

- [ ] **Step 1: Create QueryDecomposer.kt**

```kotlin
package com.noteflowai.app.data.search

/**
 * Breaks complex questions into sub-queries for better retrieval.
 *
 * Handles:
 * - Comparison queries: "X vs Y" -> search for X, search for Y, compare
 * - List queries: "What are my notes about X, Y, and Z?" -> search each
 * - Temporal queries: "notes from last week about X" -> filter by time + search
 * - Multi-aspect queries: "X and how it relates to Y" -> search X, search Y, find intersection
 */
class QueryDecomposer {

    data class SubQuery(
        val text: String,
        val purpose: String,    // "primary", "comparison", "context", "temporal"
        val weight: Float       // relative importance [0,1]
    )

    data class DecomposedQuery(
        val original: String,
        val subQueries: List<SubQuery>,
        val isComplex: Boolean,
        val decompositionStrategy: String
    )

    /**
     * Analyze and decompose a query into sub-queries.
     */
    fun decompose(query: String): DecomposedQuery {
        val trimmed = query.trim()

        // Check for comparison patterns
        val comparisonResult = decomposeComparison(trimmed)
        if (comparisonResult != null) return comparisonResult

        // Check for list patterns
        val listResult = decomposeList(trimmed)
        if (listResult != null) return listResult

        // Check for temporal modifiers
        val temporalResult = decomposeTemporal(trimmed)
        if (temporalResult != null) return temporalResult

        // Check for multi-aspect queries (contains "and", "how", "relate")
        val multiAspectResult = decomposeMultiAspect(trimmed)
        if (multiAspectResult != null) return multiAspectResult

        // Simple query -- no decomposition needed
        return DecomposedQuery(
            original = trimmed,
            subQueries = listOf(SubQuery(trimmed, "primary", 1.0f)),
            isComplex = false,
            decompositionStrategy = "none"
        )
    }

    private fun decomposeComparison(query: String): DecomposedQuery? {
        val patterns = listOf(
            Regex("(.+?)\\s+vs\\.?\\s+(.+?)$", RegexOption.IGNORE_CASE),
            Regex("(.+?)\\s+versus\\s+(.+?)$", RegexOption.IGNORE_CASE),
            Regex("compare\\s+(.+?)\\s+(?:and|with|to)\\s+(.+?)$", RegexOption.IGNORE_CASE),
            Regex("(.+?)\\s+(?:compared? to|relative to)\\s+(.+?)$", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(query) ?: continue
            val (left, right) = match.destructured
            return DecomposedQuery(
                original = query,
                subQueries = listOf(
                    SubQuery(left.trim(), "comparison", 0.5f),
                    SubQuery(right.trim(), "comparison", 0.5f)
                ),
                isComplex = true,
                decompositionStrategy = "comparison"
            )
        }
        return null
    }

    private fun decomposeList(query: String): DecomposedQuery? {
        // "notes about X, Y, and Z" or "X, Y, Z"
        val listPattern = Regex("(?:notes?\\s+(?:about|on|regarding)\\s+)?(.+?),\\s+(.+?)(?:,\\s+and\\s+(.+?))?$", RegexOption.IGNORE_CASE)
        val match = listPattern.find(query) ?: return null

        val items = mutableListOf(match.groupValues[1].trim())
        if (match.groupValues[2].isNotBlank()) items.add(match.groupValues[2].trim())
        if (match.groupValues.size > 3 && match.groupValues[3].isNotBlank()) items.add(match.groupValues[3].trim())

        if (items.size < 2) return null

        return DecomposedQuery(
            original = query,
            subQueries = items.map { SubQuery(it, "primary", 1.0f / items.size) },
            isComplex = true,
            decompositionStrategy = "list"
        )
    }

    private fun decomposeTemporal(query: String): DecomposedQuery? {
        val temporalPattern = Regex("(?:notes?\\s+)?(?:from|before|after|during|in)\\s+(last|this|next|past)\\s+(week|month|quarter|year|day|days|weeks|months)\\s*(?:about|on|regarding)?\\s*(.*)", RegexOption.IGNORE_CASE)
        val match = temporalPattern.find(query) ?: return null

        val timeRef = "${match.groupValues[1]} ${match.groupValues[2]}"
        val topic = match.groupValues[3].trim()

        val subQueries = mutableListOf(SubQuery(timeRef, "temporal", 0.3f))
        if (topic.isNotBlank()) {
            subQueries.add(SubQuery(topic, "primary", 0.7f))
        }

        return DecomposedQuery(
            original = query,
            subQueries = subQueries,
            isComplex = true,
            decompositionStrategy = "temporal"
        )
    }

    private fun decomposeMultiAspect(query: String): DecomposedQuery? {
        val multiPattern = Regex("(.+?)\\s+(?:and|how|relating? to|connected? to|related? to)\\s+(.+?)$", RegexOption.IGNORE_CASE)
        val match = multiPattern.find(query) ?: return null

        val (left, right) = match.destructured
        if (left.length < 3 || right.length < 3) return null

        return DecomposedQuery(
            original = query,
            subQueries = listOf(
                SubQuery(left.trim(), "primary", 0.6f),
                SubQuery(right.trim(), "context", 0.4f)
            ),
            isComplex = true,
            decompositionStrategy = "multi_aspect"
        )
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/search/QueryDecomposer.kt
git commit -m "feat: add QueryDecomposer for complex question handling"
```

---

### Task 8: SearchExplainer

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/search/SearchExplainer.kt`
- Create: `app/src/main/java/com/noteflowai/app/ui/components/SearchExplanationDialog.kt`

**Interfaces:**
- Consumes: `NoteSearchIndex.SearchResult`, `conceptGraph`
- Produces: `SearchExplainer.explain(result): SearchExplanation`

- [ ] **Step 1: Create SearchExplainer.kt**

```kotlin
package com.noteflowai.app.data.search

import com.noteflowai.app.data.concept.ConceptGraphRepository

/**
 * Explains WHY a search result was retrieved.
 * Provides transparency into the RAG system's decision-making.
 */
class SearchExplainer(private val conceptGraph: ConceptGraphRepository) {

    data class SearchExplanation(
        val noteTitle: String,
        val overallScore: Float,
        val reasons: List<RetrievalReason>,
        val concepts: List<String>     // concepts shared with query
    )

    data class RetrievalReason(
        val type: String,      // "title_match", "tag_match", "content_match", "concept_match", "graph_connection", "embedding_match"
        val detail: String,    // human-readable explanation
        val weight: Float      // contribution to final score
    )

    /**
     * Generate an explanation for why a result was retrieved.
     */
    fun explain(
        result: NoteSearchIndex.SearchResult,
        query: String,
        isGraphExpanded: Boolean = false,
        isConceptExpanded: Boolean = false
    ): SearchExplanation {
        val reasons = mutableListOf<RetrievalReason>()
        val queryTokens = result.title.lowercase().split("\\s+".toRegex())

        // Check title match
        val titleLower = result.title.lowercase()
        val queryLower = query.lowercase()
        if (queryLower.split(" ").any { titleLower.contains(it) }) {
            reasons.add(RetrievalReason(
                type = "title_match",
                detail = "Title contains query terms",
                weight = 0.3f
            ))
        }

        // Check field matches from SearchResult.matchedFields
        if (result.matchedFields.contains("tags")) {
            reasons.add(RetrievalReason(
                type = "tag_match",
                detail = "Tags match the query",
                weight = 0.25f
            ))
        }
        if (result.matchedFields.contains("category")) {
            reasons.add(RetrievalReason(
                type = "category_match",
                detail = "Same category as query context",
                weight = 0.15f
            ))
        }
        if (result.matchedFields.contains("content")) {
            reasons.add(RetrievalReason(
                type = "content_match",
                detail = "Content contains relevant terms",
                weight = 0.2f
            ))
        }

        // Graph expansion
        if (isGraphExpanded) {
            reasons.add(RetrievalReason(
                type = "graph_connection",
                detail = "Connected via knowledge graph",
                weight = 0.15f
            ))
        }

        // Concept expansion
        if (isConceptExpanded) {
            reasons.add(RetrievalReason(
                type = "concept_match",
                detail = "Shares extracted concepts with query",
                weight = 0.15f
            ))
        }

        // Find shared concepts
        val sharedConcepts = conceptGraph.getConcepts(query)
            .filter { concept ->
                concept.sampleNotes.any { it == result.fileName }
            }
            .map { it.displayForm }

        return SearchExplanation(
            noteTitle = result.title,
            overallScore = result.score,
            reasons = reasons,
            concepts = sharedConcepts
        )
    }
}
```

- [ ] **Step 2: Create SearchExplanationDialog.kt**

```kotlin
package com.noteflowai.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.noteflowai.app.data.search.SearchExplainer

/**
 * Shows why a search result was retrieved.
 * Displays reasoning chain and contributing factors.
 */
@Composable
fun SearchExplanationDialog(
    explanation: SearchExplainer.SearchExplanation,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Default.Lightbulb, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        title = {
            Text("Why this note?", fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(
                    "\"${explanation.noteTitle}\"",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "Relevance: ${(explanation.overallScore * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text("Retrieved because:", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)

                Spacer(modifier = Modifier.height(4.dp))

                explanation.reasons.forEach { reason ->
                    Row(
                        modifier = Modifier.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                            modifier = Modifier.size(6.dp)
                        ) {}
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            reason.detail,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                if (explanation.concepts.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Shared concepts:", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        explanation.concepts.take(5).forEach { concept ->
                            SuggestionChip(
                                onClick = {},
                                label = { Text(concept, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/search/SearchExplainer.kt \
       app/src/main/java/com/noteflowai/app/ui/components/SearchExplanationDialog.kt
git commit -m "feat: add SearchExplainer and explanation dialog for transparent RAG"
```

---

## Phase 5: Proactive Recall & Smart Snippets

### Task 9: SmartSnippetExtractor

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/search/SmartSnippetExtractor.kt`

**Interfaces:**
- Consumes: note content, query tokens
- Produces: `SmartSnippetExtractor.extract(text, query): SmartSnippet`

- [ ] **Step 1: Create SmartSnippetExtractor.kt**

```kotlin
package com.noteflowai.app.data.search

/**
 * Extracts the most relevant sentence/paragraph from a note.
 *
 * Instead of fixed-window excerpts, finds the passage that best
 * matches the query using sentence-level scoring.
 */
class SmartSnippetExtractor {

    data class SmartSnippet(
        val text: String,
        val score: Float,          // relevance of this snippet
        val sentenceIndex: Int,    // position in the note
        val isPartial: Boolean     // true if truncated
    )

    companion object {
        private const val MAX_SNIPPET_LENGTH = 300
        private const val CONTEXT_SENTENCES = 1  // sentences before/after the best match
    }

    /**
     * Extract the most relevant snippet from note content.
     *
     * @param text Full note content
     * @param queryTokens Tokenized search query
     * @return Best matching snippet
     */
    fun extract(text: String, queryTokens: List<String>): SmartSnippet {
        if (text.isBlank() || queryTokens.isEmpty()) {
            return SmartSnippet(
                text = text.take(MAX_SNIPPET_LENGTH).trim(),
                score = 0f,
                sentenceIndex = 0,
                isPartial = text.length > MAX_SNIPPET_LENGTH
            )
        }

        // Split into sentences
        val sentences = splitSentences(text)
        if (sentences.isEmpty()) {
            return SmartSnippet(text.take(MAX_SNIPPET_LENGTH).trim(), 0f, 0, false)
        }

        // Score each sentence
        val scored = sentences.mapIndexed { index, sentence ->
            val sentenceTokens = tokenizeSimple(sentence)
            val score = computeRelevance(sentenceTokens, queryTokens)
            Triple(index, sentence, score)
        }

        // Find the best sentence
        val best = scored.maxByOrNull { it.third } ?: scored.first()

        // Expand to include context sentences
        val startIdx = (best.first - CONTEXT_SENTENCES).coerceAtLeast(0)
        val endIdx = (best.first + CONTEXT_SENTENCES).coerceAtMost(sentences.lastIndex)

        val snippet = (startIdx..endIdx).joinToString(" ") { sentences[it].trim() }

        val truncated = if (snippet.length > MAX_SNIPPET_LENGTH) {
            snippet.take(MAX_SNIPPET_LENGTH).trim() + "..."
        } else {
            snippet
        }

        return SmartSnippet(
            text = truncated,
            score = best.third,
            sentenceIndex = best.first,
            isPartial = snippet.length > MAX_SNIPPET_LENGTH
        )
    }

    private fun computeRelevance(sentenceTokens: List<String>, queryTokens: List<String>): Float {
        if (sentenceTokens.isEmpty() || queryTokens.isEmpty()) return 0f

        var matches = 0
        for (qt in queryTokens) {
            if (sentenceTokens.any { it.contains(qt) || qt.contains(it) }) {
                matches++
            }
        }
        return matches.toFloat() / queryTokens.size
    }

    private fun splitSentences(text: String): List<String> {
        return text.split(Regex("[.!?]+\\s+"))
            .map { it.trim() }
            .filter { it.length > 10 }  // skip very short fragments
    }

    private fun tokenizeSimple(text: String): List<String> {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 2 }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/search/SmartSnippetExtractor.kt
git commit -m "feat: add SmartSnippetExtractor for context-aware excerpt selection"
```

---

### Task 10: Integrate All New Components into MainViewModel

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt`

**Interfaces:**
- Consumes: All new components (ConceptGraph, TemporalIndex, MultiHopReasoner, QueryDecomposer, SearchExplainer, SmartSnippet)
- Produces: Enhanced RAG pipeline with all new capabilities

- [ ] **Step 1: Add all new component fields**

In `MainViewModel.kt`, add these fields:

```kotlin
    // New RAG v2 components
    private val queryDecomposer = com.noteflowai.app.data.search.QueryDecomposer()
    private val searchExplainer = com.noteflowai.app.data.search.SearchExplainer(conceptGraphRepository)
    private val multiHopReasoner = com.noteflowai.app.data.search.MultiHopReasoner(conceptGraphRepository, noteSearchIndex)
    private val smartSnippetExtractor = com.noteflowai.app.data.search.SmartSnippetExtractor()
    private val temporalIndex = com.noteflowai.app.data.temporal.TemporalIndex(conceptExtractor)
```

- [ ] **Step 2: Use SmartSnippet instead of fixed excerpts**

In `buildFinalSystemPrompt()`, where excerpts are generated, replace:

```kotlin
// OLD: excerpt = r.excerpt
// NEW: excerpt = smartSnippetExtractor.extract(r.excerpt, searchIndex.tokenize(searchQuery)).text
```

- [ ] **Step 3: Add query decomposition to search flow**

In `buildFinalSystemPrompt()`, before the search call, decompose complex queries:

```kotlin
            val decomposition = queryDecomposer.decompose(searchQuery)
            val searchQueries = if (decomposition.isComplex) {
                decomposition.subQueries.map { it.text }
            } else {
                listOf(searchQuery)
            }

            // Search with decomposed queries, then merge results
            val allResults = mutableListOf<com.noteflowai.app.data.search.NoteSearchIndex.SearchResult>()
            for (q in searchQueries) {
                allResults.addAll(hybridSearch(q, maxResults = ragMaxExcerpts, ...))
            }
            val results = allResults.distinctBy { it.fileName }
                .sortedByDescending { it.score }
                .take(ragMaxExcerpts)
```

- [ ] **Step 4: Add multi-hop for complex queries**

After getting initial results, if query is complex, run multi-hop:

```kotlin
            if (decomposition.isComplex) {
                val multiHop = multiHopReasoner.findConnections(searchQuery, results)
                if (multiHop != null && multiHop.confidence >= 0.4f) {
                    // Add multi-hop results
                    for (hop in multiHop.hops.drop(2)) {  // skip first 2 (already in results)
                        if (hop.noteFileName !in results.map { it.fileName }) {
                            val note = savedNotes.value.find { it.fileName == hop.noteFileName }
                            if (note != null) {
                                results = results + com.noteflowai.app.data.search.NoteSearchIndex.SearchResult(
                                    fileName = hop.noteFileName,
                                    title = hop.noteTitle,
                                    score = hop.strength * 0.6f,
                                    excerpt = smartSnippetExtractor.extract(note.content.take(2000), searchIndex.tokenize(searchQuery)).text
                                )
                            }
                        }
                    }
                }
            }
```

- [ ] **Step 5: Rebuild temporal index alongside concept graph**

In the index rebuild path, add:

```kotlin
            temporalIndex.rebuildIndex(notes, getApplication())
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt
git commit -m "feat: integrate all RAG v2 components into main pipeline"
```

---

### Task 11: Add RAG v2 Settings

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/data/settings/SettingsManager.kt`
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/SettingsSections.kt`

**Interfaces:**
- Consumes: existing settings patterns
- Produces: settings for concept graph, multi-hop, search explanations

- [ ] **Step 1: Add settings keys**

In `SettingsManager.kt` companion object, add:

```kotlin
        val CONCEPT_GRAPH_ENABLED = booleanPreferencesKey("concept_graph_enabled")
        val MULTI_HOP_ENABLED = booleanPreferencesKey("multi_hop_enabled")
        val SEARCH_EXPLANATIONS_ENABLED = booleanPreferencesKey("search_explanations_enabled")
        val SMART_SNIPPETS_ENABLED = booleanPreferencesKey("smart_snippets_enabled")
```

Add corresponding Flow properties and setters following the existing pattern.

- [ ] **Step 2: Add settings UI section**

In `SettingsSections.kt`, add a new "Advanced RAG" collapsible section with toggles for each new feature.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/settings/SettingsManager.kt \
       app/src/main/java/com/noteflowai/app/ui/screens/SettingsSections.kt
git commit -m "feat: add RAG v2 settings for concept graph, multi-hop, explanations"
```

---

### Task 12: Build Verification

- [ ] **Step 1: Run full build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run lint**

Run: `./gradlew lintDebug`
Expected: No new errors

- [ ] **Step 3: Commit any fixes**

```bash
git add -A
git commit -m "fix: resolve build/lint issues from RAG v2 integration"
```

---

## Implementation Order

| # | Task | Effort | Dependency | What ships |
|---|------|--------|------------|------------|
| 1 | ConceptExtractor | Medium | None | Concept extraction from notes |
| 2 | ConceptGraph + Repository | Medium | #1 | Concept-centric knowledge graph |
| 3 | Integrate concepts into RAG | Low | #2 | Concept-expanded search results |
| 4 | TemporalIndex | Medium | #1 | Idea evolution tracking |
| 5 | IdeaEvolutionPanel UI | Low | #4 | Timeline visualization |
| 6 | MultiHopReasoner | Medium | #2 | Chained reasoning across notes |
| 7 | QueryDecomposer | Medium | None | Complex question handling |
| 8 | SearchExplainer + Dialog | Medium | #2 | Search transparency |
| 9 | SmartSnippetExtractor | Low | None | Context-aware excerpts |
| 10 | Integrate into MainViewModel | Medium | All above | Full RAG v2 pipeline |
| 11 | Settings | Low | #10 | User-configurable features |
| 12 | Build verification | Low | All | Compiles |

**Milestone checkpoints:**
- After #3: Concept graph expands search results. App finds notes by *ideas*, not just keywords.
- After #5: Idea evolution panel shows how thinking changed over time.
- After #8: Users can see WHY each result was retrieved. Search is no longer a black box.
- After #10: Full RAG v2 pipeline. Complex questions decompose, multi-hop chains, smart snippets.

---

## What Makes This Unique (Summary)

| Feature | What it does | Why it's different |
|---------|-------------|-------------------|
| **Concept Memory Graph** | Extracts ideas/entities, builds concept-centric graph | Finds notes by *meaning*, not just keywords |
| **Idea Evolution Tracking** | Shows how thinking changed over time | Turns scattered notes into a narrative |
| **Multi-Hop Reasoning** | Chains connections across notes | Follows the *relationships* between ideas |
| **Query Decomposition** | Breaks complex questions into sub-queries | Handles "compare X and Y" properly |
| **Search Transparency** | Shows WHY each result was retrieved | Builds trust, helps users understand their knowledge |
| **Smart Snippets** | Extracts the most relevant sentence | No more random fixed-window excerpts |
| **Proactive Recall** | Surfaces relevant content before searching | Turns app from tool into thinking partner |
