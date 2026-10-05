package com.noteflowai.app.data.concept

import com.noteflowai.app.data.search.NoteSearchIndex
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test: opening the "Fridge Photo Magnet Idea" note crashed the app
 * with IndexOutOfBoundsException ("No group 1") from
 * ConceptExtractor.extractTechnical (via NoteSuggestionEngine → analyzeCurrentNote).
 *
 * The technical patterns for camelCase / snake_case / qualified names define NO
 * capture group, but the code unconditionally read match.groups[1]. Any note
 * containing such a token (e.g. a multi-dot hostname inside a pasted URL)
 * crashed on open.
 */
class ConceptExtractorNoGroupCrashTest {

    private val extractor = ConceptExtractor(NoteSearchIndex())

    @Test
    fun `qualified hostname in pasted URL does not throw`() {
        val content = "Starting a fridge photo magnet business is low-capital. " +
            "See <img src=\"https://r2cdn.perplexity.ai/pplx-full-logo-primary-dark%402x.png\" /> " +
            "for the supplier logo. Pricing and margins are healthy after materials."
        val concepts = extractor.extractFromContent(content, "fridge.md")
        assertTrue(concepts.none { it.canonicalForm.isBlank() })
    }

    @Test
    fun `camelCase and snake_case tokens do not throw`() {
        val content = "Use the photoMagnet template with bleed settings. " +
            "Export fridge_magnet artwork at high resolution for crisp prints. " +
            "The badgePress workflow keeps unit costs low across production runs."
        val concepts = extractor.extractFromContent(content, "fridge.md")
        val technicals = concepts.filter { it.type == ConceptExtractor.ConceptType.TECHNICAL }
            .map { it.canonicalForm }
        assertTrue(technicals.contains("photomagnet"))
        assertTrue(technicals.contains("fridge_magnet"))
    }
}
