package com.noteflowai.app.data.rag

import com.noteflowai.app.data.chat.TemporalContextHelper
import kotlin.math.ceil

class PromptBuilder {

    data class RagPrompt(
        val system: String,
        val contextText: String,
        val userQuery: String,
    ) {
        fun toChatMlPrompt(): String = buildString {
            append("<|im_start|>system\n")
            append(system)
            if (contextText.isNotBlank()) {
                append("\n\n=== RETRIEVED NOTE EVIDENCE ===\n")
                append(contextText)
            }
            append("\n<|im_end|>\n")
            append("<|im_start|>user\n")
            append(userQuery)
            append("\n<|im_end|>\n")
            append("<|im_start|>assistant\n<think>\n</think>\n")
        }
    }

    private fun estimateTokens(s: String): Int = ceil(s.length / 4.0).toInt()

    fun buildRagPrompt(
        query: String,
        chunks: List<Chunk>,
        maxContextTokens: Int = 20000,
        temporalContext: String = TemporalContextHelper.getSystemTemporalContext()
    ): RagPrompt {
        val system = buildString {
            appendLine(temporalContext)
            appendLine()
            appendLine("You are NoteFlow AI, an intelligent personal knowledge assistant running on-device.")
            appendLine("Answer questions accurately using ONLY the retrieved note evidence below.")
            appendLine("STRICT RULES:")
            appendLine("1. FACTUAL GROUNDING: Rely strictly on the note chunks. Do not hallucinate or invent facts.")
            appendLine("2. CITATIONS: Cite your sources using the note title (e.g. \"According to your note 'Project Plan'...\").")
            appendLine("3. HONEST REFUSAL: If the provided notes do not contain the answer, say: \"Based on your notes, I don't have information about [topic].\"")
            appendLine("4. CONCISE & STRUCTURED: Provide clear, concise, and structured answers.")
        }.trim()

        val sb = StringBuilder()
        var usedTokens = 0

        for ((index, chunk) in chunks.withIndex()) {
            val dateHeader = when {
                chunk.createdDate.isNotBlank() && chunk.modifiedDate.isNotBlank() ->
                    " (Created: ${chunk.createdDate}, Modified: ${chunk.modifiedDate})"
                chunk.modifiedDate.isNotBlank() -> " (Modified: ${chunk.modifiedDate})"
                chunk.createdDate.isNotBlank() -> " (Created: ${chunk.createdDate})"
                else -> ""
            }
            val header = "[${index + 1}] Note: \"${chunk.title}\"$dateHeader\n"
            val chunkTokens = estimateTokens(header) + estimateTokens(chunk.text)
            if (usedTokens + chunkTokens > maxContextTokens) break

            sb.append(header)
            sb.append(chunk.text.trim())
            sb.append("\n\n")
            usedTokens += chunkTokens
        }

        return RagPrompt(
            system = system,
            contextText = sb.toString().trim(),
            userQuery = query,
        )
    }
}
