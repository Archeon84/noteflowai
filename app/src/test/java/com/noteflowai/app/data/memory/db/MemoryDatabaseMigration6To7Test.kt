package com.noteflowai.app.data.memory.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Migration 6 -> 7 (Phase 4: entity & timeline correction).
 *
 * Adds the durable `confirmation` column to `entities` and `entity_mentions` (backfilling
 * legacy `userConfirmed=1` rows to CONFIRMED) and creates the `timeline_entries` table with
 * its indexes. Existing data must survive unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryDatabaseMigration6To7Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MemoryDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun `migration 6 to 7 backfills confirmation from userConfirmed and preserves data`() {
        // Create DB at version 6 using the exported schema.
        helper.createDatabase(TEST_DB, 6).apply {
            execSQL(
                """
                INSERT INTO entities
                    (id, type, canonicalName, aliasesJson, normalizedName, createdAt, updatedAt,
                     confidence, userConfirmed)
                VALUES ('e_legacy', 'PERSON', 'Alice', '["Al"]', 'alice', 1000, 1000, 0.8, 1)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO entities
                    (id, type, canonicalName, aliasesJson, normalizedName, createdAt, updatedAt,
                     confidence, userConfirmed)
                VALUES ('e_pending', 'PERSON', 'Bob', NULL, 'bob', 1000, 1000, 0.6, 0)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO entity_mentions
                    (entityId, sourceSegmentId, mentionText, confidence, createdAt)
                VALUES ('e_legacy', 'seg_1', 'Alice', 0.8, 1000)
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            7,
            true,
            MemoryDatabase.MIGRATION_6_7
        )

        // userConfirmed=1 rows are backfilled to CONFIRMED; the unconfirmed one stays SUGGESTED.
        val confirmedCursor = db.query(
            "SELECT confirmation FROM entities WHERE id = ?",
            arrayOf("e_legacy")
        )
        confirmedCursor.use {
            assertTrue(it.moveToFirst())
            assertEquals("CONFIRMED", it.getString(0))
        }
        val pendingCursor = db.query(
            "SELECT confirmation FROM entities WHERE id = ?",
            arrayOf("e_pending")
        )
        pendingCursor.use {
            assertTrue(it.moveToFirst())
            assertEquals("SUGGESTED", it.getString(0))
        }

        // Mentions get a default SUGGESTED confirmation.
        val mentionCursor = db.query(
            "SELECT confirmation FROM entity_mentions WHERE entityId = ?",
            arrayOf("e_legacy")
        )
        mentionCursor.use {
            assertTrue(it.moveToFirst())
            assertEquals("SUGGESTED", it.getString(0))
        }

        // Original data survived.
        val nameCursor = db.query(
            "SELECT canonicalName FROM entities WHERE id = ?",
            arrayOf("e_legacy")
        )
        nameCursor.use {
            assertTrue(it.moveToFirst())
            assertEquals("Alice", it.getString(0))
        }

        // New timeline_entries table is present and writable with its indexes.
        db.execSQL(
            """
            INSERT INTO timeline_entries
                (id, title, startMs, endMs, precision, sourceSegmentId, sourceNoteId,
                 confidence, confirmation, originalExtraction, createdAt, updatedAt)
            VALUES ('t_1', 'Launched the product', 1000, 1000, 'EXACT', 'seg_1', 'note_1',
                    0.9, 'SUGGESTED', '{"title":"Launched"}', 2000, 2000)
            """.trimIndent()
        )
        val timelineCursor = db.query(
            "SELECT title, precision, confirmation FROM timeline_entries WHERE id = ?",
            arrayOf("t_1")
        )
        timelineCursor.use {
            assertTrue(it.moveToFirst())
            assertEquals("Launched the product", it.getString(0))
            assertEquals("EXACT", it.getString(1))
            assertEquals("SUGGESTED", it.getString(2))
        }
    }

    companion object {
        private const val TEST_DB = "migration-6-7-test.db"
    }
}