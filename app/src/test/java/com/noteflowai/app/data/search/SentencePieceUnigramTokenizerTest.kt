package com.noteflowai.app.data.search

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Validates SentencePieceUnigramTokenizer against golden token sequences
 * produced by the HuggingFace `tokenizers` library (v0.22.2) using the real
 * paraphrase-multilingual-MiniLM-L12-v2 tokenizer.json.
 *
 * The mini vocab in test resources is a subset of the real 250k vocab (the
 * pieces reachable from the test corpus's pre-tokens, preserving real ids and
 * scores) so the Viterbi competition is identical while keeping the fixture
 * small.
 */
class SentencePieceUnigramTokenizerTest {

    private lateinit var tokenizer: SentencePieceUnigramTokenizer

    @Before
    fun setUp() {
        val stream = javaClass.classLoader
            ?.getResourceAsStream("com/noteflowai/app/data/search/mini_tokens.txt")
            ?: error("mini_tokens.txt not found")
        val pieces = ArrayList<String>()
        val scores = ArrayList<Float>()
        val ids = ArrayList<Int>()
        stream.bufferedReader().forEachLine { line ->
            val parts = line.split('\t')
            if (parts.size >= 3) {
                ids.add(parts[0].toInt())
                pieces.add(parts[1])
                scores.add(parts[2].toFloat())
            }
        }
        tokenizer = SentencePieceUnigramTokenizer(
            vocabPieces = pieces.toTypedArray(),
            vocabScores = scores.toFloatArray(),
            unkId = 3,
            pieceIds = ids.toIntArray()
        )
    }

    private fun assertTokens(text: String, expected: IntArray) {
        assertArrayEquals(
            "token ids for: $text",
            expected,
            tokenizer.encode(text)
        )
    }

    @Test
    fun encodesEnglishPunctuation() {
        assertTokens(
            "Hello, World! How are you?",
            intArrayOf(0, 35378, 4, 6661, 38, 11249, 621, 398, 32, 2)
        )
    }

    @Test
    fun preservesCase() {
        assertTokens(
            "hello, world! how are you?",
            intArrayOf(0, 33600, 31, 4, 8999, 38, 3642, 621, 398, 32, 2)
        )
    }

    @Test
    fun encodesNumbersAndAcronyms() {
        assertTokens(
            "The RTX 3090 with 24GB VRAM",
            intArrayOf(0, 581, 627, 54047, 496, 5039, 678, 744, 8359, 310, 35812, 2)
        )
    }

    @Test
    fun encodesChinese() {
        assertTokens(
            "我买了一台新电脑用来跑本地AI模型",
            intArrayOf(0, 13129, 138554, 104538, 1378, 45974, 140278, 11659, 65992, 11388, 71277, 2)
        )
    }

    @Test
    fun encodesMalay() {
        assertTokens(
            "Saya perlu membeli beras dan minyak",
            intArrayOf(0, 7775, 5459, 26831, 74259, 123, 32652, 2)
        )
    }

    @Test
    fun collapsesWhitespaceRuns() {
        assertTokens(
            "  Multiple   spaces   here  ",
            intArrayOf(0, 19335, 8705, 32628, 7, 3688, 2)
        )
    }

    @Test
    fun handlesHyphenatedWords() {
        assertTokens(
            "New noise-cancelling headphones",
            intArrayOf(0, 2356, 110, 3075, 9, 4398, 34680, 214, 10336, 26551, 7, 2)
        )
    }

    @Test
    fun handlesApostrophesDatesAndTimes() {
        assertTokens(
            "today's 2026-08-15 meeting at 3:30pm",
            intArrayOf(0, 18925, 25, 7, 387, 4046, 47355, 1837, 41714, 99, 22408, 1197, 26822, 2)
        )
    }

    @Test
    fun encodesCjkAndChinese() {
        assertTokens(
            "项目会议推迟到下周",
            intArrayOf(0, 6, 6806, 15108, 10238, 85564, 789, 1130, 6271, 2)
        )
    }

    @Test
    fun normalizesNfkcFractions() {
        assertTokens(
            "Recipe: 2 cups flour, ½ tsp salt, ¼ cup sugar",
            intArrayOf(0, 182378, 12, 116, 45364, 7, 21917, 474, 4, 78660, 808, 7008, 28279, 4, 6, 121346, 45364, 101087, 2)
        )
    }

    @Test
    fun encodesEmojiPieces() {
        assertTokens(
            "Emoji time: 😀🎉🚀 and more 📝",
            intArrayOf(0, 241, 121505, 1733, 12, 21119, 243816, 247206, 136, 1286, 6, 246136, 2)
        )
    }

    @Test
    fun splitsZwjEmojiFamily() {
        assertTokens(
            "Family 👨‍👩‍👧‍👦 picnic planned",
            intArrayOf(0, 59745, 6, 244314, 6, 244785, 6, 245719, 6, 246167, 8834, 6402, 203251, 2)
        )
    }

    @Test
    fun encodesCommonSentence() {
        assertTokens(
            "The quick brown fox jumps over the lazy dog",
            intArrayOf(0, 581, 63773, 119455, 6, 147797, 88203, 7, 645, 70, 21, 3285, 10269, 2)
        )
    }

    @Test
    fun treatsNumberWordsAsMetaspacePrefixed() {
        assertTokens(
            "w0001 w0002 w0003",
            intArrayOf(0, 148, 188735, 148, 9508, 304, 148, 9508, 363, 2)
        )
    }

    @Test
    fun vocabSizeReflectsFixture() {
        assertEquals(589, tokenizer.vocabSize)
    }
}
