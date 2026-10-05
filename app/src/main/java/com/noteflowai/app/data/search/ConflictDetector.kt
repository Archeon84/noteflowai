package com.noteflowai.app.data.search

import com.noteflowai.app.data.concept.ConceptGraphRepository
import com.noteflowai.app.data.noteDisplayTitle
import com.noteflowai.app.data.temporal.TemporalIndex

/**
 * Detects contradicting notes about the same concept by analyzing
 * context windows from TemporalIndex appearances.
 */
class ConflictDetector(
    private val temporalIndex: TemporalIndex,
    private val conceptGraph: ConceptGraphRepository
) {

    data class ConflictResult(
        val concept: String,
        val noteA: String,
        val noteB: String,
        val contextA: String,
        val contextB: String,
        val reason: String
    )

    // Simple sentiment/negation lexicon for offline conflict detection
    private val positiveSignals = setOf(
        "love", "great", "excellent", "amazing", "perfect", "best", "enjoy",
        "happy", "wonderful", "fantastic", "brilliant", "recommend", "pro",
        "advantage", "benefit", "positive", "yes", "agree", "support"
    )

    private val negativeSignals = setOf(
        "hate", "terrible", "awful", "worst", "horrible", "bad", "poor",
        "disappointing", "frustrating", "annoying", "con", "disadvantage",
        "negative", "disagree", "oppose", "avoid", "problem", "issue",
        "fail", "broken", "useless", "waste", "wrong"
    )

    private val negationWords = setOf("not", "no", "never", "don't", "doesn't", "isn't", "wasn't")

    // Tokenizer regexes hoisted so they are compiled once, not per call.
    private val NON_ALNUM = Regex("[^a-z\\s]")
    private val WHITESPACE = Regex("\\s+")

    /**
     * Find concepts with contradicting notes.
     * Only checks concepts with 2+ notes (need at least 2 to conflict).
     */
    fun findConflicts(maxResults: Int = 3): List<ConflictResult> {
        val results = mutableListOf<ConflictResult>()

        // Get all concepts from the graph and filter for those with 2+ notes
        val conceptsWithMultipleNotes = conceptGraph.getAllConcepts().filter { it.noteCount >= 2 }

        for (concept in conceptsWithMultipleNotes) {
            if (results.size >= maxResults) break

            // Get notes for this concept from the concept graph
            val notesForConcept = conceptGraph.getNotesForConcept(concept.canonicalForm)
            if (notesForConcept.size < 2) continue

            val evolution = temporalIndex.getEvolution(concept.canonicalForm) ?: continue
            val appearances = evolution.appearances
            if (appearances.size < 2) continue

            // Sentiment is computed once per appearance: the old code
            // recomputed it per pair (O(pairs) redundant lexicon scans).
            val sentimentByIndex = appearances.indices.associateWith { idx ->
                computeSentimentScore(appearances[idx].context)
            }

            // Compare each pair of appearances for opposing signals
            outer@ for (i in appearances.indices) {
                for (j in i + 1 until appearances.size) {
                    val a = appearances[i]
                    val b = appearances[j]

                    // Skip if same note
                    if (a.noteFileName == b.noteFileName) continue

                    val scoreA = sentimentByIndex.getValue(i)
                    val scoreB = sentimentByIndex.getValue(j)

                    // Conflict: one positive, one negative, with enough signal.
                    // Inclusive bounds: a single signal word scores exactly
                    // ±0.3, so strict inequalities needed two words and never
                    // fired on minimal-but-clear oppositions.
                    if (scoreA >= 0.3f && scoreB <= -0.3f) {
                        results.add(
                            ConflictResult(
                                concept = concept.displayForm,
                                noteA = a.noteFileName,
                                noteB = b.noteFileName,
                                contextA = a.context.take(100),
                                contextB = b.context.take(100),
                                reason = "Positive in ${a.noteFileName.noteDisplayTitle()}, negative in ${b.noteFileName.noteDisplayTitle()}"
                            )
                        )
                        break@outer  // one conflict per concept is enough
                    } else if (scoreA <= -0.3f && scoreB >= 0.3f) {
                        results.add(
                            ConflictResult(
                                concept = concept.displayForm,
                                noteA = a.noteFileName,
                                noteB = b.noteFileName,
                                contextA = a.context.take(100),
                                contextB = b.context.take(100),
                                reason = "Negative in ${a.noteFileName.noteDisplayTitle()}, positive in ${b.noteFileName.noteDisplayTitle()}"
                            )
                        )
                        break@outer
                    }
                    if (results.size >= maxResults) break@outer
                }
            }
        }

        return results
    }

    /**
     * Simple lexicon-based sentiment score for a text context.
     * Returns a value in [-1.0, 1.0].
     */
    private fun computeSentimentScore(text: String): Float {
        val words = text.lowercase(java.util.Locale.ROOT)
            .replace(NON_ALNUM, " ")
            .split(WHITESPACE)
            .filter { it.isNotEmpty() }

        var score = 0f
        var negated = false

        for (word in words) {
            if (word in negationWords) {
                negated = true
                continue
            }
            if (word in positiveSignals) {
                score += if (negated) -0.3f else 0.3f
                negated = false
            } else if (word in negativeSignals) {
                score += if (negated) 0.2f else -0.3f
                negated = false
            } else {
                negated = false
            }
        }

        return score.coerceIn(-1f, 1f)
    }
}
