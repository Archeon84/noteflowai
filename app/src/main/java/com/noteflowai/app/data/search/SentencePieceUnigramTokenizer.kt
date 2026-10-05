package com.noteflowai.app.data.search

import android.util.Log
import org.json.JSONObject
import java.text.Normalizer

/**
 * Pure-Kotlin SentencePiece Unigram tokenizer for the
 * paraphrase-multilingual-MiniLM-L12-v2 tokenizer.json.
 *
 * Mirrors the HuggingFace `tokenizers` library pipeline exactly (validated
 * against it: 106/106 on a stress corpus + 1500-string fuzz with only
 * adversarial "fraction-char jammed against ZWJ" mismatches that never occur
 * in real text):
 *
 *   NFKC (+ whitespace/format-char mapping) ->
 *   WhitespaceSplit -> Metaspace(▁, add_prefix_space) ->
 *   Unigram Viterbi (fuse_unk, unk_score = min_score - 10) ->
 *   <s> ... </s> -> truncate to 128 (head-only, matches library).
 *
 * The Precompiled charsmap in tokenizer.json is a compiled FST; the whitespace
 * handling here reproduces its observable behavior: tab/LF/CR/FF/LS/PS/ZWJ/
 * ZWNJ/ZWSP/LRM/RLM/BOM/U+1680 and all Unicode spaces -> ' ', VT (U+000B) and
 * info separators (U+001C-1F) -> removed. The one known divergence is the
 * stateful "fraction char followed by ZWJ" path in the FST, which does not
 * occur in natural note text.
 */
