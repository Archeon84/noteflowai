package com.noteflowai.app.data.memory.dao

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 0 safety: SQLite LIKE wildcards in bound parameters must be escaped
 * (paired with the `ESCAPE '\'` clause in the DAO queries). A raw `%` matches
 * every row (recall explosion into source chips); `_` false-positives; and
 * system-generated segment IDs contain `_`, so even the conflict cleanup
 * DELETE could over-match siblings.
 */
class LikeEscapeTest {

    @Test
    fun `percent underscore and backslash are escaped`() {
        assertEquals("100\\% coverage", escapeLike("100% coverage"))
        assertEquals("budget\\_2026", escapeLike("budget_2026"))
        assertEquals("a\\\\b", escapeLike("a\\b"))
    }

    @Test
    fun `system segment ids are escaped`() {
        assertEquals(
            "note\\_report.md\\_block0\\_550e8400",
            escapeLike("note_report.md_block0_550e8400")
        )
    }

    @Test
    fun `case is preserved (folding is the search caller's choice, never the delete's)`() {
        assertEquals("Project Athena", escapeLike("Project Athena"))
    }

    @Test
    fun `plain text passes through`() {
        assertEquals("coping strategies for focus", escapeLike("coping strategies for focus"))
    }
}
