package com.noteflowai.app.data.memory.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A named entity extracted from content (person, project, company, etc.).
 * Entities are extracted conservatively and require user confirmation before
 * being used in queries.
 */
@Entity(
    tableName = "entities",
    indices = [
        Index(value = ["type"]),
        Index(value = ["normalizedName"]),
        Index(value = ["createdAt"]),
        Index(value = ["confirmation"]),
        Index(value = ["canonicalName"])
    ]
)
data class Entity(
    @PrimaryKey
    val id: String,

    /** Type of entity. */
    val type: EntityType,

    /** Original canonical name as extracted. */
    val canonicalName: String,

    /** Additional names/aliases as JSON array string. */
    val aliasesJson: String? = null,

    /** Normalized name for deduplication (lowercase, trimmed). */
    val normalizedName: String,

    /** Unix epoch millis when this entity was created. */
    val createdAt: Long = System.currentTimeMillis(),

    /** Unix epoch millis when this entity was last updated. */
    val updatedAt: Long = System.currentTimeMillis(),

    /** Confidence score from extraction, 0.0-1.0. */
    val confidence: Float = 0.0f,

    /** True if the user has confirmed this entity. */
    val userConfirmed: Boolean = false,

    /** User confirmation state (guide §Phase 4); kept in sync with [userConfirmed]. */
    val confirmation: ConfirmationState = ConfirmationState.SUGGESTED
)
