package com.noteflowai.app.data.search

import org.junit.Assert.assertEquals
import org.junit.Test

class PromptAssemblerUCurveTest {

    private val assembler = PromptAssembler()

    @Test
    fun `orderUCurve returns same list when size is 2 or less`() {
        assertEquals(emptyList<Int>(), assembler.orderUCurve(emptyList<Int>()))
        assertEquals(listOf("A"), assembler.orderUCurve(listOf("A")))
        assertEquals(listOf("A", "B"), assembler.orderUCurve(listOf("A", "B")))
    }

    @Test
    fun `orderUCurve places rank 1 at start and rank 2 at end for 3 items`() {
        val input = listOf("Rank1", "Rank2", "Rank3")
        val result = assembler.orderUCurve(input)
        // Evens: Rank1, Rank3; Odds reversed: Rank2 -> [Rank1, Rank3, Rank2]
        assertEquals(listOf("Rank1", "Rank3", "Rank2"), result)
    }

    @Test
    fun `orderUCurve places top items at extremities for 4 items`() {
        val input = listOf("R1", "R2", "R3", "R4")
        val result = assembler.orderUCurve(input)
        // Evens: R1, R3; Odds reversed: R4, R2 -> [R1, R3, R4, R2]
        assertEquals(listOf("R1", "R3", "R4", "R2"), result)
    }

    @Test
    fun `orderUCurve places top items at extremities for 5 items`() {
        val input = listOf("R1", "R2", "R3", "R4", "R5")
        val result = assembler.orderUCurve(input)
        // Evens: R1, R3, R5; Odds reversed: R4, R2 -> [R1, R3, R5, R4, R2]
        assertEquals(listOf("R1", "R3", "R5", "R4", "R2"), result)
    }
}
