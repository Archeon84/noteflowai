package com.noteflowai.app.data.chat

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.noteflowai.app.data.memory.model.SourceType
import java.lang.reflect.Type

/** Result of parsing the model's answer body; Failure never crashes the pipeline. */
sealed class ParseResult {
    data class Success(val response: GroundedChatResponse) : ParseResult()
    data class Failure(val reason: String) : ParseResult()
}

object GroundedResponseParser {

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(SourceType::class.java, LenientSourceTypeDeserializer)
        .create()

    fun parse(json: String): ParseResult {
        if (json.isBlank()) return ParseResult.Failure("Empty or blank answer body")
        return try {
            val parsed = gson.fromJson(json, GroundedChatResponse::class.java)
            if (parsed == null || parsed.answer.isNullOrBlank()) {
                ParseResult.Failure("Answer field missing or blank")
            } else {
                val normalized = parsed.copy(
                    citations = parsed.citations.orEmpty(),
                    claims = parsed.claims.orEmpty().map { c ->
                        Claim(
                            text = c.text ?: "",
                            citationIds = c.citationIds.orEmpty(),
                            memory_object_ids = c.memory_object_ids.orEmpty(),
                            uncertainty = c.uncertainty ?: UncertaintyLevel.LOW,
                            confidence = c.confidence
                        )
                    },
                    suggested_actions = parsed.suggested_actions.orEmpty()
                )
                ParseResult.Success(normalized)
            }
        } catch (e: Exception) {
            ParseResult.Failure("Malformed structured response: ${e.message}")
        }
    }

    /** Lenient: an unknown or missing sourceType string maps to NOTE (advisory only). */
    private object LenientSourceTypeDeserializer : JsonDeserializer<SourceType> {
        override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): SourceType {
            if (!json.isJsonPrimitive) return SourceType.NOTE
            return runCatching { json.asString }
                .getOrNull()
                ?.let { name -> SourceType.entries.firstOrNull { it.name == name } }
                ?: SourceType.NOTE
        }
    }
}