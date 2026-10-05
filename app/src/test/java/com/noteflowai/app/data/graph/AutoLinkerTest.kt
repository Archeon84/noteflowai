package com.noteflowai.app.data.graph

import com.noteflowai.app.data.NoteFile
import com.noteflowai.app.data.concept.ConceptExtractor
import com.noteflowai.app.data.concept.ConceptGraphRepository
import com.noteflowai.app.data.search.EmbeddingIndex
import com.noteflowai.app.data.search.NoteSearchIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for the multi-signal note linker.
 *
 * Robolectric stubs android.util.Log (AutoLinker logs) and provides a real
 * filesDir for ConceptGraphRepository.rebuildGraph persistence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoLinkerTest {

    private fun note(
        fileName: String,
        content: String,
        category: String = "Uncategorized",
        tags: List<String> = emptyList(),
        lastModifiedEpoch: Long = 0L
    ) = NoteFile(
        fileName = fileName,
        lastModified = "",
        preview = "",
        content = content,
        category = category,
        tags = tags,
        lastModifiedEpoch = lastModifiedEpoch
    )

    private fun connectionsInvolving(graph: NoteGraph, fileName: String): List<NoteConnection> =
        graph.connections.filter {
            it.sourceFileName == fileName || it.targetFileName == fileName
        }

    // ── Cross-reference: word-boundary matching ─────────────────────

    @Test
    fun `cross-ref rejects substring matches`() {
        // Note titled "app" must NOT link to a note whose text only contains
        // "app" as a substring of "happy"/"mapping".
        val a = note("tool.txt", "The happy mapping tool is fast")
        val b = note("app.txt", "grocery list milk bread cheese")
        val linker = AutoLinker(NoteSearchIndex())
        val graph = linker.computeGraph(listOf(a, b))
        assertEquals(0, graph.connections.size)
    }

    @Test
    fun `cross-ref links on whole-word title mention`() {
        val a = note("review.txt", "We discussed Meeting Notes for the quarterly review")
        val b = note("meeting_notes.txt", "unrelated filler body text here")
        val linker = AutoLinker(NoteSearchIndex())
        val graph = linker.computeGraph(listOf(a, b))
        assertEquals(1, graph.connections.size)
        val conn = graph.connections.single()
        assertTrue(conn.source == "auto_content")
        assertTrue(conn.relationship == "references")
        assertTrue(conn.strength >= 0.8f)
    }

    // ── BM25 content similarity ──────────────────────────────────────

    @Test
    fun `bm25 links topically similar notes`() {
        val a = note("one.txt", "the database migration strategy for the new backend service")
        val b = note("two.txt", "we are planning the database migration strategy for our backend service")
        val linker = AutoLinker(NoteSearchIndex())
        val graph = linker.computeGraph(listOf(a, b))
        assertEquals(1, graph.connections.size)
        assertTrue(graph.connections.single().signals.contains("auto_similar"))
        assertTrue(graph.connections.single().strength >= 0.4f)
    }

    // ── Embedding semantic similarity ───────────────────────────────

    @Test
    fun `embedding similarity creates semantic link`() {
        val index = EmbeddingIndex()
        index.putEmbedding("a.txt", 0L, floatArrayOf(1.0f, 0.0f, 0.0f))
        index.putEmbedding("b.txt", 0L, floatArrayOf(0.95f, 0.2f, 0.1f))

        val a = note("a.txt", "alpha topic content")
        val b = note("b.txt", "beta topic content")
        val linker = AutoLinker(NoteSearchIndex(), embeddingIndex = index)
        val graph = linker.computeGraph(listOf(a, b))
        assertEquals(1, graph.connections.size)
        assertTrue(graph.connections.single().signals.contains("auto_semantic"))
    }

    // ── Shared extracted concepts ────────────────────────────────────

    @Test
    fun `shared concepts contribute auto_concept signal`() {
        val searchIndex = NoteSearchIndex()
        val extractor = ConceptExtractor(searchIndex)
        val conceptRepo = ConceptGraphRepository(extractor)

        val a = note("a.txt", "Acme Corp and Globex Systems both operate here")
        val b = note("b.txt", "Acme Corp merged with Globex Systems last quarter")
        conceptRepo.rebuildGraph(listOf(a, b), RuntimeEnvironment.getApplication())

        val linker = AutoLinker(searchIndex, conceptGraphRepository = conceptRepo)
        val graph = linker.computeGraph(listOf(a, b))
        assertTrue(graph.connections.isNotEmpty())
        assertTrue(graph.connections.any { it.signals.contains("auto_concept") })
    }

    // ── Shared memory-layer entities ─────────────────────────────────

    @Test
    fun `shared entities create entity link`() {
        val a = note("a.txt", "quantum entanglement physics")
        val b = note("b.txt", "culinary recipes pasta sauce")
        val entityMap = mapOf(
            "a.txt" to setOf("e1", "e2", "e3"),
            "b.txt" to setOf("e2", "e3", "e4")
        )
        val linker = AutoLinker(NoteSearchIndex())
        val graph = linker.computeGraph(listOf(a, b), entityMap)
        assertEquals(1, graph.connections.size)
        val conn = graph.connections.single()
        assertTrue(conn.signals.contains("auto_entity"))
        assertTrue(conn.strength >= 0.4f)
    }

    // ── Precision floor ──────────────────────────────────────────────

    @Test
    fun `category-only pair is below precision floor`() {
        val a = note("a.txt", "quantum physics lab", category = "Work")
        val b = note("b.txt", "culinary recipes kitchen", category = "Work")
        val linker = AutoLinker(NoteSearchIndex())
        val graph = linker.computeGraph(listOf(a, b))
        assertEquals(0, graph.connections.size)
    }

    @Test
    fun `temporal-only pair is below precision floor`() {
        val now = System.currentTimeMillis()
        val a = note("a.txt", "quantum physics lab", lastModifiedEpoch = now - 5 * 60 * 60 * 1000L)
        val b = note("b.txt", "culinary recipes kitchen", lastModifiedEpoch = now)
        val linker = AutoLinker(NoteSearchIndex())
        val graph = linker.computeGraph(listOf(a, b))
        assertEquals(0, graph.connections.size)
    }

    // ── Cap & dedup ──────────────────────────────────────────────────

    @Test
    fun `dedup keeps one connection per pair`() {
        val a = note("a.txt", "quantum physics lab")
        val b = note("b.txt", "quantum physics workshop")
        val linker = AutoLinker(NoteSearchIndex())
        val graph = linker.computeGraph(listOf(a, b))
        val conns = graph.connections
        assertTrue(conns.size <= 1)
        // At most one connection in either direction.
        assertEquals(conns.size, graph.connections.map { pairKey(it) }.distinct().size)
    }

    @Test
    fun `connections are capped at 10 per note`() {
        val targets = (1..12).map { "t$it.txt" }
        val allNotes = listOf(note("a.txt", "root topic content")) +
            targets.map { note(it, "distinct filler body $it") }
        val entityMap = buildMap {
            put("a.txt", setOf("x1", "x2"))
            targets.forEach { put(it, setOf("x1", "x2", it)) }
        }
        val linker = AutoLinker(NoteSearchIndex())
        val graph = linker.computeGraph(allNotes, entityMap)
        // "a.txt" can have at most 10 outgoing connections.
        val aOutgoing = graph.connections.filter { it.sourceFileName == "a.txt" }
        assertTrue(aOutgoing.size <= 10)
    }

    // ── Diagnostic: unrelated notes sharing only a common word ────────

    @Test
    fun `unrelated notes sharing one common word are NOT connected`() {
        val a = note("pasta.txt", "dinner tonight pasta tomato sauce recipe")
        val b = note("garden.txt", "tomato plants gardening tips for the summer")
        val linker = AutoLinker(NoteSearchIndex())
        val graph = linker.computeGraph(listOf(a, b))
        val conns = graph.connections
        val detail = conns.joinToString { it.toString() }
        assertEquals("unexpected link(s): $detail", 0, conns.size)
    }

    private fun pairKey(conn: NoteConnection): String {
        val a = conn.sourceFileName
        val b = conn.targetFileName
        return if (a < b) "$a|$b" else "$b|$a"
    }
}
