package com.noteflowai.app.data.search.reranker

import android.util.Log
import com.google.gson.stream.JsonReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.LinkedHashMap
import kotlin.math.min

/**
 * High-performance SentencePiece / Unigram tokenizer for Alibaba GTE Multilingual Reranker.
 * Parses the vocabulary and merge scores from `tokenizer.json` and builds cross-attention
 * sequence pairs: `<s> query </s></s> document </s>`.
 */
class GteTokenizer(tokenizerFile: File) {

    companion object {
        private const val TAG = "GteTokenizer"
        const val BOS_ID = 0L // <s>
        const val PAD_ID = 1L // <pad>
        const val EOS_ID = 2L // </s>
        const val UNK_ID = 3L // <unk>
        private const val METASPACE = "\u2581"
        private const val MAX_WORD_PIECE_LEN = 24
    }

    private val pieceToId = HashMap<String, Long>(260000)
    private val pieceToScore = HashMap<String, Float>(260000)

    // LRU cache for word tokenization results
    private val wordCache = object : LinkedHashMap<String, LongArray>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LongArray>?): Boolean {
            return size > 2000
        }
    }

    init {
        loadVocab(tokenizerFile)
    }

    private fun loadVocab(file: File) {
        val startMs = System.currentTimeMillis()
        try {
            JsonReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == "model") {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            if (reader.nextName() == "vocab") {
                                reader.beginArray()
                                var idx = 0L
                                while (reader.hasNext()) {
                                    reader.beginArray()
                                    val piece = reader.nextString()
                                    val score = reader.nextDouble().toFloat()
                                    reader.endArray()
                                    pieceToId[piece] = idx
                                    pieceToScore[piece] = score
                                    idx++
                                }
                                reader.endArray()
                            } else {
                                reader.skipValue()
                            }
                        }
                        reader.endObject()
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endObject()
            }
            Log.i(TAG, "Loaded ${pieceToId.size} vocabulary pieces in ${System.currentTimeMillis() - startMs}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load tokenizer from ${file.absolutePath}: ${e.message}", e)
            throw e
        }
    }

    /**
     * Tokenizes a text string into an array of token IDs using SentencePiece Unigram Viterbi.
     */
    fun encode(text: String): LongArray {
        if (text.isBlank()) return LongArray(0)

        // Split text by whitespace, preserving word boundaries
        val words = text.trim().split(Regex("\\s+"))
        val result = mutableListOf<Long>()

        for ((index, rawWord) in words.withIndex()) {
            if (rawWord.isEmpty()) continue
            val wordWithMeta = METASPACE + rawWord
            val tokenized = tokenizeWord(wordWithMeta)
            for (t in tokenized) {
                result.add(t)
            }
        }
        return result.toLongArray()
    }

    private fun tokenizeWord(word: String): LongArray {
        synchronized(wordCache) {
            val cached = wordCache[word]
            if (cached != null) return cached
        }

        // Exact match check first
        val directId = pieceToId[word]
        if (directId != null) {
            val res = longArrayOf(directId)
            synchronized(wordCache) { wordCache[word] = res }
            return res
        }

        val len = word.length
        val dp = FloatArray(len + 1) { Float.NEGATIVE_INFINITY }
        val prev = IntArray(len + 1) { -1 }
        dp[0] = 0.0f

        for (i in 0 until len) {
            if (dp[i] == Float.NEGATIVE_INFINITY) continue
            val maxSubLen = min(len - i, MAX_WORD_PIECE_LEN)
            for (l in 1..maxSubLen) {
                val sub = word.substring(i, i + l)
                val score = pieceToScore[sub]
                if (score != null) {
                    val candidate = dp[i] + score
                    if (candidate > dp[i + l]) {
                        dp[i + l] = candidate
                        prev[i + l] = i
                    }
                }
            }
        }

        // Backtrack
        val tokens = mutableListOf<Long>()
        if (dp[len] != Float.NEGATIVE_INFINITY) {
            var curr = len
            while (curr > 0) {
                val p = prev[curr]
                val sub = word.substring(p, curr)
                val id = pieceToId[sub] ?: UNK_ID
                tokens.add(0, id)
                curr = p
            }
        } else {
            // Unigram failed to parse entire sequence (unrecognized character); fall back to char-by-char
            for (c in word) {
                val charStr = c.toString()
                tokens.add(pieceToId[charStr] ?: UNK_ID)
            }
        }

        val res = tokens.toLongArray()
        synchronized(wordCache) { wordCache[word] = res }
        return res
    }

    /**
     * Builds cross-encoder input for (query, document) pair:
     * Format: `<s> query </s></s> document </s>`
     */
    fun encodePair(
        query: String,
        document: String,
        maxSeqLen: Int = 512
    ): Pair<LongArray, LongArray> {
        val qTokens = encode(query)
        val dTokens = encode(document)

        // Reserve 4 slots for special tokens: <s>, </s>, </s>, </s>
        val maxBudget = maxSeqLen - 4
        val qBudget = min(qTokens.size, 128)
        val dBudget = min(dTokens.size, maxBudget - qBudget)

        val totalLen = 1 + qBudget + 2 + dBudget + 1
        val inputIds = LongArray(totalLen)
        val attentionMask = LongArray(totalLen) { 1L }

        var idx = 0
        inputIds[idx++] = BOS_ID // <s>
        for (i in 0 until qBudget) {
            inputIds[idx++] = qTokens[i]
        }
        inputIds[idx++] = EOS_ID // </s>
        inputIds[idx++] = EOS_ID // </s>
        for (i in 0 until dBudget) {
            inputIds[idx++] = dTokens[i]
        }
        inputIds[idx++] = EOS_ID // </s>

        return Pair(inputIds, attentionMask)
    }
}
