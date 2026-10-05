package com.noteflowai.app.data.search

import com.noteflowai.app.data.memory.model.MemoryType
import com.noteflowai.app.data.memory.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the regex-based QueryParser (personal memory layer, Phase 4).
 *
 * QueryParser is pure JVM (no Android deps), so these run on the host JVM.
 */
class QueryParserTest {

    private val parser = QueryParser()

    // ── Intent detection ────────────────────────────────────────────────

    @Test
    fun `decision query maps to DECISION_LOOKUP`() {
        val plan = parser.parse("What decision did I make about the project last week?")
        assertEquals(QueryIntent.DECISION_LOOKUP, plan.intent)
    }

    @Test
    fun `commitment query maps to COMMITMENT_LOOKUP`() {
        val plan = parser.parse("What commitments are overdue?")
        assertEquals(QueryIntent.COMMITMENT_LOOKUP, plan.intent)
    }

    @Test
    fun `conflict query maps to CONFLICT_ANALYSIS`() {
        val plan = parser.parse("Are there contradictions in my notes?")
        assertEquals(QueryIntent.CONFLICT_ANALYSIS, plan.intent)
    }

    @Test
    fun `change query maps to CHANGE_ANALYSIS`() {
        val plan = parser.parse("How has my thinking about pricing changed over time?")
        assertEquals(QueryIntent.CHANGE_ANALYSIS, plan.intent)
    }

    @Test
    fun `entity query maps to ENTITY_LOOKUP`() {
        val plan = parser.parse("What do I know about Alice?")
        assertEquals(QueryIntent.ENTITY_LOOKUP, plan.intent)
    }

    @Test
    fun `project query maps to PROJECT_LOOKUP`() {
        val plan = parser.parse("What is the status of Project Apollo?")
        assertEquals(QueryIntent.PROJECT_LOOKUP, plan.intent)
    }

    @Test
    fun `create note query maps to CREATE_NOTE`() {
        val plan = parser.parse("Create a note about the meeting")
        assertEquals(QueryIntent.CREATE_NOTE, plan.intent)
    }

    @Test
    fun `create task query maps to CREATE_TASK`() {
        val plan = parser.parse("Add a task to call the vendor")
        assertEquals(QueryIntent.CREATE_TASK, plan.intent)
    }

    @Test
    fun `create decision query maps to CREATE_DECISION`() {
        val plan = parser.parse("Record a decision about the database")
        assertEquals(QueryIntent.CREATE_DECISION, plan.intent)
    }

    @Test
    fun `summary query maps to SOURCE_SUMMARY`() {
        val plan = parser.parse("Summarize the meeting notes")
        assertEquals(QueryIntent.SOURCE_SUMMARY, plan.intent)
    }

    @Test
    fun `generic auxiliaries do not hijack into COMMITMENT_LOOKUP`() {
        // "must/should/need to/going to" used to route any such query into a
        // commitment lookup that ignored the query text.
        assertEquals(QueryIntent.GENERAL_RAG, parser.parse("I must finish this report today").intent)
        assertEquals(QueryIntent.GENERAL_RAG, parser.parse("We should discuss Room migrations").intent)
        assertEquals(QueryIntent.GENERAL_RAG, parser.parse("I need to understand Room migrations").intent)
        assertEquals(QueryIntent.GENERAL_RAG, parser.parse("Going to the doctor tomorrow").intent)
    }

    @Test
    fun `source type keywords match whole words only`() {
        // "doc" in "doctor" and "note" in "noted" must not fire.
        assertTrue(parser.parse("Going to the doctor tomorrow").sourceTypes.isEmpty())
        assertEquals(listOf(SourceType.NOTE), parser.parse("find it in my notes").sourceTypes)
        assertEquals(listOf(SourceType.DOCUMENT), parser.parse("search my docs for it").sourceTypes)
    }

    @Test
    fun `unicode entities are extracted`() {
        val plan = parser.parse("What did Müller decide about the Projekt?")
        assertTrue(plan.entities.any { it.contains("Müller") })
    }

    @Test
    fun `unrecognized query falls back to GENERAL_RAG`() {
        val plan = parser.parse("What is the weather like?")
        assertEquals(QueryIntent.GENERAL_RAG, plan.intent)
    }

    // ── Date extraction ─────────────────────────────────────────────────

