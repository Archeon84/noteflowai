package com.noteflowai.app.data.rag

class RetrievalService(private val chunks: List<Chunk>) {

    /**
     * Retrieve the top-k most relevant chunks for a user query.
     *
     * Returns an empty list on no match: falling back to the first k chunks
     * fed the LLM irrelevant "evidence" and defeated downstream refusal
     * logic, which expects empty when nothing matches.
     */
    fun retrieve(query: String, k: Int = 8): List<Chunk> {
        val qTerms = query
            .lowercase(java.util.Locale.ROOT)
            .split(Regex("\\W+"))
            .filter { it.length >= 2 }

        if (qTerms.isEmpty()) return emptyList()

        val scored = chunks.map { chunk ->
            val text = chunk.text.lowercase(java.util.Locale.ROOT)
            val title = chunk.title.lowercase(java.util.Locale.ROOT)

            var score = 0f
            for (term in qTerms) {
                if (title.contains(term)) {
                    score += 3f // Boost title matches
                }
                if (text.contains(term)) {
                    score += 1f
                }
            }
            Pair(chunk, score)
        }

        return scored
            .filter { it.second > 0f }
            .sortedByDescending { it.second }
            .take(k)
            .map { it.first }
    }
}
