package com.noteflowai.app.data.memory.repository

import android.content.Context
import com.noteflowai.app.data.memory.dao.MemoryRelationDao
import com.noteflowai.app.data.memory.db.MemoryDatabaseModule
import com.noteflowai.app.data.memory.model.MemoryRelation
import com.noteflowai.app.data.memory.model.RelationType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MemoryRelationRepository(context: Context) {

    private val dao: MemoryRelationDao = MemoryDatabaseModule.provideMemoryRelationDao(context)

    suspend fun insert(relation: MemoryRelation) = withContext(Dispatchers.IO) {
        dao.insert(relation)
    }

    suspend fun insertAll(relations: List<MemoryRelation>) = withContext(Dispatchers.IO) {
        dao.insertAll(relations)
    }

    /**
     * Relations touching [objectId] in either direction. Two indexed queries
     * unioned in memory: the single `fromId=:id OR toId=:id` query defeats
     * both composite indices and scans on every graph traversal.
     */
    suspend fun getByObjectId(objectId: String): List<MemoryRelation> = withContext(Dispatchers.IO) {
        (dao.getByFromId(objectId) + dao.getByToId(objectId)).distinctBy { it.id }
    }

    suspend fun getFromObject(fromType: String, fromId: String): List<MemoryRelation> = withContext(Dispatchers.IO) {
        dao.getFromObject(fromType, fromId)
    }

    suspend fun getToObject(toType: String, toId: String): List<MemoryRelation> = withContext(Dispatchers.IO) {
        dao.getToObject(toType, toId)
    }

    suspend fun getByRelationType(relationType: RelationType): List<MemoryRelation> = withContext(Dispatchers.IO) {
        dao.getByRelationType(relationType)
    }

    suspend fun deleteByObjectId(objectId: String) = withContext(Dispatchers.IO) {
        dao.deleteByObjectId(objectId)
    }

    suspend fun deleteBySourceSegmentId(sourceSegmentId: String) = withContext(Dispatchers.IO) {
        dao.deleteBySourceSegmentId(sourceSegmentId)
    }
}