    @Test
    fun `explicit date range is parsed`() {
        val plan = parser.parse("decisions between 2026-07-01 and 2026-07-31")
        assertNotNull(plan.dateFrom)
        assertNotNull(plan.dateTo)
        // Start of day in the DEVICE zone (not UTC midnight): computed the
        // same way the parser does so the test is zone-independent.
        val zone = java.time.ZoneId.systemDefault()
        val expectedFrom = java.time.LocalDate.of(2026, 7, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(expectedFrom, plan.dateFrom)
        // Parser adds one day to the max date for an end-of-day-inclusive range.
        val expectedTo = java.time.LocalDate.of(2026, 7, 31).atStartOfDay(zone).toInstant().toEpochMilli() + 86400000L
        assertEquals(expectedTo, plan.dateTo)
    }

    @Test
    fun `relative last N days is parsed`() {
        val plan = parser.parse("commitments from last 7 days")
        assertNotNull(plan.dateFrom)
        assertNotNull(plan.dateTo)
        // dateTo should be today + 1 day (end of day), device zone.
        val today = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(today + 86400000L, plan.dateTo)
    }

    @Test
    fun `no date in query yields null range`() {
        val plan = parser.parse("what did I decide")
        assertEquals(null, plan.dateFrom)
        assertEquals(null, plan.dateTo)
    }

    // ── Entity extraction ───────────────────────────────────────────────

    @Test
    fun `possessive entity is extracted`() {
        val plan = parser.parse("Alice's decisions")
        assertTrue(plan.entities.contains("Alice"))
    }

    @Test
    fun `quoted entity is extracted`() {
        val plan = parser.parse("notes about \"Project Zenith\"")
        assertTrue(plan.entities.contains("Project Zenith"))
    }

    @Test
    fun `entity list is capped at 5`() {
        val plan = parser.parse("Alpha Beta Gamma Delta Epsilon Zeta decisions")
        assertTrue(plan.entities.size <= 5)
    }

    // ── Source type extraction ──────────────────────────────────────────

    @Test
    fun `audio keywords map to AUDIO`() {
        val plan = parser.parse("decisions from my recordings")
        assertTrue(plan.sourceTypes.contains(SourceType.AUDIO))
    }

    @Test
    fun `pdf and youtube keywords map correctly`() {
        val plan = parser.parse("notes from the PDF and the YouTube video")
        assertTrue(plan.sourceTypes.contains(SourceType.PDF))
        assertTrue(plan.sourceTypes.contains(SourceType.YOUTUBE))
    }

    // ── Memory type mapping ─────────────────────────────────────────────

    @Test
    fun `decision lookup maps to DECISION memory type`() {
        val plan = parser.parse("what decisions did I make")
        assertEquals(listOf(MemoryType.DECISION), plan.memoryTypes)
    }

    @Test
    fun `commitment lookup maps to COMMITMENT memory type`() {
        val plan = parser.parse("what did I promise")
        assertEquals(listOf(MemoryType.COMMITMENT), plan.memoryTypes)
    }

    // ── Flags ───────────────────────────────────────────────────────────

    @Test
    fun `unconfirmed keyword sets includeUnconfirmed`() {
        val plan = parser.parse("show unconfirmed commitments")
        assertTrue(plan.includeUnconfirmed)
    }

    @Test
    fun `timeline keyword sets requiresTimeline`() {
        val plan = parser.parse("timeline of my decisions about pricing")
        assertTrue(plan.requiresTimeline)
    }

    @Test
    fun `comparison keyword sets requiresComparison`() {
        val plan = parser.parse("compare my plan versus the latest plan")
        assertTrue(plan.requiresComparison)
    }

    @Test
    fun `change analysis intent implies timeline`() {
        val plan = parser.parse("how has my opinion changed about X")
        assertEquals(QueryIntent.CHANGE_ANALYSIS, plan.intent)
        assertTrue(plan.requiresTimeline)
    }

    @Test
    fun `query text is preserved verbatim`() {
        val q = "What did I decide about the migration?"
        val plan = parser.parse(q)
        assertEquals(q, plan.queryText)
    }

    @Test
    fun `empty query does not crash`() {
        val plan = parser.parse("")
        assertEquals(QueryIntent.GENERAL_RAG, plan.intent)
        assertFalse(plan.requiresTimeline)
    }

    @Test
    fun `comprehensive query keywords set isComprehensive flag`() {
        val plan1 = parser.parse("list all items in my note")
        assertTrue(plan1.isComprehensive)

        val plan2 = parser.parse("what are all the dates in my schedule?")
        assertTrue(plan2.isComprehensive)

        val plan3 = parser.parse("give me every decision from the project")
        assertTrue(plan3.isComprehensive)

        val plan4 = parser.parse("show entire note for trip planning")
        assertTrue(plan4.isComprehensive)

        val plan5 = parser.parse("what is the weather like?")
        assertFalse(plan5.isComprehensive)
    }
}
