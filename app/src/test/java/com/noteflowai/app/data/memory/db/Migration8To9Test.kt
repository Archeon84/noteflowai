package com.noteflowai.app.data.memory.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MIGRATION_8_9 verification (Phase 4 schema repair).
 *
 * Simulates a pre-v9 install whose source_segments table predates the newer
 * columns, runs the migration, and proves: missing columns are added, existing
 * data survives, all v9 indices exist, and the migration is idempotent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration8To9Test {

    private fun openDb(): SupportSQLiteDatabase {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration
            .builder(context)
            .name(null)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(8) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int
                ) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    private fun columnNames(db: SupportSQLiteDatabase, table: String): Set<String> {
        val names = mutableSetOf<String>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIdx = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                names.add(cursor.getString(nameIdx))
            }
        }
        return names
    }

    private fun indexNames(db: SupportSQLiteDatabase, table: String): Set<String> {
        val names = mutableSetOf<String>()
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = '$table'"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                names.add(cursor.getString(0))
            }
        }
        return names
    }

    @Test
    fun `migration backfills columns preserves data and creates indices`() {
        val db = openDb()
        // Ancient source_segments: only the original columns.
        db.execSQL(
            """CREATE TABLE source_segments (
                id TEXT NOT NULL PRIMARY KEY,
                sourceId TEXT NOT NULL,
                sourceType TEXT NOT NULL,
                text TEXT NOT NULL,
                normalizedText TEXT NOT NULL,
                createdAt INTEGER NOT NULL DEFAULT 0,
                updatedAt INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL(
            "INSERT INTO source_segments VALUES ('seg1', 'note.md', 'NOTE', 'hello', 'hello', 1, 2)"
        )
        // Stub tables that receive indices (present in any real v8 install).
        db.execSQL("CREATE TABLE memory_objects (id TEXT NOT NULL PRIMARY KEY, sourceSegmentId TEXT, normalizedStatement TEXT)")
        db.execSQL("CREATE TABLE decisions (id TEXT NOT NULL PRIMARY KEY, reviewAt INTEGER)")
        db.execSQL("CREATE TABLE commitments (id TEXT NOT NULL PRIMARY KEY, completedAt INTEGER, createdAt INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE conflicts (id TEXT NOT NULL PRIMARY KEY, objectIds TEXT NOT NULL)")
        db.execSQL("CREATE TABLE answer_citations (id TEXT NOT NULL PRIMARY KEY, answerId TEXT NOT NULL, claimIndex INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE processing_status (sourceId TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE timeline_entries (id TEXT NOT NULL PRIMARY KEY, sourceSegmentId TEXT NOT NULL)")
        db.execSQL("CREATE TABLE entities (id TEXT NOT NULL PRIMARY KEY, confirmation TEXT, canonicalName TEXT NOT NULL)")
        db.execSQL("CREATE TABLE entity_mentions (entityId TEXT NOT NULL, sourceSegmentId TEXT NOT NULL, confirmation TEXT, PRIMARY KEY(entityId, sourceSegmentId))")
        db.execSQL("CREATE TABLE memory_relations (id TEXT NOT NULL PRIMARY KEY, fromId TEXT NOT NULL, toId TEXT NOT NULL)")

        MemoryDatabase.MIGRATION_8_9.migrate(db)

        // All v9 columns present...
        val columns = columnNames(db, "source_segments")
        for (expected in listOf(
            "id", "sourceId", "sourceType", "text", "normalizedText",
            "startMs", "endMs", "pageNumber", "blockId", "url", "speaker",
            "language", "transcriptionEngine", "confidence", "createdAt",
            "updatedAt", "isOriginalContent", "parentSegmentId", "metadataJson"
        )) {
            assertTrue("missing column $expected", expected in columns)
        }
        // ...old row intact with defaults applied.
        db.query("SELECT text, createdAt, isOriginalContent FROM source_segments WHERE id = 'seg1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("hello", cursor.getString(0))
            assertEquals(1L, cursor.getLong(1))
            assertEquals(1, cursor.getInt(2))
        }
        // Indices created.
        assertTrue(
            "index_memory_objects_sourceSegmentId_normalizedStatement" in
                indexNames(db, "memory_objects")
        )
        assertTrue("index_decisions_reviewAt" in indexNames(db, "decisions"))
        assertTrue("index_commitments_completedAt" in indexNames(db, "commitments"))
        assertTrue("index_conflicts_objectIds" in indexNames(db, "conflicts"))
        assertTrue(
            "index_answer_citations_answerId_claimIndex" in indexNames(db, "answer_citations")
        )
        assertTrue("index_processing_status_updatedAt" in indexNames(db, "processing_status"))
        assertTrue("index_timeline_entries_sourceSegmentId" in indexNames(db, "timeline_entries"))
        assertTrue("index_entities_confirmation" in indexNames(db, "entities"))
        assertTrue("index_entity_mentions_confirmation" in indexNames(db, "entity_mentions"))
        assertTrue("index_memory_relations_fromId" in indexNames(db, "memory_relations"))

        // Idempotent: second run is a no-op, not a crash.
        MemoryDatabase.MIGRATION_8_9.migrate(db)
        assertEquals(1, columnNames(db, "source_segments").count { it == "speaker" })

        db.close()
    }
}
