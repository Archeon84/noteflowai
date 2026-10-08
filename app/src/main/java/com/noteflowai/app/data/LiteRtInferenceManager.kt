package com.noteflowai.app.data

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.noteflowai.app.data.chat.OfflineRagPromptBuilder
import com.noteflowai.app.data.llm.AvailableModels
import com.noteflowai.app.data.llm.LlmConfig
import com.noteflowai.app.data.llm.ModelId
import com.noteflowai.app.data.llm.ModelInfo
import com.noteflowai.app.data.settings.SettingsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.util.LinkedHashMap

/**
 * High-performance on-device LLM inference manager powered by Google AI Edge (LiteRT-LM).
 *
 * Key Architectural Properties:
 * 1. Hardware Acceleration with Automatic Safe Fallback:
 *    - Primary: Backend.GPU() leveraging mobile GPU/NPU delegates.
 *    - Fallback: Backend.CPU() via XNNPACK if device drivers or shader compilation fail.
 * 2. Context Window Clamped to 4,096 tokens to bound KV-cache memory (~120 MB),
 *    preventing Android Low Memory Killer (LMKD) terminations on 4GB RAM devices.
 * 3. Real-time token delta streaming Flow<String> with coroutine cancellation support.
 */
class LiteRtInferenceManager(private val context: Context) {

    companion object {
        private const val TAG = "LiteRtInference"
        private const val MODEL_DIR = "llm_models"
        private const val MAX_NUM_TOKENS = 4096 // Bounded KV-cache
    }

    private val modelsDir = File(context.filesDir, MODEL_DIR).also { it.mkdirs() }
    private val settingsManager = SettingsManager.getInstance(context)
    private val mutex = Mutex()
    private val loadMutex = Mutex()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isModelReady = MutableStateFlow(false)
    val isModelReady: StateFlow<Boolean> = _isModelReady.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _downloadingModelId = MutableStateFlow<String?>(null)
    val downloadingModelId: StateFlow<String?> = _downloadingModelId.asStateFlow()

    private val _downloadedModels = MutableStateFlow<Set<String>>(emptySet())
    val downloadedModels: StateFlow<Set<String>> = _downloadedModels.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _activeBackend = MutableStateFlow("None")
    val activeBackend: StateFlow<String> = _activeBackend.asStateFlow()

    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var activeConversation: Conversation? = null

    @Volatile
    private var currentlyLoadedModelId: String? = null

    @Volatile
    private var gpuDisabledByRuntimeError: Boolean = false

