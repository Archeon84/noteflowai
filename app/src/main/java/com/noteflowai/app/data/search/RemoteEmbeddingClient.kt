package com.noteflowai.app.data.search

import android.util.Log
import com.google.gson.Gson
import com.noteflowai.app.data.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Calls remote embedding endpoints (OpenAI-compatible API).
 *
 * Supports:
 * - OpenAI: `POST /v1/embeddings` with model "text-embedding-3-small" (1536-dim)
 * - Ollama: `POST /api/embeddings` with model "nomic-embed-text" (768-dim)
 * - DeepSeek / any OpenAI-compatible provider
 *
 * Falls back gracefully on network errors (returns null).
 */
class RemoteEmbeddingClient {

    companion object {
        private const val TAG = "RemoteEmbeddingClient"
        private const val REQUEST_TIMEOUT_SEC = 15L
        private const val MAX_BATCH_SIZE = 20  // OpenAI supports up to 2048, but keep small for mobile
    }

    private val gson = Gson()
    private val client = NetworkModule.newClientBuilder()
        .readTimeout(REQUEST_TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(REQUEST_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    /** Result of an embedding request. */
    data class EmbeddingResult(
        val vectors: List<FloatArray>,
        val dimensions: Int,
        val model: String
    )

    /**
     * Embed one or more texts using the configured remote provider.
     *
     * @param texts The texts to embed (batched automatically if > 1)
     * @param provider Provider name: "Ollama", "OpenAI", "DeepSeek", "LM Studio", "Custom"
     * @param apiKey API key (empty for Ollama/local providers)
     * @param baseUrl Base URL of the provider (e.g. "http://10.0.2.2:11434/")
     * @param model Optional model override (null = provider default)
     * @return EmbeddingResult or null on failure
     */
    suspend fun embed(
        texts: List<String>,
        provider: String,
        apiKey: String,
        baseUrl: String,
        model: String? = null
    ): EmbeddingResult? = withContext(Dispatchers.IO) {
        try {
            if (texts.isEmpty()) return@withContext null

            // Ollama has no batch endpoint: embed each text sequentially and
            // aggregate, rather than silently embedding only the first.
            if (provider.equals("Ollama", ignoreCase = true)) {
                val vectors = ArrayList<FloatArray>(texts.size)
                for (text in texts) {
                    val single = runRequest(listOf(text), provider, apiKey, baseUrl, model)
                        ?: return@withContext null
                    vectors.add(single.vectors.firstOrNull() ?: return@withContext null)
                }
                EmbeddingResult(
                    vectors = vectors,
                    dimensions = vectors.firstOrNull()?.size ?: 0,
                    model = model ?: "nomic-embed-text"
                )
            } else {
                // OpenAI-compatible endpoints cap the batch size: loop in
                // MAX_BATCH_SIZE chunks instead of silently dropping texts
                // past the cap (a source's segments 20+ were never embedded).
                val vectors = ArrayList<FloatArray>(texts.size)
                var dimensions = 0
                var modelName: String = model ?: "embedding"
                for (chunk in texts.chunked(MAX_BATCH_SIZE)) {
                    val single = runRequest(chunk, provider, apiKey, baseUrl, model)
                        ?: return@withContext null
                    vectors.addAll(single.vectors)
                    dimensions = single.dimensions
                    modelName = single.model
                }
                EmbeddingResult(
                    vectors = vectors,
                    dimensions = dimensions,
                    model = modelName
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Embedding request error: ${e.message}")
            null
        }
    }

    /** Execute one embedding request and parse the response body. */
    private suspend fun runRequest(
        texts: List<String>,
        provider: String,
        apiKey: String,
        baseUrl: String,
        model: String?
    ): EmbeddingResult? = withContext(Dispatchers.IO) {
        val (endpoint, body, responseParser) = buildRequest(texts, provider, apiKey, baseUrl, model)

        val request = Request.Builder()
            .url(endpoint)
            .post(body.toRequestBody("application/json".toMediaType()))
            .apply {
                if (apiKey.isNotBlank()) {
                    addHeader("Authorization", "Bearer $apiKey")
                }
            }
            .build()

        // use{} closes the response on every path: the old code leaked it
        // on failures and parser throws, exhausting the OkHttp pool.
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "Embedding request failed: ${response.code} ${response.message}")
                return@withContext null
            }

            val json = response.body?.string() ?: return@withContext null
            responseParser(json)
        }
    }

    /**
     * Embed a single text. Convenience wrapper.
     */
    suspend fun embed(
        text: String,
        provider: String,
        apiKey: String,
        baseUrl: String,
        model: String? = null
    ): FloatArray? {
        return embed(listOf(text), provider, apiKey, baseUrl, model)?.vectors?.firstOrNull()
    }

    /**
     * Build the HTTP request details based on provider type.
     * Returns (endpoint URL, request body JSON string, response parser).
     */
    private fun buildRequest(
        texts: List<String>,
        provider: String,
        apiKey: String,
        baseUrl: String,
        model: String?
    ): Triple<String, String, (String) -> EmbeddingResult> {
        val normalizedBase = baseUrl.trimEnd('/')

        return when (provider.lowercase(java.util.Locale.ROOT)) {
            "ollama" -> {
                // Ollama uses a different endpoint and format
                // For batch: embed one at a time (Ollama doesn't support batch embedding well)
                val text = texts.firstOrNull() ?: ""
                val modelName = model?.ifBlank { null } ?: "ibm-granite/granite-embedding-311m-multilingual-r2"
                val body = gson.toJson(mapOf(
                    "model" to modelName,
                    "prompt" to text
                ))
                val endpoint = "$normalizedBase/api/embeddings"
                Triple(endpoint, body) { json ->
                    val parsed = gson.fromJson(json, Map::class.java)
                    @Suppress("UNCHECKED_CAST")
                    val embedding = (parsed["embedding"] as? List<Number>)?.map { it.toFloat() }?.toFloatArray()
                        ?: throw IllegalArgumentException("No embedding in Ollama response")
                    EmbeddingResult(
                        vectors = listOf(embedding),
                        dimensions = embedding.size,
                        model = modelName
                    )
                }
            }
            else -> {
                // OpenAI-compatible format (OpenAI, DeepSeek, LM Studio, Custom)
                val modelName = model?.ifBlank { null } ?: when (provider.lowercase(java.util.Locale.ROOT)) {
                    "openai" -> "text-embedding-3-small"
                    "deepseek" -> "deepseek-embedding"
                    else -> "ibm-granite/granite-embedding-311m-multilingual-r2"
                }
                val body = gson.toJson(mapOf(
                    "model" to modelName,
                    "input" to texts
                ))
                val endpoint = "$normalizedBase/v1/embeddings"
                Triple(endpoint, body) { json ->
                    val parsed = gson.fromJson(json, Map::class.java)
                    @Suppress("UNCHECKED_CAST")
                    val data = parsed["data"] as? List<Map<String, Any>>
                        ?: throw IllegalArgumentException("No data in embedding response")
                    val vectors = data.map { item ->
                        (item["embedding"] as? List<Number>)?.map { it.toFloat() }?.toFloatArray()
                            ?: throw IllegalArgumentException("Malformed embedding vector")
                    }
                    EmbeddingResult(
                        vectors = vectors,
                        dimensions = vectors.firstOrNull()?.size ?: 0,
                        model = modelName
                    )
                }
            }
        }
    }
}
