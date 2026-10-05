package com.noteflowai.app.data.search

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * On-device text embedder using ONNX Runtime with a multilingual MiniLM model.
 *
 * Model: sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2
 *   (INT8 qint8 arm64 export, ~118MB) — 50+ languages, multilingual note search.
 * Output: 384-dimensional normalized embedding vector
 *
 * NOTE: the tokenizer is SentencePiece Unigram (250k vocab, tokenizer.json),
 * implemented by [SentencePieceUnigramTokenizer] (mirrors the HuggingFace
 * `tokenizers` library, validated against it). Falls back gracefully if the
 * model isn't downloaded yet or inference fails.
 */
class OnDeviceEmbedder {

    companion object {
        private const val TAG = "OnDeviceEmbedder"
        private const val MODEL_DIR = "embedding_model"
        private const val MODEL_FILE = "multilingual-MiniLM-L12-qint8.onnx"
        private const val VOCAB_FILE = "tokenizer.json"
        private const val MODEL_URL = "https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/resolve/main/onnx/model_qint8_arm64.onnx"
        private const val VOCAB_URL = "https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/resolve/main/tokenizer.json"
        private const val MAX_SEQ_LENGTH = 256
        private const val DIMENSIONS = 384
        // Sanity floors (well below real sizes: ~120MB model, multi-MB
        // vocab) that prove a download completed instead of truncating.
        private const val MIN_MODEL_BYTES = 50L * 1024 * 1024
        private const val MIN_VOCAB_BYTES = 1024L * 1024
    }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var tokenizer: SentencePieceUnigramTokenizer? = null
    private var initialized = false

    /**
     * Initialize the model. Downloads files on first use.
     * @return true if model is ready for inference
     */
    suspend fun initialize(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (initialized) return@withContext true

        try {
            val modelDir = File(context.filesDir, MODEL_DIR)
            modelDir.mkdirs()

            val modelFile = File(modelDir, MODEL_FILE)
            val vocabFile = File(modelDir, VOCAB_FILE)

            // Download model if not present. Undersized leftovers from an
            // older interrupted download are discarded and re-fetched.
            if (!modelFile.exists() || modelFile.length() < MIN_MODEL_BYTES) {
                if (modelFile.exists()) modelFile.delete()
                Log.i(TAG, "Downloading embedding model...")
                downloadFile(MODEL_URL, modelFile, MIN_MODEL_BYTES)
                Log.i(TAG, "Model downloaded: ${modelFile.length()} bytes")
            }

            // Download vocab if not present
            if (!vocabFile.exists() || vocabFile.length() < MIN_VOCAB_BYTES) {
                if (vocabFile.exists()) vocabFile.delete()
                Log.i(TAG, "Downloading vocabulary...")
                downloadFile(VOCAB_URL, vocabFile, MIN_VOCAB_BYTES)
                Log.i(TAG, "Vocabulary downloaded: ${vocabFile.length()} bytes")
            }

            // Build the SentencePiece Unigram tokenizer from tokenizer.json.
            tokenizer = SentencePieceUnigramTokenizer.fromJson(vocabFile.readText())

            // Create ONNX session.
            env = OrtEnvironment.getEnvironment()
            val sessionOptions = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            session = env!!.createSession(modelFile.absolutePath, sessionOptions)

            if (tokenizer == null) {
                Log.w(TAG, "Tokenizer failed to load — embedder not ready")
                initialized = false
                return@withContext false
            }

            initialized = true
            Log.i(TAG, "On-device embedder initialized (${tokenizer!!.vocabSize} vocab, ${DIMENSIONS}d)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize on-device embedder: ${e.message}")
            false
        }
    }

