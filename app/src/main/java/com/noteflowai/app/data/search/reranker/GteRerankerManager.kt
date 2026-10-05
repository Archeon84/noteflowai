package com.noteflowai.app.data.search.reranker

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.noteflowai.app.data.ModelDownloader
import com.noteflowai.app.data.NetworkModule
import com.noteflowai.app.data.search.RetrievalResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.exp
import kotlin.math.max

/**
 * On-device cross-encoder reranker inference manager powered by Alibaba GTE Multilingual
 * (Alibaba-NLP/gte-multilingual-reranker-base) running locally via ONNX Runtime.
 */
class GteRerankerManager(private val context: Context) {

    companion object {
        private const val TAG = "GteReranker"
        private const val BASE_URL =
            "https://huggingface.co/onnx-community/gte-multilingual-reranker-base/resolve/main"

        private const val MODEL_URL = "$BASE_URL/onnx/model_quantized.onnx"
        private const val TOKENIZER_URL = "$BASE_URL/tokenizer.json"

        const val EXPECTED_MODEL_SIZE = 340858200L
        const val EXPECTED_TOKENIZER_SIZE = 17082999L
        const val TOTAL_DOWNLOAD_SIZE_BYTES = EXPECTED_MODEL_SIZE + EXPECTED_TOKENIZER_SIZE

        private const val MAX_CANDIDATES_TO_RERANK = 15
        private const val MAX_SEQUENCE_LENGTH = 512
        private const val MIN_RELEVANCE_THRESHOLD = 0.20f
    }

    private val modelDir = File(context.filesDir, "models/reranker")
    private val modelFile = File(modelDir, "model_quantized.onnx")
    private val tokenizerFile = File(modelDir, "tokenizer.json")

    private val mutex = Mutex()

    private val _isDownloaded = MutableStateFlow(checkModelFilesExist())
    val isDownloaded: StateFlow<Boolean> = _isDownloaded.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _isModelReady = MutableStateFlow(checkModelFilesExist())
    val isModelReady: StateFlow<Boolean> = _isModelReady.asStateFlow()

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var tokenizer: GteTokenizer? = null

    fun checkModelFilesExist(): Boolean {
        val modelOk = modelFile.exists() && (modelFile.length() == EXPECTED_MODEL_SIZE || modelFile.length() > 300_000_000L)
        val tokOk = tokenizerFile.exists() && (tokenizerFile.length() == EXPECTED_TOKENIZER_SIZE || tokenizerFile.length() > 10_000_000L)
        return modelOk && tokOk
    }

    suspend fun downloadModel(
        onProgress: (Float) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        if (_isDownloading.value) return@withContext false
        NetworkModule.requireNetworkAllowed(MODEL_URL)

        _isDownloading.value = true
        _downloadProgress.value = 0f

        try {
            modelDir.mkdirs()

            var downloadedBytes = 0L
            val totalBytes = TOTAL_DOWNLOAD_SIZE_BYTES

            // 1. Download model_quantized.onnx
            if (!modelFile.exists() || modelFile.length() < 300_000_000L) {
                Log.i(TAG, "Downloading GTE model_quantized.onnx...")
                ModelDownloader.download(
                    url = MODEL_URL,
                    destFile = modelFile,
                    onProgress = { bytes ->
                        val p = (downloadedBytes + bytes).toFloat() / totalBytes
                        _downloadProgress.value = p.coerceIn(0f, 1f)
                        onProgress(p.coerceIn(0f, 1f))
                    }
                )
            }
            downloadedBytes += modelFile.length()

            // 2. Download tokenizer.json
            if (!tokenizerFile.exists() || tokenizerFile.length() < 10_000_000L) {
                Log.i(TAG, "Downloading GTE tokenizer.json...")
                ModelDownloader.download(
                    url = TOKENIZER_URL,
                    destFile = tokenizerFile,
                    onProgress = { bytes ->
                        val p = (downloadedBytes + bytes).toFloat() / totalBytes
                        _downloadProgress.value = p.coerceIn(0f, 1f)
                        onProgress(p.coerceIn(0f, 1f))
                    }
                )
            }

            val success = checkModelFilesExist()
            _isDownloaded.value = success
            _isModelReady.value = success
            _downloadProgress.value = 1f
            Log.i(TAG, "GTE Reranker downloaded successfully: $success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download GTE Reranker: ${e.message}", e)
            false
        } finally {
            _isDownloading.value = false
        }
    }

