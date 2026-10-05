package com.noteflowai.app.data.memory.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Migration 7 -> 8 (Phase 6: citation grounding).
 *
 * Adds the `quoteText` column to `answer_citations` table.
 * Existing rows get `quoteText = NULL`; new inserts can include the quote text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryDatabaseMigration7To8Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MemoryDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun `migration 7 to 8 adds quoteText column and preserves existing data`() {
        // Create DB at version 7 using the exported schema.
        helper.createDatabase(TEST_DB, 7).apply {
            execSQL(
                """
                INSERT INTO answer_citations
                    (id, answerId, sourceSegmentId, claimIndex, locationType,
                     startMs, endMs, pageNumber, url, supportStatus, createdAt)
                VALUES ('ac_1', 'ans_1', 'seg_1', 0, 'NOTE',
                        0, 5000, NULL, NULL, 'VALIDATED', 1000)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO answer_citations
                    (id, answerId, sourceSegmentId, claimIndex, locationType,
                     startMs, endMs, pageNumber, url, supportStatus, createdAt)
                VALUES ('ac_2', 'ans_1', 'seg_2', 1, 'NOTE',
                        NULL, NULL, 3, NULL, 'PENDING', 1000)
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            8,
            true,
            MemoryDatabase.MIGRATION_7_8
        )

        // Existing rows have quoteText = NULL
        val cursor1 = db.query(
            "SELECT quoteText FROM answer_citations WHERE id = ?",
            arrayOf("ac_1")
        )
        cursor1.use {
            assertTrue(it.moveToFirst())
            assertNull(it.getString(0))
        }

        val cursor2 = db.query(
            "SELECT quoteText FROM answer_citations WHERE id = ?",
            arrayOf("ac_2")
        )
        cursor2.use {
            assertTrue(it.moveToFirst())
            assertNull(it.getString(0))
        }

        // Original data survived
        val statusCursor = db.query(
            "SELECT supportStatus, pageNumber FROM answer_citations WHERE id = ?",
            arrayOf("ac_1")
        )
        statusCursor.use {
            assertTrue(it.moveToFirst())
            assertEquals("VALIDATED", it.getString(0))
            assertEquals(0, it.getInt(1))
        }

        val pageCursor = db.query(
            "SELECT pageNumber FROM answer_citations WHERE id = ?",
            arrayOf("ac_2")
        )
        pageCursor.use {
            assertTrue(it.moveToFirst())
            assertEquals(3, it.getInt(0))
        }

        // New inserts with quoteText work
        db.execSQL(
            """
            INSERT INTO answer_citations
                (id, answerId, sourceSegmentId, claimIndex, locationType,
                 startMs, endMs, pageNumber, url, supportStatus, createdAt, quoteText)
            VALUES ('ac_3', 'ans_2', 'seg_3', 0, 'NOTE',
                    0, 5000, NULL, NULL, 'VALIDATED', 2000, 'The supplier was ACME Corp.')
            """.trimIndent()
        )
        val quoteCursor = db.query(
            "SELECT quoteText FROM answer_citations WHERE id = ?",
            arrayOf("ac_3")
        )
        quoteCursor.use {
            assertTrue(it.moveToFirst())
            assertEquals("The supplier was ACME Corp.", it.getString(0))
        }
    }

    companion object {
        private const val TEST_DB = "migration-7-8-test.db"
    }
}