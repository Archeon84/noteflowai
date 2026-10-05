package com.noteflowai.app.data.memory.repository

import android.content.Context
import android.util.Log
import com.noteflowai.app.data.memory.dao.MemoryReviewItemDao
import com.noteflowai.app.data.memory.db.MemoryDatabase
import com.noteflowai.app.data.memory.model.MemoryReviewItem
import com.noteflowai.app.data.memory.model.ReviewItemStatus
import com.noteflowai.app.data.memory.model.ReviewItemType
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MemoryReviewRepository(
    private val db: MemoryDatabase
) {
    constructor(context: Context) : this(MemoryDatabase.getInstance(context))

    private val dao: MemoryReviewItemDao = db.memoryReviewItemDao()

    suspend fun insert(item: MemoryReviewItem) = withContext(Dispatchers.IO) {
        dao.insert(item)
    }

    suspend fun getById(id: String): MemoryReviewItem? = withContext(Dispatchers.IO) {
        dao.getById(id)
    }

    suspend fun getPending(): List<MemoryReviewItem> = withContext(Dispatchers.IO) {
        dao.getPending()
    }

    suspend fun getPendingByType(type: ReviewItemType): List<MemoryReviewItem> = withContext(Dispatchers.IO) {
        dao.getPendingByType(type)
    }

    suspend fun pendingCount(): Int = withContext(Dispatchers.IO) {
        dao.pendingCount()
    }

    /**
     * Accept a review item and confirm the underlying object atomically.
     *
     * Runs in a single transaction: an exception after the review row is
     * written no longer leaves a half-confirmed state (ACCEPTED review over
     * an unconfirmed object). Exceptions propagate to the caller instead of
     * being swallowed — silent success with lost data was the old behavior.
     */
    suspend fun accept(itemId: String) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val item = dao.getById(itemId) ?: return@withTransaction
            dao.update(item.copy(
                status = ReviewItemStatus.ACCEPTED,
                resolvedAt = System.currentTimeMillis()
            ))
            // Also confirm the underlying object
            when (item.type) {
                ReviewItemType.DECISION -> {
                    val decision = db.decisionDao().getById(item.referencedObjectId)
                    if (decision != null) {
                        db.decisionDao().update(decision.copy(
                            status = com.noteflowai.app.data.memory.model.DecisionStatus.CONFIRMED,
                            userConfirmed = true,
                            updatedAt = System.currentTimeMillis()
                        ))
                        val memObj = db.memoryObjectDao().getById(decision.memoryObjectId)
                        if (memObj != null) {
                            db.memoryObjectDao().update(memObj.copy(
                                status = com.noteflowai.app.data.memory.model.MemoryObjectStatus.CONFIRMED,
                                confirmedAt = System.currentTimeMillis()
                            ))
                        }
                    } else {
                        Log.w("MemoryReviewRepository", "Accept references missing decision: ${item.referencedObjectId}")
                    }
                }
                ReviewItemType.COMMITMENT -> {
                    val commitment = db.commitmentDao().getById(item.referencedObjectId)
                    if (commitment != null) {
                        db.commitmentDao().update(commitment.copy(
                            status = com.noteflowai.app.data.memory.model.CommitmentStatus.CONFIRMED,
                            userConfirmed = true,
                            updatedAt = System.currentTimeMillis()
                        ))
                        val memObj = db.memoryObjectDao().getById(commitment.memoryObjectId)
                        if (memObj != null) {
                            db.memoryObjectDao().update(memObj.copy(
                                status = com.noteflowai.app.data.memory.model.MemoryObjectStatus.CONFIRMED,
                                confirmedAt = System.currentTimeMillis()
                            ))
                        }
                    } else {
                        Log.w("MemoryReviewRepository", "Accept references missing commitment: ${item.referencedObjectId}")
                    }
                }
                ReviewItemType.ENTITY -> {
                    val entity = db.entityDao().getById(item.referencedObjectId)
                    if (entity != null) {
                        // Both columns: userConfirmed drives getUnconfirmed, while
                        // confirmation drives searchActive/getActiveByType. Setting
                        // only one splits the entity across the two query families.
                        db.entityDao().update(entity.copy(
                            confirmation = com.noteflowai.app.data.memory.model.ConfirmationState.CONFIRMED,
                            userConfirmed = true,
                            updatedAt = System.currentTimeMillis()
                        ))
                    } else {
                        Log.w("MemoryReviewRepository", "Accept references missing entity: ${item.referencedObjectId}")
                    }
                }
                else -> { /* RELATION types don't have a status to update */ }
            }
        }
    }

    suspend fun edit(itemId: String) = withContext(Dispatchers.IO) {
        val item = dao.getById(itemId) ?: return@withContext
        dao.update(item.copy(
            status = ReviewItemStatus.EDITED,
            resolvedAt = System.currentTimeMillis()
        ))
    }

    suspend fun ignore(itemId: String) = withContext(Dispatchers.IO) {
        val item = dao.getById(itemId) ?: return@withContext
        dao.update(item.copy(
            status = ReviewItemStatus.IGNORED,
            resolvedAt = System.currentTimeMillis()
        ))
    }

    suspend fun deleteById(id: String) = withContext(Dispatchers.IO) {
        dao.deleteById(id)
    }
}
