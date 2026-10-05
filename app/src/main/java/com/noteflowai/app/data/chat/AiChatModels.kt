package com.noteflowai.app.data.chat

import androidx.compose.runtime.Immutable
import com.google.gson.annotations.SerializedName

@Immutable
data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val attachmentUri: String? = null,
    val attachmentType: String? = null, // "image", "audio", "video", "file"
    val timestamp: Long = System.currentTimeMillis(),
    val images: List<String>? = null, // Base64 encoded images for Vision API
    val ragSources: List<RagSource>? = null,  // RAG attribution for this message
    @SerializedName("tool_calls") val toolCalls: List<ToolCall>? = null,  // Function/tool calls from AI
    val groundedResponse: GroundedChatResponse? = null,  // Phase 5: Structured grounded response
    val groundedAnswerId: String? = null,  // Phase 6: Answer ID for citation footer hydration
    val groundedDisposition: String? = null,  // Phase 6: Grounding disposition ("FULLY_VALIDATED", "UNVERIFIED", etc.)
) {
    // Computed properties (not constructor params) — Gson bypasses constructor,
    // so precomputed fields would deserialize as 0 for persisted messages.
    val wordCount: Int get() = if (content.isBlank()) 0 else content.trim().split("\\s+".toRegex()).size
    val estimatedTokenCount: Int get() = (wordCount * 4 / 3).coerceAtLeast(1)
}

// Tool/function calling models
data class ToolCall(
    val id: String = "",
    val type: String = "function",
    val function: FunctionCall = FunctionCall()
)

data class FunctionCall(
    val name: String = "",
    @SerializedName("arguments") val arguments: String = "{}"
)

/**
 * Attribution metadata for a note source used in RAG retrieval.
 */
@Immutable
data class RagSource(
    val noteFileName: String,
    val noteTitle: String,
    val relevanceScore: Float,
    val excerpt: String,
    val source: String,  // "bm25", "embedding", "graph"
    val retrievalReasons: List<String> = emptyList(),
    val concepts: List<String> = emptyList()
)

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false,
    @SerializedName("system") val system: String? = null,
    val temperature: Float? = null,
    @SerializedName("presence_penalty") val presencePenalty: Float? = null,
    @SerializedName("top_p") val topP: Float? = null
)

data class ChatResponse(
    val model: String,
    val message: ChatMessage?,
    val choices: List<ChatChoice>?
)

data class ChatChoice(
    val message: ChatMessage
)

// For Streaming
data class ChatStreamResponse(
    val model: String? = null,
    val message: ChatMessageDelta? = null,
    val choices: List<ChatStreamChoice>? = null,
    val done: Boolean = false
)

data class ChatStreamChoice(
    val delta: ChatMessageDelta,
    @SerializedName("finish_reason") val finishReason: String? = null
)

data class ChatMessageDelta(
    val content: String? = null,
    val role: String? = null,
    @SerializedName("tool_calls") val toolCalls: List<ToolCallDelta>? = null
)

// Streaming tool call delta (arguments come in chunks)
data class ToolCallDelta(
    val index: Int? = null,
    val id: String? = null,
    val type: String? = null,
    val function: FunctionCallDelta? = null
)

data class FunctionCallDelta(
    val name: String? = null,
    val arguments: String? = null
)
