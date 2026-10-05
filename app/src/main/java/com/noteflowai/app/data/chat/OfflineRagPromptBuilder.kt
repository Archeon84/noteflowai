package com.noteflowai.app.data.chat

/**
 * Builds compact, high-precision system prompts with a strict Rule RAG system
 * tailored specifically for on-device offline LLMs (e.g. Qwen3-1.7B / Llama).
 */
object OfflineRagPromptBuilder {

    data class Excerpt(
        val index: Int,
        val title: String,
        val text: String,
        val createdDate: String? = null,
        val modifiedDate: String? = null
    )

    /**
     * Builds the complete system prompt for the offline LLM with temporal context,
     * evidence excerpts, and strict RAG operational rules.
     */
    fun buildSystemPrompt(
        basePrompt: String,
        temporalContext: String = TemporalContextHelper.getSystemTemporalContext(),
        excerpts: List<Excerpt> = emptyList()
    ): String {
        return buildString {
            // 1. Temporal Reference Anchor
            appendLine(temporalContext)
            appendLine()

            // 2. Base Persona / Instructions
            if (basePrompt.isNotBlank()) {
                appendLine(basePrompt)
                appendLine()
            } else {
                appendLine("You are NoteFlow AI, an intelligent personal knowledge assistant running privately on-device.")
                appendLine()
            }

            // 3. Retrieved Evidence Excerpts
            appendLine("=== RETRIEVED NOTE EVIDENCE ===")
            if (excerpts.isEmpty()) {
                appendLine("No relevant note excerpts found for this query.")
            } else {
                excerpts.forEach { item ->
                    val dateHeader = buildDateHeader(item.createdDate, item.modifiedDate)
                    appendLine("[${item.index}] \"${item.title}\"$dateHeader")
                    appendLine(item.text.trim())
                    appendLine()
                }
            }

            // 4. Strict Offline RAG Rule System
            appendLine("=== STRICT OFFLINE RAG RULES ===")
            appendLine("1. FACTUAL GROUNDING: Answer strictly using the RETRIEVED NOTE EVIDENCE above. Never fabricate facts, dates, or note contents.")
            appendLine("2. TEMPORAL RESOLUTION: Use the [TEMPORAL CONTEXT] to resolve relative time expressions (e.g., 'yesterday', 'last month', 'tomorrow'). Note timestamps indicate when information was recorded.")
            appendLine("3. CONFLICT HANDLING: If notes contain contradictory information, prioritize the note with the more recent 'Modified' or 'Created' date and mention the update.")
            appendLine("4. CITATION SYNTAX:")
            appendLine("   - You MUST place citation tags like [1] or [2] immediately after each supported statement.")
            appendLine("   - Example: \"The project kick-off is on Friday [1] and requires 3 engineers [2].\"")
            appendLine("5. HONEST REFUSAL: If the retrieved notes do not contain the answer, clearly state: \"Based on your notes, I don't have information about [topic].\" Do not invent details.")
            appendLine("6. RICH MARKDOWN FORMATTING:")
            appendLine("   - Always structure responses using Markdown headings (## Section Title) to separate distinct topics.")
            appendLine("   - Use bullet points (- ) for lists, details, takeaways, or actionable items.")
            appendLine("   - Use **bold** for key terms, metrics, dates, and names.")
            appendLine("   - Keep paragraphs short (1-2 sentences) for mobile display. Never output a single solid wall of plain text.")
        }.trim()
    }

    /**
     * Reusable formatting & anti-hallucination directives injected into on-device LLM prompts.
     */
    fun getOfflineFormattingAndGroundingDirectives(): String = """
=== RESPONSE FORMATTING & FACTUAL GROUNDING ===
1. FACTUAL GROUNDING: Answer strictly and only from the retrieved note evidence. Never fabricate facts, dates, names, or note contents.
2. HONEST REFUSAL: If the retrieved notes do not contain the answer, reply: "Based on your notes, I don't have information about [topic]." Do not invent details.
3. STRUCTURED HEADINGS: Organize your response with Markdown headings (## Section Title) to separate distinct sections.
4. BULLET POINTS: Use bullet points (- ) for lists, details, takeaways, or actionable items.
5. HIGHLIGHT KEY TERMS: Use **bold** for key terms, metrics, dates, and names.
6. CONCISE & READABLE: Keep paragraphs short (1-2 sentences) for mobile display. Never output a solid wall of plain text.
7. CITATIONS: Place citation tags like [1] or [2] immediately after each supported statement.
""".trim()

    /**
     * Formatting and grounding directives for on-device LLM when answering in
     * "Talk with AI Assistant" mode with web search results.
     */
    fun getOfflineAssistantWebSearchDirectives(): String = """
=== RESPONSE FORMATTING & WEB EVIDENCE GROUNDING ===
1. WEB GROUNDING: Answer using the retrieved web search results above. If the web results do not contain enough information, supplement using your general knowledge.
2. CITATIONS: Place citation tags like [1] or [2] immediately after each supported statement derived from the corresponding web search result. Never fabricate web citations. Do NOT add raw URLs to the response text.
3. STRUCTURED HEADINGS: Organize your response with Markdown headings (## Section Title) to separate distinct sections.
4. BULLET POINTS: Use bullet points (- ) for lists, details, takeaways, or actionable items.
5. HIGHLIGHT KEY TERMS: Use **bold** for key terms, metrics, dates, and names.
6. CONCISE & READABLE: Keep paragraphs short (1-2 sentences) for mobile display. Never output a solid wall of plain text.
7. NO NOTE REFUSAL: Do NOT state that information is missing from notes; you are an AI assistant answering with web search and general knowledge.
""".trim()

