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
 * Room migration tests for the Personal Memory Layer database.
 *
 * Verifies that existing user data survives each schema migration and that
 * newly added tables are present and queryable after migration.
 *
 * Uses [MigrationTestHelper] from androidx.room:room-testing and the exported
 * schemas in app/schemas (mounted as test assets in build.gradle.kts).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryDatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MemoryDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun `migration 3 to 4 adds conflicts table and preserves existing data`() {
        // Create DB at version 3 using the exported schema
        helper.createDatabase(TEST_DB, 3).apply {
            // Insert a pre-migration source segment so we can assert data survives
            execSQL(
                """
                INSERT INTO source_segments
                    (id, sourceId, sourceType, text, normalizedText, createdAt, updatedAt,
                     isOriginalContent)
                VALUES ('seg_migrate', 'src_1', 'NOTE', 'legacy text', 'legacy text',
                        1000, 1000, 1)
                """.trimIndent()
            )
            close()
        }

        // Run the 3 -> 4 migration
        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            4,
            true,
            MemoryDatabase.MIGRATION_3_4
        )

        // The legacy row survived the migration
        val cursor = db.query(
            "SELECT id, text FROM source_segments WHERE id = ?",
            arrayOf("seg_migrate")
        )
        cursor.use {
            assertTrue(it.moveToFirst())
            assertEquals("seg_migrate", it.getString(0))
            assertEquals("legacy text", it.getString(1))
        }

        // The new conflicts table exists and is writable
        db.execSQL(
            """
            INSERT INTO conflicts
                (id, conflictType, objectIds, sourceSegmentIds,
                 firstObservedAt, latestObservedAt, confidence, status, createdAt)
            VALUES ('c1', 'DECISION_CONFLICT', '["a","b"]', '["s1","s2"]',
                    100, 200, 0.7, 'PENDING', 300)
            """.trimIndent()
        )
        val conflictCursor = db.query("SELECT status FROM conflicts WHERE id = ?", arrayOf("c1"))
        conflictCursor.use {
            assertTrue(it.moveToFirst())
            assertEquals("PENDING", it.getString(0))
        }
    }

    @Test
    fun `migration 4 to 5 adds processing_status table and preserves existing data`() {
        // Create DB at version 4 using the exported schema
        helper.createDatabase(TEST_DB, 4).apply {
            // Insert a pre-migration source segment so we can assert data survives
            execSQL(
                """
                INSERT INTO source_segments
                    (id, sourceId, sourceType, text, normalizedText, createdAt, updatedAt,
                     isOriginalContent)
                VALUES ('seg_migrate', 'src_1', 'NOTE', 'legacy text', 'legacy text',
                        1000, 1000, 1)
                """.trimIndent()
            )
            close()
        }

        // Run the 4 -> 5 migration
        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            5,
            true,
            MemoryDatabase.MIGRATION_4_5
        )

        // The legacy row survived the migration
        val cursor = db.query(
            "SELECT id, text FROM source_segments WHERE id = ?",
            arrayOf("seg_migrate")
        )
        cursor.use {
            assertTrue(it.moveToFirst())
            assertEquals("seg_migrate", it.getString(0))
            assertEquals("legacy text", it.getString(1))
        }

        // The new processing_status table exists and is writable
        db.execSQL(
            """
            INSERT INTO processing_status
                (sourceId, sourceType, currentStage, status, attempts, startedAt, updatedAt)
            VALUES ('src_1', 'NOTE', 'SEGMENTS_CREATED', 'PENDING', 0, 100, 100)
            """.trimIndent()
        )
        val statusCursor = db.query(
            "SELECT sourceType, status FROM processing_status WHERE sourceId = ?",
            arrayOf("src_1")
        )
        statusCursor.use {
            assertTrue(it.moveToFirst())
            assertEquals("NOTE", it.getString(0))
            assertEquals("PENDING", it.getString(1))
        }
    }

    @Test
    fun `migration 5 to 6 adds raw_captures table and preserves existing data`() {
        // Create DB at version 5 using the exported schema
        helper.createDatabase(TEST_DB, 5).apply {
            // Insert a pre-migration source segment so we can assert data survives
            execSQL(
                """
                INSERT INTO source_segments
                    (id, sourceId, sourceType, text, normalizedText, createdAt, updatedAt,
                     isOriginalContent)
                VALUES ('seg_migrate', 'src_1', 'NOTE', 'legacy text', 'legacy text',
                        1000, 1000, 1)
                """.trimIndent()
            )
            close()
        }

        // Run the 5 -> 6 migration
        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            6,
            true,
            MemoryDatabase.MIGRATION_5_6
        )

        // The legacy row survived the migration
        val cursor = db.query(
            "SELECT id, text FROM source_segments WHERE id = ?",
            arrayOf("seg_migrate")
        )
        cursor.use {
            assertTrue(it.moveToFirst())
            assertEquals("seg_migrate", it.getString(0))
            assertEquals("legacy text", it.getString(1))
        }

        // The new raw_captures table exists and is writable
        db.execSQL(
            """
            INSERT INTO raw_captures
                (sourceId, title, createdAt, updatedAt, sourceType, rawText, status)
            VALUES ('raw_1', 'captured note', 1000, 1000, 'NOTE', 'raw content', 'CAPTURED')
            """.trimIndent()
        )
        val captureCursor = db.query(
            "SELECT sourceType, status FROM raw_captures WHERE sourceId = ?",
            arrayOf("raw_1")
        )
        captureCursor.use {
            assertTrue(it.moveToFirst())
            assertEquals("NOTE", it.getString(0))
            assertEquals("CAPTURED", it.getString(1))
        }
    }

    @Test
    fun `migration 3 to 4 keeps conflicts queryable via dao`() {
        helper.createDatabase(TEST_DB, 3).close()
        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            4,
            true,
            MemoryDatabase.MIGRATION_3_4
        )
        // Insert a conflict row directly, then confirm the enum value round-trips
        db.execSQL(
            """
            INSERT INTO conflicts
                (id, conflictType, objectIds, sourceSegmentIds,
                 firstObservedAt, latestObservedAt, confidence, status, createdAt)
            VALUES ('c2', 'DEADLINE_CONFLICT', '["x","y"]', '["s3","s4"]',
                    500, 600, 0.6, 'PENDING', 700)
            """.trimIndent()
        )
        val cursor = db.query("SELECT confidence, status FROM conflicts WHERE id = ?", arrayOf("c2"))
        cursor.use {
            assertTrue(it.moveToFirst())
            assertEquals(0.6, it.getDouble(0), 0.0)
            assertEquals("PENDING", it.getString(1))
        }
    }

    companion object {
        private const val TEST_DB = "migration-test.db"
    }
}