    suspend fun deleteModel(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                closeInternal()
                modelFile.delete()
                tokenizerFile.delete()
                modelDir.delete()
                _isDownloaded.value = false
                _isModelReady.value = false
                _downloadProgress.value = 0f
                Log.i(TAG, "GTE Reranker deleted from storage")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete GTE Reranker: ${e.message}", e)
                false
            }
        }
    }

    private fun ensureSessionLoaded() {
        if (ortSession != null && tokenizer != null) return

        if (!checkModelFilesExist()) {
            throw IllegalStateException("GTE Reranker model files are not available on disk")
        }

        if (tokenizer == null) {
            Log.i(TAG, "Initializing GteTokenizer...")
            tokenizer = GteTokenizer(tokenizerFile)
        }

        if (ortSession == null) {
            Log.i(TAG, "Initializing ONNX Runtime session for GTE Reranker...")
            val env = OrtEnvironment.getEnvironment()
            ortEnv = env
            val sessionOpts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            }
            ortSession = env.createSession(modelFile.absolutePath, sessionOpts)
            Log.i(TAG, "ONNX Runtime session initialized successfully")
        }
    }

    private fun closeInternal() {
        try {
            ortSession?.close()
        } catch (_: Exception) {}
        ortSession = null
        ortEnv = null
        tokenizer = null
    }

    /**
     * Reranks the given candidate list against the user's query using cross-attention.
     * Returns candidates sorted by reranked score in descending order.
     */
    suspend fun rerank(
        query: String,
        candidates: List<RetrievalResult>,
        topK: Int = 5
    ): List<RetrievalResult> = withContext(Dispatchers.Default) {
        if (candidates.isEmpty() || query.isBlank()) return@withContext candidates

        mutex.withLock {
            try {
                ensureSessionLoaded()
                val session = ortSession ?: return@withContext candidates
                val env = ortEnv ?: return@withContext candidates
                val tok = tokenizer ?: return@withContext candidates

                val itemsToScore = candidates.take(MAX_CANDIDATES_TO_RERANK)
                val batchSize = itemsToScore.size
                val encodedPairs = itemsToScore.map { candidate ->
                    tok.encodePair(query, candidate.text, MAX_SEQUENCE_LENGTH)
                }

                val maxBatchLen = encodedPairs.maxOfOrNull { it.first.size } ?: 1
                val batchInputIds = Array(batchSize) { i ->
                    val pair = encodedPairs[i]
                    val arr = LongArray(maxBatchLen) { GteTokenizer.PAD_ID }
                    System.arraycopy(pair.first, 0, arr, 0, pair.first.size)
                    arr
                }
                val batchAttentionMask = Array(batchSize) { i ->
                    val pair = encodedPairs[i]
                    val arr = LongArray(maxBatchLen) { 0L }
                    System.arraycopy(pair.second, 0, arr, 0, pair.second.size)
                    arr
                }

                val inputTensor = OnnxTensor.createTensor(env, batchInputIds)
                val maskTensor = OnnxTensor.createTensor(env, batchAttentionMask)

                val inputMap = mapOf(
                    "input_ids" to inputTensor,
                    "attention_mask" to maskTensor
                )

                val startTime = System.currentTimeMillis()
                val scores = mutableListOf<Float>()

                session.run(inputMap).use { result ->
                    val rawOutput = result.get(0).value
                    for (i in 0 until batchSize) {
                        val logit = when (rawOutput) {
                            is Array<*> -> {
                                val row = rawOutput[i]
                                when (row) {
                                    is FloatArray -> row[0]
                                    is Array<*> -> (row[0] as? Number)?.toFloat() ?: 0f
                                    else -> 0f
                                }
                            }
                            is FloatArray -> rawOutput[i]
                            else -> 0f
                        }
                        // Sigmoid
                        val score = 1.0f / (1.0f + exp(-logit.toDouble()).toFloat())
                        scores.add(score)
                    }
                }

                inputTensor.close()
                maskTensor.close()

                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "Reranked $batchSize candidates in ${elapsed}ms")

                val scoredCandidates = itemsToScore.mapIndexed { idx, candidate ->
                    val s = scores.getOrElse(idx) { candidate.score }
                    candidate.copy(
                        score = s,
                        metadata = (candidate.metadata ?: emptyMap()) + mapOf(
                            "rerank_score" to "%.4f".format(s),
                            "original_score" to "%.4f".format(candidate.score)
                        )
                    )
                }

                // Filter candidates with very low relevance and sort descending
                val reranked = scoredCandidates
                    .filter { it.score >= MIN_RELEVANCE_THRESHOLD }
                    .sortedByDescending { it.score }

                val resultList = if (reranked.isNotEmpty()) reranked else scoredCandidates.sortedByDescending { it.score }
                resultList.take(topK)
            } catch (e: Exception) {
                Log.w(TAG, "Reranker inference failed (${e.message}). Falling back to original ranking.", e)
                candidates.take(topK)
            }
        }
    }
}