    // Response cache: MD5(prompt+params) -> result (LRU, 50 entries)
    private val responseCache = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > 50
        }
    }

    init {
        Thread { refreshDownloadedModels() }.start()
    }

    fun refreshDownloadedModels() {
        val downloaded = mutableSetOf<String>()
        for (model in AvailableModels.ALL) {
            val file = File(modelsDir, model.fileName)
            if (file.exists() && file.length() > 1_000_000) {
                downloaded.add(model.id.id)
            }
        }
        _downloadedModels.value = downloaded
        val activeId = settingsManager.localLlmModelIdBlocking
        _isModelReady.value = isModelDownloaded(ModelId.fromId(activeId))
    }

    fun isModelDownloaded(modelId: ModelId): Boolean {
        val info = AvailableModels.get(modelId)
        val file = File(modelsDir, info.fileName)
        return file.exists() && file.length() > 1_000_000
    }

    fun getModelPath(modelId: ModelId = ModelId.fromId(settingsManager.localLlmModelIdBlocking)): String? {
        val info = AvailableModels.get(modelId)
        val file = File(modelsDir, info.fileName)
        return if (file.exists() && file.length() > 1_000_000) file.absolutePath else null
    }

    /**
     * Wait for model loading to finish with a timeout.
     * Returns true if the model is ready, false if timed out or failed.
     */
    suspend fun awaitReady(timeoutMs: Long = 30_000L): Boolean {
        if (!_isLoading.value) return _isModelReady.value
        return try {
            withTimeoutOrNull(timeoutMs) {
                _isLoading.first { !it }
                _isModelReady.value
            } ?: false
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun loadModel(
        modelId: ModelId = ModelId.fromId(settingsManager.localLlmModelIdBlocking)
    ): Boolean = loadMutex.withLock {
        withContext(Dispatchers.IO) {
            val path = getModelPath(modelId)
            if (path == null) {
                Log.e(TAG, "Model file not found for ${modelId.id}")
                return@withContext false
            }

            if (currentlyLoadedModelId == modelId.id && engine?.isInitialized() == true) {
                return@withContext true
            }

            _isLoading.value = true
            try {
                freeInternal()

                var candidateEngine: Engine? = null
                var initializedBackend = "None"

                // Stage 1: Try GPU / NPU hardware acceleration (if not disabled by previous runtime error)
                if (!gpuDisabledByRuntimeError) {
                    var gpuEngine: Engine? = null
                    try {
                        Log.i(TAG, "Attempting LiteRT-LM GPU backend initialization: $path")
                        val gpuConfig = EngineConfig(
                            modelPath = path,
                            backend = Backend.GPU(),
                            maxNumTokens = MAX_NUM_TOKENS
                        )
                        gpuEngine = Engine(gpuConfig)
                        gpuEngine.initialize()
                        candidateEngine = gpuEngine
                        initializedBackend = "GPU (Accelerated)"
                        Log.i(TAG, "LiteRT-LM GPU backend initialized successfully")
                    } catch (gpuError: Throwable) {
                        try { gpuEngine?.close() } catch (_: Throwable) {}
                        gpuDisabledByRuntimeError = true
                        Log.w(
                            TAG,
                            "GPU backend initialization failed (${gpuError.message}). Initiating fallback to CPU (XNNPACK)..."
                        )
                    }
                }

                // Stage 2: Fallback to universal CPU (XNNPACK)
                if (candidateEngine == null) {
                    var cpuEngine: Engine? = null
                    try {
                        Log.i(TAG, "Attempting LiteRT-LM CPU backend initialization: $path")
                        val cpuConfig = EngineConfig(
                            modelPath = path,
                            backend = Backend.CPU(),
                            maxNumTokens = MAX_NUM_TOKENS
                        )
                        cpuEngine = Engine(cpuConfig)
                        cpuEngine.initialize()
                        candidateEngine = cpuEngine
                        initializedBackend = "CPU (XNNPACK)"
                        Log.i(TAG, "LiteRT-LM CPU backend initialized successfully")
                    } catch (cpuError: Throwable) {
                        try { cpuEngine?.close() } catch (_: Throwable) {}
                        Log.e(TAG, "LiteRT-LM CPU backend initialization failed: ${cpuError.message}", cpuError)
                        _activeBackend.value = "Failed"
                        return@withContext false
                    }
                }

                engine = candidateEngine
                _activeBackend.value = initializedBackend
                currentlyLoadedModelId = modelId.id
                _isModelReady.value = true
                Log.i(TAG, "LiteRT-LM loaded model ${modelId.id} with backend $initializedBackend")
                true
            } catch (e: Throwable) {
                Log.e(TAG, "Unexpected error loading model: ${e.message}", e)
                false
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Safely streams conversation output via MessageCallback, bypassing the litertlm AAR's
     * internal sendMessageAsync Flow bridge which has a known coroutines binary incompatibility
     * (calls SendChannel.close$default from kotlinx-coroutines 1.11+).
     */
    private fun streamConversation(conv: Conversation, prompt: String): Flow<String> = callbackFlow {
        try {
            conv.sendMessageAsync(
                prompt,
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val chunk = message.contents.contents
                            .filterIsInstance<Content.Text>()
                            .joinToString("") { it.text }
                        if (chunk.isNotEmpty()) {
                            trySend(chunk)
                        }
                    }

                    override fun onDone() {
                        close(null)
                    }

                    override fun onError(throwable: Throwable) {
                        close(throwable)
                    }
                }
            )
        } catch (e: Throwable) {
            close(e)
        }
        awaitClose {
            // Lifetime managed by caller
        }
    }

    /**
     * Real-time streaming generation emitting token deltas as they are decoded.
     */
    fun generateFlow(
        prompt: String,
        maxTokens: Int = 1280,
        temperature: Float = 0.3f,
        topP: Float = 0.8f,
        topK: Int = 20,
        repeatPenalty: Float = 1.08f,
        modelId: ModelId = ModelId.fromId(settingsManager.localLlmModelIdBlocking)
    ): Flow<String> = flow {
        val safePrompt = OfflineRagPromptBuilder.clampPromptToSafeBudget(prompt)
        mutex.withLock {
            if (engine == null || currentlyLoadedModelId != modelId.id) {
                val loaded = loadModel(modelId)
                if (!loaded) {
                    emit("Error: Could not load LiteRT-LM model")
                    return@flow
                }
            }

            val currentEngine = engine ?: run {
                emit("Error: Engine not available")
                return@flow
            }

            _isGenerating.value = true
            var conv: Conversation? = null
            var shouldRetryOnCpu = false
            var conversationConfig: ConversationConfig? = null
            try {
                val samplerConfig = SamplerConfig(
                    topK = topK.coerceAtLeast(1),
                    topP = topP.toDouble().coerceIn(0.01, 1.0),
                    temperature = temperature.toDouble().coerceAtLeast(0.01),
                    seed = 0
                )
                conversationConfig = ConversationConfig(
                    samplerConfig = samplerConfig
                )

                conv = currentEngine.createConversation(conversationConfig)
                activeConversation = conv

                streamConversation(conv, safePrompt).collect { delta ->
                    emit(delta)
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "LiteRT-LM stream generation cancelled")
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "LiteRT-LM stream generation error: ${e.message}", e)
                val errMsg = e.message.orEmpty()
                if (errMsg.contains("Input token ids are too long", ignoreCase = true) ||
                    errMsg.contains("Exceeding the maximum number of tokens", ignoreCase = true)) {
                    Log.w(TAG, "Prompt tokens exceeded LiteRT-LM capacity: $errMsg. Performing emergency clamp retry...")
                    try {
                        val emergencyPrompt = OfflineRagPromptBuilder.clampPromptToSafeBudget(safePrompt, maxChars = 5_000)
                        val retryConv = currentEngine.createConversation(conversationConfig ?: ConversationConfig())
                        activeConversation = retryConv
                        streamConversation(retryConv, emergencyPrompt).collect { delta ->
                            emit(delta)
                        }
                        try { retryConv.close() } catch (_: Throwable) {}
                    } catch (retryEx: Throwable) {
                        Log.e(TAG, "Emergency clamp retry failed: ${retryEx.message}", retryEx)
                        emit("Error: Note context was too large for on-device memory. Please ask a more specific question.")
                    }
                } else if (_activeBackend.value.contains("GPU") && (errMsg.contains("OpenCL", ignoreCase = true) || errMsg.contains("backend", ignoreCase = true) || e.javaClass.name.contains("LiteRtLmJniException"))) {
                    Log.w(TAG, "GPU execution failed at runtime (${e.message}). Falling back to CPU (XNNPACK)...")
                    shouldRetryOnCpu = true
                } else {
                    emit("Error: ${e.message}")
                }
            } finally {
                try {
                    conv?.close()
                } catch (_: Throwable) {}
                if (activeConversation == conv) {
                    activeConversation = null
                }
            }

            if (shouldRetryOnCpu) {
                gpuDisabledByRuntimeError = true
                freeInternal()
                val loaded = loadModel(modelId)
                if (loaded && engine != null) {
                    var cpuConv: Conversation? = null
                    try {
                        val samplerConfig = SamplerConfig(
                            topK = topK.coerceAtLeast(1),
                            topP = topP.toDouble().coerceIn(0.01, 1.0),
                            temperature = temperature.toDouble().coerceAtLeast(0.01),
                            seed = 0
                        )
                        cpuConv = engine!!.createConversation(ConversationConfig(samplerConfig = samplerConfig))
                        activeConversation = cpuConv
                        streamConversation(cpuConv, safePrompt).collect { delta ->
                            emit(delta)
                        }
                    } catch (cpuEx: CancellationException) {
                        throw cpuEx
                    } catch (cpuEx: Throwable) {
                        Log.e(TAG, "CPU fallback generation error: ${cpuEx.message}", cpuEx)
                        emit("Error: ${cpuEx.message}")
                    } finally {
                        try {
                            cpuConv?.close()
                        } catch (_: Throwable) {}
                        if (activeConversation == cpuConv) {
                            activeConversation = null
                        }
                        _isGenerating.value = false
                    }
                } else {
                    emit("Error: CPU fallback failed to initialize")
                    _isGenerating.value = false
                }
            } else {
                _isGenerating.value = false
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun generate(
        prompt: String,
        maxTokens: Int = 1280,
        temperature: Float = 0.3f,
        topP: Float = 0.8f,
        topK: Int = 20,
        repeatPenalty: Float = 1.08f,
        frequencyPenalty: Float = 0.0f,
        presencePenalty: Float = 0.0f,
        modelId: ModelId = ModelId.fromId(settingsManager.localLlmModelIdBlocking)
    ): String = withContext(Dispatchers.IO) {
        val cacheKey = if (temperature <= 0.1f) {
            computeCacheKey(prompt, maxTokens, temperature, topP, topK, repeatPenalty)
        } else null

        if (cacheKey != null) {
            val cached = synchronized(responseCache) { responseCache[cacheKey] }
            if (cached != null) {
                Log.i(TAG, "Response cache HIT for prompt (${prompt.length} chars)")
                return@withContext cached
            }
        }

        val sb = StringBuilder()
        val startTime = System.currentTimeMillis()
        try {
            generateFlow(
                prompt = prompt,
                maxTokens = maxTokens,
                temperature = temperature,
                topP = topP,
                topK = topK,
                repeatPenalty = repeatPenalty,
                modelId = modelId
            ).collect { delta ->
                sb.append(delta)
            }

            val result = sb.toString()
            val elapsed = System.currentTimeMillis() - startTime
            val wordCount = result.split(Regex("\\s+")).size
            val wps = if (elapsed > 0 && wordCount > 0) String.format("%.1f", wordCount * 1000.0 / elapsed) else "N/A"
            Log.i(TAG, "LiteRT-LM generated ${result.length} chars, ~$wordCount words in ${elapsed}ms (~$wps wps)")

            if (cacheKey != null && !result.startsWith("Error:") && result.isNotBlank()) {
                synchronized(responseCache) { responseCache[cacheKey] = result }
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "LiteRT-LM generation failed: ${e.message}", e)
            "Error: ${e.message}"
        }
    }

    suspend fun downloadModel(
        modelId: ModelId = ModelId.fromId(settingsManager.localLlmModelIdBlocking)
    ) {
        if (_isDownloading.value) return
        _isDownloading.value = true
        _downloadingModelId.value = modelId.id
        _downloadProgress.value = 0f

        val info = AvailableModels.get(modelId)
        withContext(Dispatchers.IO) {
            try {
                val destFile = File(modelsDir, info.fileName)
                val expectedSize = ModelDownloader.getContentLength(info.hfUrl)
                ModelDownloader.download(info.hfUrl, destFile) { bytesRead ->
                    if (expectedSize > 0) {
                        _downloadProgress.value = (bytesRead.toFloat() / expectedSize).coerceIn(0f, 1f)
                    }
                }
                refreshDownloadedModels()
                Log.i(TAG, "Model downloaded: ${destFile.absolutePath} (${destFile.length()} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "Model download failed for ${modelId.id}", e)
            } finally {
                _isDownloading.value = false
                _downloadingModelId.value = null
            }
        }
    }

    fun deleteModel(modelId: ModelId = ModelId.fromId(settingsManager.localLlmModelIdBlocking)) {
        if (_isGenerating.value) {
            Log.w(TAG, "deleteModel() refused: generation is in progress")
            return
        }
        val info = AvailableModels.get(modelId)
        val modelFile = File(modelsDir, info.fileName)
        if (modelFile.exists()) modelFile.delete()

        if (currentlyLoadedModelId == modelId.id) {
            freeInternal()
        }
        refreshDownloadedModels()
        synchronized(responseCache) { responseCache.clear() }
    }

    fun stop() {
        try {
            activeConversation?.close()
        } catch (_: Throwable) {}
        activeConversation = null
        _isGenerating.value = false
    }

    fun free() {
        freeInternal()
    }

    private fun freeInternal() {
        stop()
        currentlyLoadedModelId = null
        try {
            engine?.close()
        } catch (_: Throwable) {}
        engine = null
        _activeBackend.value = "None"
        _isModelReady.value = isModelDownloaded(ModelId.fromId(settingsManager.localLlmModelIdBlocking))
    }

    private fun computeCacheKey(
        prompt: String, maxTokens: Int, temperature: Float,
        topP: Float, topK: Int, repeatPenalty: Float
    ): String {
        val key = "$prompt|$maxTokens|$temperature|$topP|$topK|$repeatPenalty"
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(key.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
