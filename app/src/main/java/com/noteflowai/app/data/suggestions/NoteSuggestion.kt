package com.noteflowai.app.data.suggestions

/**
 * Typed suggestion for the note editor.
 */
sealed class NoteSuggestion {

    /** Suggest linking to an existing related note. */
    data class LinkSuggestion(
        val targetFileName: String,
        val targetTitle: String,
        val reason: String,
        val strength: Float
    ) : NoteSuggestion()

    /** Suggest merging with a similar note. */
    data class MergeSuggestion(
        val targetFileName: String,
        val targetTitle: String,
        val reason: String,
        val similarity: Float
    ) : NoteSuggestion()

    /** Suggest a title for the current note based on its content. */
    data class TitleSuggestion(
        val suggestedTitle: String,
        val confidence: Float
    ) : NoteSuggestion()

    /** Suggest adding a tag/category based on content. */
    data class TagSuggestion(
        val tag: String,
        val confidence: Float
    ) : NoteSuggestion()
}
