package com.noteflowai.app.data.graph

import android.util.Log
import com.noteflowai.app.data.NoteFile
import com.noteflowai.app.data.concept.ConceptGraphRepository
import com.noteflowai.app.data.noteDisplayTitle
import com.noteflowai.app.data.search.EmbeddingIndex
import com.noteflowai.app.data.search.NoteSearchIndex
import kotlin.math.sqrt

/**
 * Automatically computes connections between notes using multiple signals:
 *
 * 1. Cross-references (strength 0.9): Note A mentions Note B's title/filename as whole words
 * 2. Shared tags (strength 0.8): Notes sharing 2+ tags are strongly linked
 * 3. Content similarity (strength 0.3-0.7): BM25 score between notes
 * 4. Semantic similarity (strength 0-0.8): embedding cosine similarity
 * 5. Shared concepts (strength 0.6): both notes mention the same extracted concepts
 * 6. Shared entities (strength 0.7): both notes reference the same memory-layer entities
 * 7. Category match (strength 0.1): Weak signal, same category
 * 8. Temporal proximity (strength 0.2): Notes modified within 24h of each other
 *
 * Signals are combined into a single weighted strength per pair: the strongest
 * contributing signal, plus a small bonus for each additional independent signal
 * above a floor. Pairs below a precision threshold are dropped, which suppresses
 * the noisy category-only and temporal-only edges. Connections are capped at 10
 * per note to keep the graph sparse.
 */
