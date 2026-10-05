package com.noteflowai.app.data.digest

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * A daily brief assembled from the user's notes, concepts, and recall data.
 */
data class DailyDigest(
    val generatedAt: Long = System.currentTimeMillis(),
    val recentActivity: List<RecentItem> = emptyList(),
    val openQuestions: List<OpenQuestion> = emptyList(),
    val staleConcepts: List<StaleItem> = emptyList(),
    val hiddenConnections: List<ConnectionItem> = emptyList()
) {
    data class RecentItem(
        val noteTitle: String,
        val concepts: List<String>,
        val summary: String
    )

    data class OpenQuestion(
        val concept: String,
        val detail: String,
        val fromNote: String
    )

    data class StaleItem(
        val concept: String,
        val daysSinceLastSeen: Long,
        val relatedNotes: List<String>
    )

    data class ConnectionItem(
        val conceptA: String,
        val conceptB: String,
        val strength: Float,
        val reason: String
    )

    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): DailyDigest = Gson().fromJson(json, DailyDigest::class.java)
    }
}
