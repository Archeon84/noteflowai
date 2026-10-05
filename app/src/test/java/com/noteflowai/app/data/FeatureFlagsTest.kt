package com.noteflowai.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureFlagsTest {

    @Test
    fun `defaults follow the guide with cloudLlm enabled`() {
        val flags = FeatureFlags()
        assertTrue(flags.semanticSearch)
        assertTrue(flags.entityExtraction)
        assertTrue(flags.timelineExtraction)
        assertFalse(flags.webGrounding)
        assertFalse(flags.proactiveRecall)
        assertTrue(flags.localLlm)
        // User override: cloud LLM enabled by default (guide default was false).
        assertTrue(flags.cloudLlm)
    }

    @Test
    fun `from maps only the persisted-backed subset`() {
        val flags = FeatureFlags.from(
            semanticSearch = true,
            webGrounding = true,
            proactiveRecall = false
        )
        assertTrue(flags.semanticSearch)
        assertTrue(flags.webGrounding)
        assertFalse(flags.proactiveRecall)
        // Unmapped fields keep declared defaults.
        assertTrue(flags.entityExtraction)
        assertTrue(flags.timelineExtraction)
        assertTrue(flags.localLlm)
        assertTrue(flags.cloudLlm)
    }
}
