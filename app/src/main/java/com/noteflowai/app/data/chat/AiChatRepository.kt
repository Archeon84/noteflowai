package com.noteflowai.app.data.chat

import com.google.gson.Gson
import com.noteflowai.app.BuildConfig
import com.noteflowai.app.data.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class AiChatRepository {
    private val gson = Gson()
    private val client = NetworkModule.newClientBuilder()
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().apply {
                    // HEADERS only: BODY would dump full system prompts and
                    // note contents into logcat (privacy leak on user devices).
                    level = HttpLoggingInterceptor.Level.HEADERS
                    redactHeader("Authorization")
                })
            }
        }
        .readTimeout(300, TimeUnit.SECONDS)
        .build()
    
    private fun getRetrofit(baseUrl: String): Retrofit {
        val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(url)
            .addConverterFactory(GsonConverterFactory.create())
            .client(client)
            .build()
    }

    /**
     * Streams response from AI provider.
     * Supports Multi-modal (Vision) for DeepSeek/OpenAI and Ollama.
     */
    fun streamResponse(
        baseUrl: String,
        provider: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        messages: List<ChatMessage>,
        temperature: Float? = null,
        presencePenalty: Float? = null,
        topP: Float? = null,
        contextTokens: Int? = null,
        useKvCache: Boolean? = null,
        idleTimeoutMs: Long = 300_000,
        tools: List<Map<String, Any>>? = null
    ): Flow<String> = flow {
        val isOllama = provider.equals("Ollama", true)
        val baseUrlNormalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val hasV1 = baseUrlNormalized.contains("/v1/")
        val path = if (isOllama) "api/chat" else if (hasV1) "chat/completions" else "v1/chat/completions"
        val url = "$baseUrlNormalized$path"
        
        // Transform messages for Multi-modal Vision API
        // NOTE: tool-call assistant messages (content="" + toolCalls set) are
        // already filtered out by the caller, so we never need to serialize
        // tool_calls into the API payload here.
        val transformedMessages = messages.map { msg ->
            if (msg.role == "system") return@map mapOf("role" to "system", "content" to msg.content)

            if (!msg.images.isNullOrEmpty()) {
                val textContent = msg.content.ifBlank { "Describe this image in detail." }
                if (isOllama) {
                    // Ollama format
                    mutableMapOf<String, Any>(
                        "role" to msg.role,
                        "content" to textContent,
                        "images" to msg.images
                    )
                } else {
                    // DeepSeek / OpenAI Vision format
                    val multiModalContent = mutableListOf<Map<String, Any>>()
                    multiModalContent.add(mapOf("type" to "text", "text" to textContent))
                    msg.images.forEach { base64 ->
                        multiModalContent.add(mapOf(
                            "type" to "image_url",
                            "image_url" to mapOf("url" to "data:image/jpeg;base64,$base64")
                        ))
                    }
                    mapOf("role" to msg.role, "content" to multiModalContent)
                }
            } else {
                // Standard text message
                mapOf("role" to msg.role, "content" to msg.content)
            }
        }.toMutableList()

        if (systemPrompt.isNotEmpty()) {
            transformedMessages.add(0, mapOf("role" to "system", "content" to systemPrompt))
        }

        val requestBodyMap = mutableMapOf<String, Any>(
            "model" to model,
            "messages" to transformedMessages,
            "stream" to true
        )
        
        temperature?.let { requestBodyMap["temperature"] = it }
        presencePenalty?.let { requestBodyMap["presence_penalty"] = it }
        topP?.let { requestBodyMap["top_p"] = it }

        if (isOllama) {
            val optionsMap = mutableMapOf<String, Any>()
            contextTokens?.let { optionsMap["num_ctx"] = it }
            useKvCache?.let { optionsMap["f16_kv"] = it }
            if (optionsMap.isNotEmpty()) {
                requestBodyMap["options"] = optionsMap
            }
        } else {
            contextTokens?.let { requestBodyMap["max_tokens"] = it }
            useKvCache?.let { requestBodyMap["kv_cache"] = it }
        }

        // Inject tool definitions for function calling
        tools?.let { requestBodyMap["tools"] = it }

        val client = this@AiChatRepository.client
        val request = Request.Builder()
            .url(url)
            .post(gson.toJson(requestBodyMap).toRequestBody("application/json".toMediaType()))
            .apply {
                if (apiKey.isNotEmpty()) addHeader("Authorization", "Bearer $apiKey")
            }
            .build()

        // Accumulate tool calls from streaming deltas
        val accumulatedToolCalls = mutableMapOf<Int, MutableMap<String, Any>>()
        var nextNullIndex = 0

        // Phase 2 honesty: a socket drop or timeout ends the read loop without
        // a terminal marker, and the partial text would otherwise be presented
        // as a complete answer (and even cited). Track clean termination here
        // (outside `use`) so the post-stream marker can read it.
        var sawTerminal = false
        var emittedContent = false

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                emit("Error: HTTP ${response.code} ${response.message}")
                return@flow
            }

            val source = response.body?.source() ?: return@flow
            var lastChunkTime = System.currentTimeMillis()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue
                if (line.startsWith(":")) continue

                // Check idle timeout
                val now = System.currentTimeMillis()
                if (now - lastChunkTime > idleTimeoutMs) {
                    emit("Error: Stream idle timeout - no data for ${idleTimeoutMs / 1000}s")
                    break
                }
                lastChunkTime = now

                try {
                    if (isOllama) {
                        val chunk = gson.fromJson(line, ChatStreamResponse::class.java)
                        if (chunk.done) sawTerminal = true
                        chunk.message?.content?.let { emit(it); emittedContent = true }
                    // Ollama tool calls (in message.tool_calls) — each is a complete
                    // call (not a delta), so emit them directly instead of accumulating.
                    chunk.message?.toolCalls?.forEach { tc ->
                        val fnName = tc.function?.name ?: return@forEach
                        val fnArgs = tc.function.arguments ?: "{}"
                        accumulatedToolCalls[nextNullIndex++] = mutableMapOf(
                            "id" to (tc.id ?: "tool_${System.currentTimeMillis()}"),
                            "type" to (tc.type ?: "function"),
                            "function" to mutableMapOf("name" to fnName, "arguments" to fnArgs)
                        )
                    }
                    } else {
                        val trimmedLine = line.trim()
                        if (!trimmedLine.startsWith("data:")) continue
                        val data = trimmedLine.removePrefix("data:").trim()
                        if (data == "[DONE]") { sawTerminal = true; break }
                        if (data.isEmpty()) continue
                        val chunk = gson.fromJson(data, ChatStreamResponse::class.java)
                        chunk.choices?.firstOrNull()?.delta?.content?.let { emit(it); emittedContent = true }
                        // OpenAI tool calls (in choices[].delta.tool_calls)
                        chunk.choices?.firstOrNull()?.delta?.toolCalls?.forEach { tc ->
                            val idx = tc.index ?: 0
                            val existing = accumulatedToolCalls.getOrPut(idx) {
                                mutableMapOf("id" to (tc.id ?: ""), "type" to (tc.type ?: "function"),
                                    "function" to mutableMapOf("name" to "", "arguments" to ""))
                            }
                            @Suppress("UNCHECKED_CAST")
                            tc.function?.let { fn ->
                                val funcMap = existing["function"] as MutableMap<String, Any>
                                fn.name?.let { funcMap["name"] = it }
                                fn.arguments?.let { funcMap["arguments"] = (funcMap["arguments"] as String) + it }
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("AiChat", "SSE parse error: ${e.message}")
                }
            }
        }

        // Emit tool calls as a special marker after streaming completes.
        // Validate that each tool call has valid JSON arguments before emitting;
        // skip any with malformed arguments to avoid upstream parse failures.
        if (accumulatedToolCalls.isNotEmpty()) {
            val toolCallsList = accumulatedToolCalls.values.mapNotNull { m ->
                val func = m["function"] as Map<*, *>
                val args = func["arguments"] as String
                try {
                    com.google.gson.Gson().fromJson(args, com.google.gson.JsonObject::class.java)
                } catch (e: Exception) {
                    android.util.Log.w("AiChat", "Skipping tool call '${func["name"]}': malformed arguments JSON: ${e.message}")
                    null
                } ?: return@mapNotNull null
                ToolCall(
                    id = m["id"] as String,
                    type = m["type"] as String,
                    function = FunctionCall(
                        name = func["name"] as String,
                        arguments = args
                    )
                )
            }
            if (toolCallsList.isNotEmpty()) {
                emit("__TOOL_CALLS__:${gson.toJson(toolCallsList)}")
            }

            // Truncated stream: content was emitted but the server never closed
            // it cleanly. Say so inline — downstream (grounding/parse) treats
            // this as incomplete rather than authoritative.
            if (emittedContent && !sawTerminal) {
                emit("\n[TRUNCATED: the response stream ended before completion — this answer may be incomplete]")
            }
        }
    }.flowOn(Dispatchers.IO).buffer(capacity = 512, onBufferOverflow = BufferOverflow.SUSPEND)

    suspend fun getResponse(
        baseUrl: String,
        provider: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        messages: List<ChatMessage>,
        temperature: Float? = null,
        presencePenalty: Float? = null,
        topP: Float? = null,
        contextTokens: Int? = null,
        useKvCache: Boolean? = null,
        idleTimeoutMs: Long = 300_000
    ): ChatMessage {
        var result = ""
        streamResponse(baseUrl, provider, apiKey, model, systemPrompt, messages, temperature, presencePenalty, topP, contextTokens, useKvCache, idleTimeoutMs).collect {
            result += it
        }
        return ChatMessage(role = "assistant", content = result)
    }

    suspend fun testConnection(baseUrl: String, provider: String, apiKey: String): List<String> {
        val baseUrlNormalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val service = getRetrofit(baseUrlNormalized).create(AiApiService::class.java)
        val headers = mutableMapOf<String, String>()
        if (apiKey.isNotEmpty()) {
            headers["Authorization"] = "Bearer $apiKey"
        }

        val isOllama = provider.equals("Ollama", true)
        val hasV1 = baseUrlNormalized.contains("/v1/")
        val path = if (isOllama) "api/tags" else if (hasV1) "models" else "v1/models"
        
        return try {
            val response = service.getModels(path, headers)
            val modelList = mutableListOf<String>()
            
            if (provider.equals("Ollama", true)) {
                val models = response["models"] as? List<*>
                models?.forEach {
                    val m = it as? Map<*, *>
                    (m?.get("name") as? String)?.let { name -> modelList.add(name) }
                }
            } else {
                val data = response["data"] as? List<*>
                data?.forEach {
                    val m = it as? Map<*, *>
                    (m?.get("id") as? String)?.let { id -> modelList.add(id) }
                }
            }
            modelList
        } catch (e: Exception) {
            throw e
        }
    }
}