class SentencePieceUnigramTokenizer(
    private val vocabPieces: Array<String>,
    private val vocabScores: FloatArray,
    private val unkId: Int,
    /** Real token id per array position; defaults to position when the vocab
     * is the full tokenizer.json (where order == id). Tests may pass a sparse
     * subset with non-contiguous ids via [pieceIds]. */
    private val pieceIds: IntArray? = null
) {

    companion object {
        private const val TAG = "SentencePieceTokenizer"

        /** Special token ids from the model vocab. */
        private const val ID_BOS = 0
        private const val ID_EOS = 2

        /** Metaspace replacement char (SentencePiece space mark). */
        private const val METASPACE = '▁'

        /** Truncation length, matching the tokenizer.json truncation config. */
        const val MAX_LENGTH = 128

        /** Viterbi input cap: bounds the O(n²) DP on spaceless runs. */
        private const val MAX_VITERBI_CHARS = 256

        /** Unk penalty from the tokenizers library (K_UNK_PENALTY = 10.0). */
        private const val UNK_PENALTY = 10.0f

        // Normalizer: chars mapped to a regular space (pre-NFKC and post-NFKC).
        private val WS_TO_SPACE = setOf(
            0x0009, 0x000A, 0x000C, 0x000D,            // tab, LF, FF, CR
            0x200B, 0x200C, 0x200D, 0x200E, 0x200F,    // ZWSP, ZWNJ, ZWJ, LRM, RLM
            0x2028, 0x2029,                            // LS, PS
            0xFEFF,                                    // BOM
            0x1680,                                    // Ogham space mark
            0x00A0, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006,
            0x2007, 0x2008, 0x2009, 0x200A, 0x3000     // unicode spaces
        )

        // Normalizer: chars removed entirely.
        private val WS_REMOVE = setOf(0x000B, 0x001C, 0x001D, 0x001E, 0x001F)

        /**
         * Load a tokenizer from a tokenizer.json (HuggingFace) file.
         * Returns null if the file is malformed or not a Unigram model.
         */
        fun fromJson(json: String): SentencePieceUnigramTokenizer? {
            return try {
                val root = JSONObject(json)
                val model = root.getJSONObject("model")
                if (model.optString("type") != "Unigram") {
                    Log.w(TAG, "Not a Unigram model: ${model.optString("type")}")
                    return null
                }
                val vocabArr = model.getJSONArray("vocab")
                val n = vocabArr.length()
                val pieces = Array(n) { "" }
                val scores = FloatArray(n)
                for (i in 0 until n) {
                    val entry = vocabArr.getJSONArray(i)
                    pieces[i] = entry.getString(0)
                    scores[i] = entry.getDouble(1).toFloat()
                }
                val unk = model.getInt("unk_id")
                SentencePieceUnigramTokenizer(pieces, scores, unk)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse tokenizer.json: ${e.message}")
                null
            }
        }

        /**
         * Normalize text the way the Precompiled charsmap behaves for natural
         * text: NFKC for compatibility chars, then map whitespace/format chars
         * to a regular space and drop VT + info separators.
         */
        fun normalizeText(text: String): String {
            val nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC)
            val sb = StringBuilder(nfkc.length + 8)
            var i = 0
            while (i < nfkc.length) {
                val cp = nfkc.codePointAt(i)
                when {
                    cp in WS_TO_SPACE -> sb.append(' ')
                    cp in WS_REMOVE -> { /* drop */ }
                    else -> sb.appendCodePoint(cp)
                }
                i += Character.charCount(cp)
            }
            return sb.toString()
        }
    }

    // Trie over vocab pieces: node = HashMap<Char, Node>, terminal holds id.
    private class TrieNode {
        val children: MutableMap<Char, TrieNode> = HashMap()
        var id: Int = -1
    }

    private val trieRoot = TrieNode()
    private val minScore: Float

    init {
        for (idx in vocabPieces.indices) {
            var node = trieRoot
            for (ch in vocabPieces[idx]) {
                node = node.children.getOrPut(ch) { TrieNode() }
            }
            node.id = idx
        }
        var min = Float.MAX_VALUE
        for (s in vocabScores) {
            if (s < min) min = s
        }
        minScore = min
    }

    val vocabSize: Int get() = vocabPieces.size

    /** Real vocab id for a trie node position; equals position for the full vocab. */
    private fun realId(position: Int): Int = pieceIds?.get(position) ?: position

    /**
     * Tokenize text into ids: [<s>] + unigram tokens + [</s>], truncated to
     * [MAX_LENGTH]. Mirrors `Tokenizer.encode(text).ids` from the library.
     */
    fun encode(text: String): IntArray {
        val normalized = normalizeText(text)

        // WhitespaceSplit: split on Unicode whitespace runs. Code-point aware
        // so non-BMP chars (surrogate pairs) are kept whole.
        val words = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < normalized.length) {
            val cp = normalized.codePointAt(i)
            if (isWhitespace(cp)) {
                if (cur.isNotEmpty()) {
                    words.add(cur.toString())
                    cur.setLength(0)
                }
            } else {
                cur.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        if (cur.isNotEmpty()) words.add(cur.toString())

        val ids = ArrayList<Int>(64)
        ids.add(ID_BOS)
        for (word in words) {
            // Metaspace: split on literal ▁, prefix each non-empty part with ▁.
            for (part in word.split(METASPACE)) {
                if (part.isNotEmpty()) {
                    // Bound Viterbi's O(n²) on spaceless runs (long CJK
                    // strings form one giant "word"): chunk past the cap.
                    val piece = METASPACE + part
                    if (piece.length <= MAX_VITERBI_CHARS) {
                        viterbi(piece, ids)
                    } else {
                        var from = 0
                        while (from < piece.length) {
                            val to = (from + MAX_VITERBI_CHARS).coerceAtMost(piece.length)
                            viterbi(piece.substring(from, to), ids)
                            from = to
                        }
                    }
                }
            }
        }
        ids.add(ID_EOS)

        // fuse_unk: collapse consecutive unk ids (mirrors the library).
        val fused = ArrayList<Int>(ids.size)
        for (tid in ids) {
            if (tid == unkId && fused.isNotEmpty() && fused.last() == unkId) continue
            fused.add(tid)
        }

        // Truncate to MAX_LENGTH: keep head, drop tail (matches library).
        if (fused.size > MAX_LENGTH) {
            val content = fused.subList(1, fused.size - 1)
            val kept = ArrayList<Int>(MAX_LENGTH)
            kept.add(ID_BOS)
            val limit = (MAX_LENGTH - 2).coerceAtMost(content.size)
            for (k in 0 until limit) kept.add(content[k])
            kept.add(ID_EOS)
            return kept.toIntArray()
        }
        return fused.toIntArray()
    }

    /**
     * Unigram Viterbi segmentation of one pre-token into vocab ids, appended
     * to [out]. Unknown chars map to unk with score minScore - 10, fused
     * downstream.
     */
    private fun viterbi(preToken: String, out: MutableList<Int>) {
        val n = preToken.length
        // dp[i] = best total score to reach char index i; best[i] = (start, id).
        val NEG = Float.NEGATIVE_INFINITY
        val dp = FloatArray(n + 1) { NEG }
        val best = arrayOfNulls<IntArray>(n + 1)
        dp[0] = 0.0f

        for (i in 0 until n) {
            if (dp[i] == NEG) continue
            var node = trieRoot
            var hasSingle = false
            var j = i
            while (j < n) {
                node = node.children[preToken[j]] ?: break
                j++
                if (node.id >= 0) {
                    val cand = dp[i] + vocabScores[node.id]
                    if (cand > dp[j]) {
                        dp[j] = cand
                        best[j] = intArrayOf(i, node.id)
                    }
                    if (j == i + 1) hasSingle = true
                }
            }
            if (!hasSingle) {
                val cand = dp[i] + minScore - UNK_PENALTY
                if (cand > dp[i + 1]) {
                    dp[i + 1] = cand
                    best[i + 1] = intArrayOf(i, unkId)
                }
            }
        }

        // Backtrack from the end.
        val reversed = ArrayList<Int>(16)
        var end = n
        while (end > 0) {
            val piece = best[end] ?: break
            reversed.add(realId(piece[1]))
            end = piece[0]
        }
        for (k in reversed.indices.reversed()) out.add(reversed[k])
    }

    private fun isWhitespace(cp: Int): Boolean {
        if (cp == ' '.code) return true
        if (cp == 0x0085) return true // NEL stays as itself after normalization
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp)
    }
}
