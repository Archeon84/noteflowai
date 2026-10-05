package com.noteflowai.app.data.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.noteflowai.app.data.memory.model.Entity
import com.noteflowai.app.data.memory.model.EntityType

@Dao
interface EntityDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: Entity)

    @Update
    suspend fun update(entity: Entity)

    @Query("SELECT * FROM entities WHERE id = :id")
    suspend fun getById(id: String): Entity?

    @Query("SELECT * FROM entities WHERE normalizedName = :normalizedName LIMIT 1")
    suspend fun getByNormalizedName(normalizedName: String): Entity?

    @Query("SELECT * FROM entities WHERE type = :type ORDER BY canonicalName ASC")
    suspend fun getByType(type: EntityType): List<Entity>

    @Query("SELECT * FROM entities ORDER BY type ASC, canonicalName ASC")
    suspend fun getAll(): List<Entity>

    @Query("SELECT * FROM entities WHERE userConfirmed = 0 ORDER BY confidence DESC")
    suspend fun getUnconfirmed(): List<Entity>

    @Query("SELECT * FROM entities WHERE userConfirmed = 1 ORDER BY canonicalName ASC")
    suspend fun getConfirmed(): List<Entity>

    @Query("SELECT * FROM entities WHERE canonicalName LIKE '%' || :query || '%' ESCAPE '\\' OR normalizedName LIKE '%' || :query || '%' ESCAPE '\\' ORDER BY confidence DESC LIMIT :limit")
    suspend fun search(query: String, limit: Int = 20): List<Entity>

    /**
     * LIKE search restricted to entities the user has not rejected, for retrieval.
     * Confirmation is stored as `.name` strings; the literal filter keeps rejected
     * entities out of chat/library results even at the query layer.
     */
    @Query("SELECT * FROM entities WHERE (canonicalName LIKE '%' || :query || '%' ESCAPE '\\' OR normalizedName LIKE '%' || :query || '%' ESCAPE '\\') AND confirmation != 'REJECTED' ORDER BY confidence DESC LIMIT :limit")
    suspend fun searchActive(query: String, limit: Int = 20): List<Entity>

    @Query("SELECT * FROM entities WHERE aliasesJson LIKE '%' || :query || '%' ESCAPE '\\' AND confirmation != 'REJECTED' ORDER BY confidence DESC LIMIT :limit")
    suspend fun searchActiveByAlias(query: String, limit: Int = 20): List<Entity>

    @Query("SELECT * FROM entities WHERE type = :type AND confirmation != 'REJECTED' ORDER BY canonicalName ASC")
    suspend fun getActiveByType(type: EntityType): List<Entity>

    @Query("DELETE FROM entities WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM entities")
    suspend fun totalCount(): Int

    /** Count of rejected entities, for the rebuild final report. */
    @Query("SELECT COUNT(*) FROM entities WHERE confirmation = 'REJECTED'")
    suspend fun countRejected(): Int

    @Query("DELETE FROM entities WHERE confirmation != 'CONFIRMED'")
    suspend fun clearUnconfirmed()

    @Query("DELETE FROM entities")
    suspend fun clearAll()
}
