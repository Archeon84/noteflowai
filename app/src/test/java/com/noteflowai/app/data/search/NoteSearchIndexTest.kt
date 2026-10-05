package com.noteflowai.app.data.search

import com.noteflowai.app.data.NoteFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the BM25 NoteSearchIndex (personal memory layer, RAG v2).
 *
 * Robolectric stubs android.util.Log so rebuildIndex()/search() logging
 * paths can run on the host JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteSearchIndexTest {

    private lateinit var index: NoteSearchIndex

    private fun note(
        fileName: String,
        content: String,
        preview: String = "",
        category: String = "Uncategorized",
        tags: List<String> = emptyList(),
        lastModifiedEpoch: Long = 0L
    ) = NoteFile(
        fileName = fileName,
        lastModified = "",
        preview = preview,
        content = content,
        category = category,
        tags = tags,
        lastModifiedEpoch = lastModifiedEpoch
    )

    @Before
    fun setUp() {
        index = NoteSearchIndex()
    }

    // ── Tokenization ─────────────────────────────────────────────────

    @Test
    fun `tokenize lowercases and strips punctuation`() {
        val tokens = index.tokenize("Hello, World! Foo-Bar.")
        assertTrue("hello" in tokens)
        // "world" is a stop word and is filtered out
        assertTrue("foo" in tokens)
        assertTrue("bar" in tokens)
        // No punctuation-only tokens survive
        assertTrue(tokens.none { it.isEmpty() })
    }

    @Test
    fun `tokenize removes stop words and single chars`() {
        val tokens = index.tokenize("the a and on quick fox")
        assertEquals(listOf("quick", "fox"), tokens)
    }

    @Test
    fun `tokenize caps token length at 30 chars`() {
        val longWord = "x".repeat(50)
        val tokens = index.tokenize(longWord)
        assertEquals(1, tokens.size)
        assertTrue(tokens[0].length <= 30)
    }

    // ── Index building ────────────────────────────────────────────────

    @Test
    fun `rebuildIndex with empty notes yields empty index`() {
        index.rebuildIndex(emptyList())
        assertTrue(index.isEmpty())
        assertEquals(0, index.indexSize())
    }

    @Test
    fun `rebuildIndex indexes all notes`() {
        index.rebuildIndex(listOf(note("a.txt", "content"), note("b.txt", "more")))
        assertEquals(2, index.indexSize())
    }

    // ── Search ────────────────────────────────────────────────────────

    @Test
    fun `search returns matching note`() {
        index.rebuildIndex(
            listOf(
                note("meeting.txt", "We discussed the migration strategy for the database"),
                note("grocery.txt", "Milk eggs bread cheese")
            )
        )
        val results = index.search("migration strategy")
        assertEquals(1, results.size)
        assertEquals("meeting.txt", results[0].fileName)
    }

    @Test
    fun `search is empty when query has no matches`() {
        index.rebuildIndex(
            listOf(
                note("meeting.txt", "We discussed the migration strategy"),
                note("grocery.txt", "Milk eggs bread")
            )
        )
        val results = index.search("zebra unicorn")
        assertEquals(0, results.size)
    }

    @Test
    fun `search on empty index returns empty`() {
        index.rebuildIndex(emptyList())
        assertEquals(0, index.search("anything").size)
    }

    @Test
    fun `search respects maxResults`() {
        index.rebuildIndex(
            listOf(
                note("one.txt", "migration plan alpha"),
                note("two.txt", "migration plan beta"),
                note("three.txt", "migration plan gamma")
            )
        )
        val results = index.search("migration", maxResults = 2)
        assertEquals(2, results.size)
    }

    @Test
    fun `results are sorted by descending score`() {
        index.rebuildIndex(
            listOf(
                note("full.txt", "database migration strategy migration database migration"),
                note("brief.txt", "database")
            )
        )
        val results = index.search("database", maxResults = 5)
        assertTrue(results.size >= 2)
        // Higher frequency doc ranks first
        assertTrue(results[0].score >= results[1].score)
    }

    // ── Field boosting ────────────────────────────────────────────────

    @Test
    fun `title match ranks above body-only match`() {
        index.rebuildIndex(
            listOf(
                note("migration_notes.txt", "random unrelated body text"),
                note("other.txt", "the word migration appears deep in the body here")
            )
        )
        // fileName "migration_notes.txt" tokenizes to ["migration", "notes"]
        val results = index.search("migration", maxResults = 5)
        assertTrue(results.isNotEmpty())
        assertEquals("migration_notes.txt", results[0].fileName)
    }

    @Test
    fun `excerpt contains the matched term`() {
        index.rebuildIndex(
            listOf(
                note("meeting.txt", "We discussed the database migration and the rollback plan in detail today.")
            )
        )
        val results = index.search("rollback")
        assertEquals(1, results.size)
        assertTrue(results[0].excerpt.lowercase().contains("rollback"))
    }

    // ── Fingerprint / rebuild skip ────────────────────────────────────

    @Test
    fun `identical rebuild does not change fingerprint`() {
        val notes = listOf(note("a.txt", "content here"))
        index.rebuildIndex(notes)
        val fp1 = index.getCurrentFingerprint()
        index.rebuildIndex(notes)
        val fp2 = index.getCurrentFingerprint()
        assertEquals(fp1, fp2)
    }

    @Test
    fun `changed content changes fingerprint`() {
        index.rebuildIndex(listOf(note("a.txt", "content one")))
        val fp1 = index.getCurrentFingerprint()
        index.rebuildIndex(listOf(note("a.txt", "content two")))
        val fp2 = index.getCurrentFingerprint()
        assertTrue(fp1 != fp2)
    }

    @Test
    fun `long note content beyond 4000 characters is indexed and searchable`() {
        val filler = "padding text without special keywords. ".repeat(200) // ~8000 chars
        val secretWord = "quantumentanglement"
        val longContent = filler + " The final breakthrough was $secretWord discovered here. " + filler
        assertTrue(longContent.indexOf(secretWord) > 4000)

        index.rebuildIndex(
            listOf(
                note("research_paper.txt", longContent)
            )
        )

        val results = index.search("quantumentanglement")
        assertEquals(1, results.size)
        assertEquals("research_paper.txt", results[0].fileName)
        assertTrue(results[0].excerpt.contains("quantumentanglement"))
    }
}