    /**
     * Formatting directives for on-device LLM when answering in
     * "Talk with AI Assistant" mode purely using general knowledge (no notes, no web search).
     */
    fun getOfflineGeneralKnowledgeDirectives(): String = """
=== RESPONSE FORMATTING & GENERAL KNOWLEDGE ===
1. GENERAL KNOWLEDGE: Answer the user's question accurately, helpfully, and concisely using your own general knowledge.
2. NO CITATIONS: Do NOT include citation tags or markers (such as [1], [2]) in your response.
3. NO NOTE REFUSAL: Do NOT state that information is missing from notes or refuse based on notes. You are an AI assistant answering directly from your general knowledge.
4. STRUCTURED HEADINGS: Organize your response with Markdown headings (## Section Title) to separate distinct sections.
5. BULLET POINTS: Use bullet points (- ) for lists, details, takeaways, or actionable items.
6. HIGHLIGHT KEY TERMS: Use **bold** for key terms, metrics, dates, and names.
7. CONCISE & READABLE: Keep paragraphs short (1-2 sentences) for mobile display. Never output a solid wall of plain text.
""".trim()


    private fun buildDateHeader(createdDate: String?, modifiedDate: String?): String {
        return when {
            !createdDate.isNullOrBlank() && !modifiedDate.isNullOrBlank() -> " (Created: $createdDate, Modified: $modifiedDate):"
            !modifiedDate.isNullOrBlank() -> " (Modified: $modifiedDate):"
            !createdDate.isNullOrBlank() -> " (Created: $createdDate):"
            else -> ":"
        }
    }

    private val STOP_WORDS = setOf(
        "the", "and", "is", "in", "it", "of", "to", "a", "for", "with", "on", "as",
        "at", "by", "from", "an", "be", "this", "that", "are", "or", "was", "will",
        "your", "my", "have", "has", "not", "but", "about", "can", "also"
    )

    /**
     * Post-hoc grounding fallback: if on-device model did not emit [N] citation tags,
     * detect sentences with high keyword overlap against retrieved sources and append
     * appropriate [N] citation markers to preserve clickable deep links.
     */
    fun attachPostHocCitations(text: String, ragSources: List<RagSource>): String {
        if (text.isBlank() || ragSources.isEmpty()) return text
        if (Regex("\\[\\d+\\]").containsMatchIn(text)) return text

        val lines = text.split("\n")
        val processedLines = lines.map { line ->
            if (line.isBlank() || line.startsWith("#") || line.startsWith("-") || line.startsWith("*")) {
                line
            } else {
                // Split line into sentences
                val sentences = line.split(Regex("(?<=[.!?])\\s+"))
                sentences.joinToString(" ") { sentence ->
                    val cleanSentence = sentence.trim()
                    if (cleanSentence.length < 15) return@joinToString sentence

                    val words = cleanSentence.lowercase()
                        .replace(Regex("[^a-z0-9\\s]"), "")
                        .split(Regex("\\s+"))
                        .filter { it.length >= 3 && it !in STOP_WORDS }

                    if (words.size < 3) return@joinToString sentence

                    val lowerSentence = cleanSentence.lowercase()
                    // Never attach citations to refusal or lack-of-evidence statements
                    if (lowerSentence.contains("don't have information") ||
                        lowerSentence.contains("do not have information") ||
                        lowerSentence.contains("no information") ||
                        lowerSentence.contains("couldn't find") ||
                        lowerSentence.contains("could not find") ||
                        lowerSentence.contains("not found in your notes") ||
                        lowerSentence.contains("not mentioned in your notes") ||
                        lowerSentence.contains("no relevant notes") ||
                        lowerSentence.contains("no notes found") ||
                        lowerSentence.contains("based on your notes, i don't")
                    ) {
                        return@joinToString sentence
                    }

                    // Find source with best token overlap
                    var bestSourceIndex = -1
                    var maxOverlapRatio = 0.0

                    ragSources.forEachIndexed { idx, source ->
                        val sourceContent = (source.noteTitle + " " + source.excerpt).lowercase()
                        var matches = 0
                        words.forEach { w ->
                            if (sourceContent.contains(w)) matches++
                        }
                        val ratio = matches.toDouble() / words.size.toDouble()
                        if (ratio > maxOverlapRatio && ratio >= 0.40) {
                            maxOverlapRatio = ratio
                            bestSourceIndex = idx
                        }
                    }

                    if (bestSourceIndex >= 0) {
                        val citationTag = " [${bestSourceIndex + 1}]"
                        if (cleanSentence.endsWith(".") || cleanSentence.endsWith("!") || cleanSentence.endsWith("?")) {
                            val punctuation = cleanSentence.takeLast(1)
                            cleanSentence.dropLast(1).trimEnd() + citationTag + punctuation
                        } else {
                            "$cleanSentence$citationTag"
                        }
                    } else {
                        sentence
                    }
                }
            }
        }
        return processedLines.joinToString("\n")
    }

    const val DEFAULT_LOCAL_PROMPT_MAX_CHARS = 10_000

    /**
     * Clamps an on-device LLM prompt to prevent exceeding the model's physical token capacity.
     * Preserves system instructions/directives at the head and latest user query at the tail,
     * while trimming excess middle context with a clean truncation note.
     */
    fun clampPromptToSafeBudget(prompt: String, maxChars: Int = DEFAULT_LOCAL_PROMPT_MAX_CHARS): String {
        if (prompt.length <= maxChars) return prompt
        val headChars = (maxChars * 0.40).toInt()
        val tailChars = (maxChars * 0.55).toInt()
        return prompt.take(headChars) + "\n\n[...prior note context trimmed to fit on-device memory...]\n\n" + prompt.takeLast(tailChars)
    }
}