    /**
     * Embed a single text into a 384-dim vector.
     * @return embedding vector or null on failure
     */
    suspend fun embed(text: String): FloatArray? = withContext(Dispatchers.IO) {
        if (!initialized || session == null || env == null) {
            Log.w(TAG, "Embedder not initialized")
            return@withContext null
        }

        try {
            val tokens = tokenize(text)
            if (tokens.isEmpty()) return@withContext null

            // Create input tensors. The all-MiniLM-L6-v2 ONNX export declares its
            // input_ids/attention_mask/token_type_ids as int64, so feed LongBuffer;
            // int32 inputs are rejected with ORT_INVALID_ARGUMENT.
            val inputIds = LongArray(MAX_SEQ_LENGTH)
            val attentionMask = LongArray(MAX_SEQ_LENGTH)
            val tokenTypeIds = LongArray(MAX_SEQ_LENGTH)

            for (i in tokens.indices.take(MAX_SEQ_LENGTH)) {
                inputIds[i] = tokens[i].toLong()
                attentionMask[i] = 1L
                tokenTypeIds[i] = 0L
            }

            // Tensor lifetime is scoped with try/finally: any throw between
            // creation and use used to leak native ONNX memory until OOM
            // over queries. (OnnxTensor/Result are AutoCloseable, not
            // Closeable, so stdlib use{} does not apply.)
            val inputIdsTensor = OnnxTensor.createTensor(
                env!!,
                LongBuffer.wrap(inputIds),
                longArrayOf(1, MAX_SEQ_LENGTH.toLong())
            )
            val pooled: FloatArray = try {
                val attentionMaskTensor = OnnxTensor.createTensor(
                    env!!,
                    LongBuffer.wrap(attentionMask),
                    longArrayOf(1, MAX_SEQ_LENGTH.toLong())
                )
                try {
                    val tokenTypeIdsTensor = OnnxTensor.createTensor(
                        env!!,
                        LongBuffer.wrap(tokenTypeIds),
                        longArrayOf(1, MAX_SEQ_LENGTH.toLong())
                    )
                    try {
                        val inputMap = mapOf(
                            "input_ids" to inputIdsTensor,
                            "attention_mask" to attentionMaskTensor,
                            "token_type_ids" to tokenTypeIdsTensor
                        )

                        // Run inference
                        val output = session!!.run(inputMap)
                        try {
                            // Extract last_hidden_state (shape: [1, seq_len, 384])
                            val outputTensor = output[0].value as Array<Array<FloatArray>>
                            poolAndNormalize(outputTensor[0], attentionMask)
                        } finally {
                            output.close()
                        }
                    } finally {
                        tokenTypeIdsTensor.close()
                    }
                } finally {
                    attentionMaskTensor.close()
                }
            } finally {
                inputIdsTensor.close()
            }

            pooled
        } catch (e: Exception) {
            Log.e(TAG, "Embedding inference failed: ${e.message}")
            null
        }
    }

    /**
     * Mean-pool sequence hidden states over non-padding tokens, then L2
     * normalize. Pure function extracted for the scoped-tensor path above.
     */
    private fun poolAndNormalize(hiddenStates: Array<FloatArray>, attentionMask: LongArray): FloatArray {
        val maskSum = attentionMask.sum().coerceAtLeast(1L)
        val pooled = FloatArray(DIMENSIONS)
        for (i in 0 until MAX_SEQ_LENGTH) {
            if (attentionMask[i] == 1L) {
                for (d in 0 until DIMENSIONS) {
                    pooled[d] += hiddenStates[i][d]
                }
            }
        }
        for (d in 0 until DIMENSIONS) {
            pooled[d] /= maskSum.toFloat()
        }

        // L2 normalize
        val norm = kotlin.math.sqrt(pooled.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0f) {
            for (d in 0 until DIMENSIONS) {
                pooled[d] /= norm
            }
        }
        return pooled
    }

    /**
     * Check if the model is ready (downloaded and initialized).
     */
    fun isReady(): Boolean = initialized

    /**
     * Release resources.
     */
    fun close() {
        session?.close()
        env?.close()
        session = null
        env = null
        initialized = false
    }

    // ── Tokenization ──────────────────────────────────────────

    /** Tokenize text to ids (includes <s>/</s> specials, truncated to 128). */
    private fun tokenize(text: String): List<Int> {
        val tok = tokenizer ?: return emptyList()
        return tok.encode(text).toList()
    }

    // ── File operations ───────────────────────────────────────

    /**
     * Download [url] atomically: stream to a temp file, sanity-check the
     * size, then rename. The old direct write left a truncated file behind
     * on interruption; the next launch saw "exists" and skipped the
     * download, failing session creation forever on a poisoned file.
     */
    private fun downloadFile(url: String, dest: File, minBytes: Long) {
        // Local-Only Mode blocks model downloads (throws before any traffic).
        com.noteflowai.app.data.NetworkModule.requireNetworkAllowed(url)
        val tmp = File(dest.parent, "${dest.name}.part")
        try {
            val connection = URL(url).openConnection()
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.connect()

            connection.getInputStream().use { input ->
                tmp.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }
            if (tmp.length() < minBytes) {
                throw java.io.IOException("Download too small (${tmp.length()} bytes): $url")
            }
            if (!tmp.renameTo(dest)) {
                throw java.io.IOException("Failed to move download into place: $dest")
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