class AutoLinker(
    private val searchIndex: NoteSearchIndex,
    private val embeddingIndex: EmbeddingIndex? = null,
    private val conceptGraphRepository: ConceptGraphRepository? = null
) {

    companion object {
        private const val TAG = "AutoLinker"
        private const val MAX_CONNECTIONS_PER_NOTE = 10
        private const val TEMPORAL_WINDOW_MS = 24 * 60 * 60 * 1000L  // 24 hours

        // Precision floor: pairs whose combined strength is below this are dropped.
        // Category-only (0.1) and temporal-only (0.2) edges never clear this.
        private const val MIN_CONNECTION_STRENGTH = 0.4f

        // Bonus applied for each additional independent signal above the floor.
        private const val SIGNAL_AGREEMENT_BONUS = 0.15f
        private const val SIGNAL_AGREEMENT_FLOOR = 0.3f

        // Strength constants
        private const val STRENGTH_CROSS_REF = 0.9f
        private const val STRENGTH_SHARED_TAGS = 0.8f
        private const val STRENGTH_CATEGORY = 0.1f
        private const val STRENGTH_TEMPORAL = 0.2f
        private const val STRENGTH_SHARED_CONCEPTS = 0.6f
        private const val STRENGTH_SHARED_ENTITIES = 0.7f
        private const val MIN_SHARED_CONCEPTS = 2
        private const val MIN_SHARED_ENTITIES = 2
        private const val MIN_SHARED_TAGS = 2
        // Minimum number of distinct content tokens two notes must actually share
        // before BM25 is trusted as a topical-similarity signal. A lone shared word
        // (e.g. "tomato" in two otherwise-unrelated notes) can carry a high BM25 idf
        // weight and would otherwise manufacture a false connection on its own.
        private const val MIN_SHARED_CONTENT_TOKENS = 2

        // How many notes per BM25 search to consider as content-similar candidates.
        private const val BM25_CANDIDATES_PER_NOTE = 20

        // Signal tags used for attribution.
        private const val SIGNAL_CROSS_REF = "auto_content"
        private const val SIGNAL_CONTENT = "auto_similar"   // BM25 topical similarity
        private const val SIGNAL_TAGS = "auto_tags"
        private const val SIGNAL_SEMANTIC = "auto_semantic"
        private const val SIGNAL_CONCEPT = "auto_concept"
        private const val SIGNAL_ENTITY = "auto_entity"
        private const val SIGNAL_CATEGORY = "auto_category"
        private const val SIGNAL_TEMPORAL = "auto_temporal"
    }

    /**
     * Compute all auto-connections for the given notes.
     *
     * @param noteEntityIds Optional map of note fileName -> set of memory-layer entity
     *   ids extracted from that note. Built by the caller (e.g. MainViewModel) from the
     *   memory database; used to link notes that reference the same entities.
     */
    fun computeGraph(
        notes: List<NoteFile>,
        noteEntityIds: Map<String, Set<String>> = emptyMap()
    ): NoteGraph {
        if (notes.size < 2) return NoteGraph()

        val start = System.currentTimeMillis()
        val connections = mutableListOf<NoteConnection>()

        // ── Precompute once (avoid O(N^2) rebuilds) ─────────────────
        // BM25: build a single index, then per-note search for similar candidates.
        val localIndex = NoteSearchIndex()
        localIndex.rebuildIndex(notes)
        val bm25Candidates = notes.associate { note ->
            val scores = localIndex.search(note.content.take(500), maxResults = BM25_CANDIDATES_PER_NOTE)
                .filter { it.fileName != note.fileName }
                .associate { it.fileName to it.score }
            note.fileName to scores
        }

        // Content token sets: precomputed once so the per-pair overlap check in the
        // inner loop never re-tokenizes (avoid O(N^2) tokenization).
        val noteContentTokens: Map<String, Set<String>> = notes.associate {
            it.fileName to searchIndex.tokenize(it.content).toSet()
        }

        // Embeddings: read vectors once into a lookup.
        val vectors = if (embeddingIndex != null) {
            notes.associate { it.fileName to embeddingIndex.getEmbedding(it.fileName) }
        } else emptyMap()

        // Concepts: precompute each note's set of concept canonical forms.
        val noteConceptSets: Map<String, Set<String>> =
            if (conceptGraphRepository != null) {
                val allConcepts = conceptGraphRepository.getAllConcepts()
                notes.associate { note ->
                    val concepts = allConcepts
                        .filter { note.fileName in it.sampleNotes }
                        .map { it.canonicalForm }
                        .toSet()
                    note.fileName to concepts
                }
            } else emptyMap()

        for (i in notes.indices) {
            val noteA = notes[i]
            val noteConnections = mutableListOf<NoteConnection>()

            for (j in notes.indices) {
                if (i == j) continue
                val noteB = notes[j]

                val signalStrengths = mutableListOf<Pair<String, Float>>()

                // 1. Cross-references (word-boundary): does noteA mention noteB's title or filename?
                val crossRefStrength = crossReferenceStrength(noteA.content, noteB)
                if (crossRefStrength > 0f) {
                    signalStrengths.add(SIGNAL_CROSS_REF to crossRefStrength)
                }

                // 2. Shared tags
                val sharedTags = noteA.tags.intersect(noteB.tags.toSet())
                if (sharedTags.size >= MIN_SHARED_TAGS) {
                    signalStrengths.add(SIGNAL_TAGS to STRENGTH_SHARED_TAGS)
                }

                // 3. BM25 content similarity (from the prebuilt index).
                // Requires a meaningful number of shared content tokens: BM25's idf
                // inflates a single rare shared term (short corpora especially), so a
                // lone coincidental word must not on its own create a topical link.
                val sharedContentTokens = noteContentTokens[noteA.fileName]
                    ?.intersect(noteContentTokens[noteB.fileName].orEmpty())
                    ?.size ?: 0
                val contentScore = bm25Candidates[noteA.fileName]?.get(noteB.fileName) ?: 0f
                if (contentScore > 0f && sharedContentTokens >= MIN_SHARED_CONTENT_TOKENS) {
                    // Map BM25 score into 0.3..0.7 band.
                    val mapped = (0.3f + contentScore.coerceIn(0f, 1f) * 0.4f).coerceIn(0.3f, 0.7f)
                    signalStrengths.add(SIGNAL_CONTENT to mapped)
                }

                // 4. Embedding cosine similarity
                val embeddingScore = embeddingSimilarity(noteA.fileName, noteB.fileName, vectors)
                if (embeddingScore > 0f) {
                    signalStrengths.add(SIGNAL_SEMANTIC to embeddingScore.coerceIn(0f, 0.8f))
                }

                // 5. Shared concepts
                if (sharedSetSize(noteConceptSets, noteA.fileName, noteB.fileName) >= MIN_SHARED_CONCEPTS) {
                    signalStrengths.add(SIGNAL_CONCEPT to STRENGTH_SHARED_CONCEPTS)
                }

                // 6. Shared memory-layer entities
                if (sharedSetSize(noteEntityIds, noteA.fileName, noteB.fileName) >= MIN_SHARED_ENTITIES) {
                    signalStrengths.add(SIGNAL_ENTITY to STRENGTH_SHARED_ENTITIES)
                }

                // 7. Category match (weak)
                if (noteA.category == noteB.category &&
                    noteA.category != "Uncategorized" &&
                    noteA.category.isNotBlank()
                ) {
                    signalStrengths.add(SIGNAL_CATEGORY to STRENGTH_CATEGORY)
                }

                // 8. Temporal proximity (weak)
                val timeDiff = kotlin.math.abs(noteA.lastModifiedEpoch - noteB.lastModifiedEpoch)
                if (timeDiff in 1..TEMPORAL_WINDOW_MS) {
                    signalStrengths.add(SIGNAL_TEMPORAL to STRENGTH_TEMPORAL)
                }

                val combined = combineStrengths(signalStrengths) ?: continue
                if (combined < MIN_CONNECTION_STRENGTH) continue

                val relationship = if (signalStrengths.any { it.first == SIGNAL_CROSS_REF }) {
                    "references"
                } else {
                    "related"
                }
                val dominant = signalStrengths.maxByOrNull { it.second }!!.first
                val contributingSignals = signalStrengths.map { it.first }.distinct()

                noteConnections.add(NoteConnection(
                    sourceFileName = noteA.fileName,
                    targetFileName = noteB.fileName,
                    relationship = relationship,
                    strength = combined,
                    source = dominant,
                    signals = contributingSignals
                ))
            }

            // Cap per-note connections by strength
            connections.addAll(
                noteConnections
                    .distinctBy { it.targetFileName }
                    .sortedByDescending { it.strength }
                    .take(MAX_CONNECTIONS_PER_NOTE)
            )
        }

        // Deduplicate: keep the strongest connection between any pair
        val deduped = connections
            .groupBy { pairKey(it.sourceFileName, it.targetFileName) }
            .map { (_, group) -> group.maxByOrNull { it.strength }!! }

        val elapsed = System.currentTimeMillis() - start
        Log.i(TAG, "Graph computed: ${deduped.size} connections from ${notes.size} notes in ${elapsed}ms")

        return NoteGraph(
            connections = deduped,
            lastComputed = System.currentTimeMillis()
        )
    }

    /**
     * Compute content similarity between two notes using BM25.
     * Uses note A's content as a query against note B's content.
     */
    fun computeContentSimilarity(noteA: NoteFile, noteB: NoteFile): Float {
        // Use a small index to score note A against note B
        val miniIndex = NoteSearchIndex()
        miniIndex.rebuildIndex(listOf(noteB))
        val results = miniIndex.search(noteA.content.take(500), maxResults = 1)
        return results.firstOrNull()?.score ?: 0f
    }

    /**
     * Word-boundary cross-reference: does [text] mention [other]'s display title or raw filename?
     * Matches whole words/phrases only, so "app" won't match "happy" or "mapping".
     */
    private fun crossReferenceStrength(text: String, other: NoteFile): Float {
        val textLower = text.lowercase()
        val displayTitle = other.fileName.noteDisplayTitle().lowercase()
        val rawFileName = other.fileName.lowercase()
        // Also match the file stem without extension, e.g. "meeting_notes" or "meeting notes".
        val stem = rawFileName.removeSuffix(".json").removeSuffix(".txt").replace("_", " ")

        val candidates = listOf(displayTitle, rawFileName, stem)
            .filter { it.isNotBlank() }
            .distinct()

        for (candidate in candidates) {
            if (wordBoundaryMatch(textLower, candidate)) return STRENGTH_CROSS_REF
        }
        return 0f
    }

    /** True if [text] contains [phrase] as a whole word/phrase (word boundaries on both sides). */
    private fun wordBoundaryMatch(text: String, phrase: String): Boolean {
        if (phrase.isEmpty()) return false
        // Multi-word phrases match as-is; single tokens get \b word boundaries.
        val pattern = if (phrase.contains(" ")) {
            "\\b${Regex.escape(phrase)}\\b"
        } else {
            "(?<![a-z0-9])${Regex.escape(phrase)}(?![a-z0-9])"
        }
        return Regex(pattern).containsMatchIn(text)
    }

    /** Cosine similarity between two notes' embedding vectors, 0 if unavailable. */
    private fun embeddingSimilarity(
        fileNameA: String,
        fileNameB: String,
        vectors: Map<String, FloatArray?>
    ): Float {
        val vecA = vectors[fileNameA] ?: return 0f
        val vecB = vectors[fileNameB] ?: return 0f
        if (vecA.size != vecB.size || vecA.isEmpty()) return 0f
        val cosine = cosineSimilarity(vecA, vecB)
        return if (cosine >= EmbeddingIndex.MIN_COSINE_SIMILARITY) cosine else 0f
    }

    /** Size of the intersection between two notes' sets in the given map. */
    private fun sharedSetSize(
        map: Map<String, Set<String>>,
        fileNameA: String,
        fileNameB: String
    ): Int {
        if (map.isEmpty()) return 0
        val setA = map[fileNameA] ?: return 0
        val setB = map[fileNameB] ?: return 0
        return setA.intersect(setB).size
    }

    /**
     * Combine signal strengths: the max signal, plus a bonus for each additional
     * independent signal at or above [SIGNAL_AGREEMENT_FLOOR]. Returns null if no
     * signals fired.
     */
    private fun combineStrengths(signals: List<Pair<String, Float>>): Float? {
        if (signals.isEmpty()) return null
        val max = signals.maxOf { it.second }
        val agreementCount = signals.count { it.second >= SIGNAL_AGREEMENT_FLOOR } - 1
        val bonus = SIGNAL_AGREEMENT_BONUS * agreementCount.coerceAtLeast(0)
        return (max + bonus).coerceAtMost(1f)
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dotProduct = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator > 0f) dotProduct / denominator else 0f
    }

    private fun pairKey(a: String, b: String): String {
        return if (a < b) "$a|$b" else "$b|$a"
    }
}
