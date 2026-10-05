package com.noteflowai.app.data.memory.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An item in the Memory Inbox that requires user review.
 * Created when the AI detects possible decisions, commitments, questions,
 * entities, or conflicts. The user must confirm, edit, or ignore each item.
 */
@Entity(
    tableName = "memory_review_items",
    indices = [
        Index(value = ["status"]),
        Index(value = ["type"]),
        Index(value = ["createdAt"])
    ]
)
data class MemoryReviewItem(
    @PrimaryKey
    val id: String,

    /** Type of review item. */
    val type: ReviewItemType,

    /** ID of the referenced object (MemoryObject, Entity, etc.). */
    val referencedObjectId: String,

    /** Explanation of why this item needs review. */
    val reason: String,

    /** Current review status. */
    val status: ReviewItemStatus = ReviewItemStatus.PENDING,

    /** Unix epoch millis when created. */
    val createdAt: Long = System.currentTimeMillis(),

    /** Unix epoch millis when resolved (nullable). */
    val resolvedAt: Long? = null
)
