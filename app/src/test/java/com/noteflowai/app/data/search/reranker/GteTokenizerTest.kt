package com.noteflowai.app.data.search.reranker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GteTokenizerTest {

    @Test
    fun testEncodePairStructureWithoutModelFile() {
        // Create a minimal synthetic tokenizer.json to test tokenizer parsing and pair creation
        val tempFile = File.createTempFile("gte_tok_test", ".json")
        tempFile.deleteOnExit()
        tempFile.writeText(
            """
            {
              "model": {
                "type": "Unigram",
                "vocab": [
                  ["<s>", 0.0],
                  ["<pad>", 0.0],
                  ["</s>", 0.0],
                  ["<unk>", 0.0],
                  ["\u2581hello", -1.0],
                  ["\u2581world", -1.2],
                  ["\u2581note", -0.8]
                ]
              }
            }
            """.trimIndent()
        )

        val tokenizer = GteTokenizer(tempFile)
        val (inputIds, attentionMask) = tokenizer.encodePair("hello", "world note", maxSeqLen = 512)

        // Must start with <s> (0)
        assertEquals(0L, inputIds[0])
        // Must contain two </s> in between: [0, ...Q..., 2, 2, ...D..., 2]
        assertEquals(2L, inputIds[2]) // </s> after Q
        assertEquals(2L, inputIds[3]) // </s> before D
        // Must end with </s> (2)
        assertEquals(2L, inputIds.last())

        // Attention mask must be all 1s for the sequence
        assertEquals(inputIds.size, attentionMask.size)
        assertTrue(attentionMask.all { it == 1L })
    }
}
