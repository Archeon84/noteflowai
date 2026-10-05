package com.noteflowai.app.data.suggestions

import com.noteflowai.app.data.NoteFile
import com.noteflowai.app.data.concept.ConceptExtractor
import com.noteflowai.app.data.concept.ConceptGraphRepository
import com.noteflowai.app.data.graph.NoteGraphRepository
import com.noteflowai.app.data.noteDisplayTitle
import com.noteflowai.app.data.search.NoteSearchIndex

/**
 * Analyzes a note being edited and produces smart suggestions:
 * related notes to link, merge candidates, title ideas, and tag suggestions.
 */
class NoteSuggestionEngine(
    private val conceptGraph: ConceptGraphRepository,
    private val noteGraph: NoteGraphRepository,
    private val searchIndex: NoteSearchIndex,
    private val conceptExtractor: ConceptExtractor
) {

    companion object {
        private const val MAX_LINK_SUGGESTIONS = 3
        private const val MAX_MERGE_SUGGESTIONS = 2
        private const val MERGE_SIMILARITY_THRESHOLD = 0.6f
        private const val MIN_CONTENT_LENGTH = 20

        // Regexes hoisted so they are compiled once, not per call.
        private val MARKDOWN_INLINE = Regex("[*_`~]")
        private val MARKDOWN_LIST_PREFIX = Regex("^[-*>\\s]+")
        private val NON_ALNUM = Regex("[^a-z0-9\\s]")
        private val WHITESPACE = Regex("\\s+")
    }

    /**
     * Analyze the given note content and return suggestions.
     * Fast enough to run on content changes with debouncing.
     */
    fun analyze(
        noteFileName: String,
        content: String,
        tags: List<String>,
        allNotes: List<NoteFile> = emptyList()
    ): List<NoteSuggestion> {
        if (content.length < MIN_CONTENT_LENGTH) return emptyList()

        val suggestions = mutableListOf<NoteSuggestion>()

        // 1. Title suggestions
        suggestions.addAll(suggestTitle(content))

        // 2. Tag suggestions (only if current tags are sparse)
        if (tags.size < 3) {
            suggestions.addAll(suggestTags(content, tags))
        }

        // 3. Related note links via concept overlap
        suggestions.addAll(suggestLinks(noteFileName, content))

        // 4. Merge candidates via content similarity
        if (allNotes.isNotEmpty()) {
            suggestions.addAll(suggestMerges(noteFileName, content, allNotes))
        }

        return suggestions
    }

    /**
     * Suggest a title based on the first meaningful line of content.
     */
    private fun suggestTitle(content: String): List<NoteSuggestion.TitleSuggestion> {
        // Take first non-empty line that isn't a markdown heading
        val firstLine = content.lines()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") && it.length in 5..80 }
            ?: return emptyList()

        // Clean up: remove markdown formatting
        val cleanTitle = firstLine
            .replace(MARKDOWN_INLINE, "")
            .replace(MARKDOWN_LIST_PREFIX, "")
            .take(60)

        if (cleanTitle.length < 5) return emptyList()

        return listOf(
            NoteSuggestion.TitleSuggestion(
                suggestedTitle = cleanTitle,
                confidence = 0.7f
            )
        )
    }

    /**
     * Suggest tags based on extracted concepts not already in the tag list.
     */
    private fun suggestTags(content: String, existingTags: List<String>): List<NoteSuggestion.TagSuggestion> {
        val existingLower = existingTags.map { it.lowercase() }.toSet()
        val concepts = conceptExtractor.extractFromContent(content)

        return concepts
            .filter { it.displayForm.lowercase() !in existingLower }
            .take(3)
            .map { concept ->
                NoteSuggestion.TagSuggestion(
                    tag = concept.displayForm,
                    confidence = concept.importance
                )
            }
    }

    /**
     * Suggest linking to related notes via concept graph connections.
     */
    private fun suggestLinks(
        noteFileName: String,
        content: String
    ): List<NoteSuggestion.LinkSuggestion> {
        // Extract concepts from the current note
        val currentConcepts = conceptExtractor.extractFromContent(content)
            .map { it.canonicalForm }

        if (currentConcepts.isEmpty()) return emptyList()

        // Find notes connected via concept graph
        val relatedNotes = mutableMapOf<String, Float>()  // fileName -> max strength

        for (concept in currentConcepts.take(10)) {
            val conceptNotes = conceptGraph.getNotesForConcept(concept)
            for (relatedNote in conceptNotes) {
                if (relatedNote == noteFileName) continue
                val currentStrength = relatedNotes[relatedNote] ?: 0f
                relatedNotes[relatedNote] = maxOf(currentStrength, 0.5f)
            }
        }

        // Also check note graph connections
        val noteConnections = noteGraph.getConnectedNotes(noteFileName)
        for ((connectedNote, strength) in noteConnections) {
            val currentStrength = relatedNotes[connectedNote] ?: 0f
            relatedNotes[connectedNote] = maxOf(currentStrength, strength)
        }

        return relatedNotes.entries
            .sortedByDescending { it.value }
            .take(MAX_LINK_SUGGESTIONS)
            .map { (fileName, strength) ->
                NoteSuggestion.LinkSuggestion(
                    targetFileName = fileName,
                    targetTitle = fileName.noteDisplayTitle(),
                    reason = "Shares concepts with current note",
                    strength = strength
                )
            }
    }

    /**
     * Suggest merging when content is highly similar to an existing note.
     */
    private fun suggestMerges(
        noteFileName: String,
        content: String,
        allNotes: List<NoteFile>
    ): List<NoteSuggestion.MergeSuggestion> {
        val results = mutableListOf<NoteSuggestion.MergeSuggestion>()

        for (note in allNotes) {
            if (note.fileName == noteFileName) continue
            if (note.content.isEmpty()) continue

            // Quick similarity check: shared unique words ratio
            val currentWords = extractKeyWords(content)
            val noteWords = extractKeyWords(note.content)

            if (currentWords.isEmpty() || noteWords.isEmpty()) continue

            val intersection = currentWords.intersect(noteWords)
            val union = currentWords.union(noteWords)
            val jaccard = intersection.size.toFloat() / union.size

            if (jaccard >= MERGE_SIMILARITY_THRESHOLD) {
                results.add(
                    NoteSuggestion.MergeSuggestion(
                        targetFileName = note.fileName,
                        targetTitle = note.fileName.noteDisplayTitle(),
                        reason = "${(jaccard * 100).toInt()}% word overlap",
                        similarity = jaccard
                    )
                )
            }
        }

        return results.sortedByDescending { it.similarity }.take(MAX_MERGE_SUGGESTIONS)
    }

    /**
     * Extract meaningful words (remove stopwords, short words).
     */
    private fun extractKeyWords(text: String): Set<String> {
        val stopwords = setOf(
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "could",
            "should", "may", "might", "shall", "can", "to", "of", "in", "for",
            "on", "with", "at", "by", "from", "as", "into", "through", "during",
            "before", "after", "above", "below", "between", "and", "but", "or",
            "not", "no", "nor", "so", "yet", "both", "either", "neither", "each",
            "every", "all", "any", "few", "more", "most", "other", "some", "such",
            "than", "too", "very", "just", "also", "now", "then", "here", "there",
            "when", "where", "how", "what", "which", "who", "whom", "this", "that",
            "these", "those", "it", "its", "i", "me", "my", "we", "our", "you",
            "your", "he", "him", "his", "she", "her", "they", "them", "their"
        )

        return text.lowercase()
            .replace(NON_ALNUM, " ")
            .split(WHITESPACE)
            .filter { it.length >= 4 && it !in stopwords }
            .toSet()
    }
}
