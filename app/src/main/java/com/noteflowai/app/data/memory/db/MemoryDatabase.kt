package com.noteflowai.app.data.memory.db

import android.content.Context
import android.util.Base64
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.TypeConverters
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.SecureRandom
import com.noteflowai.app.data.capture.RawCapture
import com.noteflowai.app.data.capture.RawCaptureDao
import com.noteflowai.app.data.memory.dao.AnswerCitationDao
import com.noteflowai.app.data.memory.dao.CommitmentDao
import com.noteflowai.app.data.memory.dao.ConflictDao
import com.noteflowai.app.data.memory.dao.DecisionDao
import com.noteflowai.app.data.memory.dao.EntityDao
import com.noteflowai.app.data.memory.dao.EntityMentionDao
import com.noteflowai.app.data.memory.dao.MemoryObjectDao
import com.noteflowai.app.data.memory.dao.MemoryRelationDao
import com.noteflowai.app.data.memory.dao.MemoryReviewItemDao
import com.noteflowai.app.data.memory.dao.ProcessingStatusDao
import com.noteflowai.app.data.memory.dao.SourceSegmentDao
import com.noteflowai.app.data.memory.dao.TimelineEntryDao
import com.noteflowai.app.data.memory.model.AnswerCitation
import com.noteflowai.app.data.memory.model.Commitment
import com.noteflowai.app.data.memory.model.Conflict
import com.noteflowai.app.data.memory.model.Decision
import com.noteflowai.app.data.memory.model.Entity
import com.noteflowai.app.data.memory.model.EntityMention
import com.noteflowai.app.data.memory.model.MemoryObject
import com.noteflowai.app.data.memory.model.MemoryRelation
import com.noteflowai.app.data.memory.model.MemoryReviewItem
import com.noteflowai.app.data.memory.model.ProcessingStatus
import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.TimelineEntry

/**
 * Room database for the Personal Memory Layer.
 *
 * Version 1: source_segments table.
 * Version 2: Added memory_objects, decisions, commitments, entities,
 *            entity_mentions, memory_relations, memory_review_items, answer_citations.
 * Version 3: FK constraints on decisions/commitments.memoryObjectId.
 * Version 4: Added conflicts table.
 * Version 5: Added processing_status table.
 * Version 6: Added raw_captures (Phase 1 durable capture).
 * Version 7: Added confirmation state to entities/entity_mentions and the
 *            timeline_entries table (Phase 4 entity & timeline correction).
 * Version 8: Added quoteText column to answer_citations (Phase 6 citation grounding).
 * Version 9: Backfilled source_segments columns for pre-v9 installs and added
 *            query-backed indices (Phase 4 audit: review/due/stuck/eval queries).
 */
@Database(
    entities = [
        SourceSegment::class,
        Entity::class,
        EntityMention::class,
        MemoryObject::class,
        Decision::class,
        Commitment::class,
        MemoryRelation::class,
        MemoryReviewItem::class,
        AnswerCitation::class,
        Conflict::class,
        ProcessingStatus::class,
        RawCapture::class,
        TimelineEntry::class
    ],
    version = 9,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class MemoryDatabase : RoomDatabase() {

    abstract fun sourceSegmentDao(): SourceSegmentDao
    abstract fun entityDao(): EntityDao
    abstract fun entityMentionDao(): EntityMentionDao
    abstract fun memoryObjectDao(): MemoryObjectDao
    abstract fun decisionDao(): DecisionDao
    abstract fun commitmentDao(): CommitmentDao
    abstract fun memoryRelationDao(): MemoryRelationDao
    abstract fun memoryReviewItemDao(): MemoryReviewItemDao
    abstract fun answerCitationDao(): AnswerCitationDao
    abstract fun conflictDao(): ConflictDao
    abstract fun processingStatusDao(): ProcessingStatusDao
    abstract fun rawCaptureDao(): RawCaptureDao
    abstract fun timelineEntryDao(): TimelineEntryDao

    companion object {
        const val DATABASE_NAME = "noteflow_memory.db"

        // Key under which the SQLCipher passphrase is stored in EncryptedSharedPreferences.
        private const val ENC_MEMORY_DB_PASSPHRASE = "enc_memory_db_passphrase"

        @Volatile
        private var INSTANCE: MemoryDatabase? = null

        /**
         * Migration from version 1 to version 2.
         * Adds all Phase 2 memory entities.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Entities table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS entities (
                        id TEXT NOT NULL PRIMARY KEY,
                        type TEXT NOT NULL,
                        canonicalName TEXT NOT NULL,
                        aliasesJson TEXT,
                        normalizedName TEXT NOT NULL,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0,
                        confidence REAL NOT NULL DEFAULT 0.0,
                        userConfirmed INTEGER NOT NULL DEFAULT 0
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_entities_type ON entities(type)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_entities_normalizedName ON entities(normalizedName)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_entities_createdAt ON entities(createdAt)")

                // Entity mentions table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS entity_mentions (
                        entityId TEXT NOT NULL,
                        sourceSegmentId TEXT NOT NULL,
                        mentionText TEXT NOT NULL,
                        confidence REAL NOT NULL DEFAULT 0.0,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(entityId, sourceSegmentId)
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_entity_mentions_entityId ON entity_mentions(entityId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_entity_mentions_sourceSegmentId ON entity_mentions(sourceSegmentId)")

                // Memory objects table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS memory_objects (
                        id TEXT NOT NULL PRIMARY KEY,
                        type TEXT NOT NULL,
                        statement TEXT NOT NULL,
                        normalizedStatement TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'DETECTED',
                        sourceSegmentId TEXT NOT NULL,
                        sourceId TEXT NOT NULL,
                        projectEntityId TEXT,
                        ownerEntityId TEXT,
                        confidence REAL NOT NULL DEFAULT 0.0,
                        extractionModel TEXT,
                        extractedAt INTEGER NOT NULL DEFAULT 0,
                        confirmedAt INTEGER,
                        dueAt INTEGER,
                        reviewAt INTEGER,
                        metadataJson TEXT
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_objects_type ON memory_objects(type)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_objects_status ON memory_objects(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_objects_sourceSegmentId ON memory_objects(sourceSegmentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_objects_sourceId ON memory_objects(sourceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_objects_projectEntityId ON memory_objects(projectEntityId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_objects_ownerEntityId ON memory_objects(ownerEntityId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_objects_extractedAt ON memory_objects(extractedAt)")

                // Decisions table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS decisions (
                        id TEXT NOT NULL PRIMARY KEY,
                        memoryObjectId TEXT NOT NULL,
                        statement TEXT NOT NULL,
                        reason TEXT,
                        alternativesJson TEXT,
                        status TEXT NOT NULL DEFAULT 'DETECTED',
                        sourceSegmentId TEXT NOT NULL,
                        decidedAt INTEGER,
                        reviewAt INTEGER,
                        projectEntityId TEXT,
                        confidence REAL NOT NULL DEFAULT 0.0,
                        userConfirmed INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_decisions_status ON decisions(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_decisions_sourceSegmentId ON decisions(sourceSegmentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_decisions_projectEntityId ON decisions(projectEntityId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_decisions_decidedAt ON decisions(decidedAt)")

                // Commitments table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS commitments (
                        id TEXT NOT NULL PRIMARY KEY,
                        memoryObjectId TEXT NOT NULL,
                        action TEXT NOT NULL,
                        ownerText TEXT,
                        ownerEntityId TEXT,
                        dueAt INTEGER,
                        status TEXT NOT NULL DEFAULT 'DETECTED',
                        sourceSegmentId TEXT NOT NULL,
                        projectEntityId TEXT,
                        confidence REAL NOT NULL DEFAULT 0.0,
                        userConfirmed INTEGER NOT NULL DEFAULT 0,
                        completedAt INTEGER,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_commitments_status ON commitments(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_commitments_sourceSegmentId ON commitments(sourceSegmentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_commitments_projectEntityId ON commitments(projectEntityId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_commitments_ownerEntityId ON commitments(ownerEntityId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_commitments_dueAt ON commitments(dueAt)")

                // Memory relations table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS memory_relations (
                        id TEXT NOT NULL PRIMARY KEY,
                        fromType TEXT NOT NULL,
                        fromId TEXT NOT NULL,
                        toType TEXT NOT NULL,
                        toId TEXT NOT NULL,
                        relationType TEXT NOT NULL,
                        sourceSegmentId TEXT,
                        confidence REAL NOT NULL DEFAULT 0.0,
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_relations_fromType_fromId ON memory_relations(fromType, fromId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_relations_toType_toId ON memory_relations(toType, toId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_relations_relationType ON memory_relations(relationType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_relations_sourceSegmentId ON memory_relations(sourceSegmentId)")

                // Memory review items table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS memory_review_items (
                        id TEXT NOT NULL PRIMARY KEY,
                        type TEXT NOT NULL,
                        referencedObjectId TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        resolvedAt INTEGER
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_review_items_status ON memory_review_items(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_review_items_type ON memory_review_items(type)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_review_items_createdAt ON memory_review_items(createdAt)")

                // Answer citations table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS answer_citations (
                        id TEXT NOT NULL PRIMARY KEY,
                        answerId TEXT NOT NULL,
                        sourceSegmentId TEXT NOT NULL,
                        claimIndex INTEGER NOT NULL,
                        locationType TEXT NOT NULL,
                        startMs INTEGER,
                        endMs INTEGER,
                        pageNumber INTEGER,
                        url TEXT,
                        supportStatus TEXT NOT NULL DEFAULT 'PENDING',
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_answer_citations_answerId ON answer_citations(answerId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_answer_citations_sourceSegmentId ON answer_citations(sourceSegmentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_answer_citations_supportStatus ON answer_citations(supportStatus)")
            }
        }

        fun getInstance(context: Context): MemoryDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: openDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }

        /**
         * Opens the encrypted memory database, forcing the file open so a
         * pre-encryption plaintext database (from earlier builds where the
         * memory layer was unencrypted) is detected here rather than at the
         * first DAO call. SQLCipher cannot read a plaintext file, so only in
         * that specific case is the file moved aside (kept as a `.bak`, never
         * deleted) and recreated encrypted.
         *
         * Any other failure (migration error, transient SQLCipher error, disk
         * issue) is rethrown: silently deleting the database there would
         * destroy all memory/processing/citation data with no backup.
         */
        private fun openDatabase(context: Context): MemoryDatabase {
            return try {
                buildDatabase(context).also { it.openHelper.writableDatabase }
            } catch (e: RuntimeException) {
                if (!isPlaintextDatabaseError(e)) throw e
                moveAsidePlaintextDatabase(context)
                buildDatabase(context)
            }
        }

        /**
         * SQLCipher reports a plaintext file as "file is not a database".
         * Only that signature justifies discarding the file; everything else
         * must propagate so data is never wiped by a transient failure.
         */
        private fun isPlaintextDatabaseError(e: RuntimeException): Boolean {
            var cause: Throwable? = e
            while (cause != null) {
                val message = cause.message.orEmpty()
                if (message.contains("file is not a database", ignoreCase = true)) return true
                cause = cause.cause
            }
            return false
        }

        private fun moveAsidePlaintextDatabase(context: Context) {
            val dbFile = context.getDatabasePath(DATABASE_NAME)
            val backupFile = context.getDatabasePath("$DATABASE_NAME.plaintext.bak")
            if (dbFile.exists()) {
                if (backupFile.exists()) backupFile.delete()
                if (!dbFile.renameTo(backupFile)) {
                    context.deleteDatabase(DATABASE_NAME)
                }
            }
        }

        /**
         * Migration from version 2 to version 3.
         * Adds foreign key constraints on decisions.memoryObjectId and commitments.memoryObjectId.
         * Room handles the FK constraints at the schema level; this migration recreates the tables.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Recreate decisions with FK
                db.execSQL("CREATE TABLE IF NOT EXISTS decisions_new (id TEXT NOT NULL PRIMARY KEY, memoryObjectId TEXT NOT NULL, statement TEXT NOT NULL, reason TEXT, alternativesJson TEXT, status TEXT NOT NULL DEFAULT 'DETECTED', sourceSegmentId TEXT NOT NULL, decidedAt INTEGER, reviewAt INTEGER, projectEntityId TEXT, confidence REAL NOT NULL DEFAULT 0.0, userConfirmed INTEGER NOT NULL DEFAULT 0, createdAt INTEGER NOT NULL DEFAULT 0, updatedAt INTEGER NOT NULL DEFAULT 0, FOREIGN KEY (memoryObjectId) REFERENCES memory_objects(id) ON DELETE CASCADE)")
                db.execSQL("INSERT INTO decisions_new SELECT * FROM decisions")
                db.execSQL("DROP TABLE decisions")
                db.execSQL("ALTER TABLE decisions_new RENAME TO decisions")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_decisions_status ON decisions(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_decisions_sourceSegmentId ON decisions(sourceSegmentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_decisions_projectEntityId ON decisions(projectEntityId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_decisions_decidedAt ON decisions(decidedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_decisions_memoryObjectId ON decisions(memoryObjectId)")

                // Recreate commitments with FK
                db.execSQL("CREATE TABLE IF NOT EXISTS commitments_new (id TEXT NOT NULL PRIMARY KEY, memoryObjectId TEXT NOT NULL, action TEXT NOT NULL, ownerText TEXT, ownerEntityId TEXT, dueAt INTEGER, status TEXT NOT NULL DEFAULT 'DETECTED', sourceSegmentId TEXT NOT NULL, projectEntityId TEXT, confidence REAL NOT NULL DEFAULT 0.0, userConfirmed INTEGER NOT NULL DEFAULT 0, completedAt INTEGER, createdAt INTEGER NOT NULL DEFAULT 0, updatedAt INTEGER NOT NULL DEFAULT 0, FOREIGN KEY (memoryObjectId) REFERENCES memory_objects(id) ON DELETE CASCADE)")
                db.execSQL("INSERT INTO commitments_new SELECT * FROM commitments")
                db.execSQL("DROP TABLE commitments")
                db.execSQL("ALTER TABLE commitments_new RENAME TO commitments")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_commitments_status ON commitments(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_commitments_sourceSegmentId ON commitments(sourceSegmentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_commitments_projectEntityId ON commitments(projectEntityId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_commitments_ownerEntityId ON commitments(ownerEntityId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_commitments_dueAt ON commitments(dueAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_commitments_memoryObjectId ON commitments(memoryObjectId)")
            }
        }

        /**
         * Migration from version 3 to version 4.
         * Adds the conflicts table for conflict detection.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS conflicts (
                        id TEXT NOT NULL PRIMARY KEY,
                        conflictType TEXT NOT NULL,
                        objectIds TEXT NOT NULL,
                        sourceSegmentIds TEXT NOT NULL,
                        firstObservedAt INTEGER NOT NULL,
                        latestObservedAt INTEGER NOT NULL,
                        confidence REAL NOT NULL DEFAULT 0.0,
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        resolution TEXT,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        resolvedAt INTEGER
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conflicts_status ON conflicts(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conflicts_conflictType ON conflicts(conflictType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conflicts_createdAt ON conflicts(createdAt)")
            }
        }

        /**
         * Migration from version 5 to version 6 (Phase 1 of the Agentic Guide).
         * Adds the raw_captures table recording captures independent of AI processing.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS raw_captures (
                        sourceId TEXT NOT NULL PRIMARY KEY,
                        title TEXT,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0,
                        sourceType TEXT NOT NULL,
                        rawText TEXT,
                        audioUri TEXT,
                        status TEXT NOT NULL DEFAULT 'CAPTURED',
                        deletedAt INTEGER
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_raw_captures_status ON raw_captures(status)")
            }
        }

        /**
         * Migration from version 4 to version 5.
         * Adds the processing_status table for the §7 ingestion pipeline.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS processing_status (
                        sourceId TEXT NOT NULL PRIMARY KEY,
                        sourceType TEXT NOT NULL,
                        currentStage TEXT NOT NULL,
                        status TEXT NOT NULL,
                        error TEXT,
                        attempts INTEGER NOT NULL DEFAULT 0,
                        startedAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0,
                        completedAt INTEGER
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_processing_status_status ON processing_status(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_processing_status_sourceType ON processing_status(sourceType)")
            }
        }

        /**
         * Migration from version 6 to version 7 (Phase 4: entity & timeline correction).
         *
         * Adds a durable `confirmation` state column to `entities` and `entity_mentions`
         * (SUGGESTED by default; existing user-confirmed entities are backfilled to
         * CONFIRMED so legacy `userConfirmed=1` rows keep their meaning), and creates the
         * new `timeline_entries` table.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entities ADD COLUMN confirmation TEXT NOT NULL DEFAULT 'SUGGESTED'")
                db.execSQL("UPDATE entities SET confirmation='CONFIRMED' WHERE userConfirmed=1")
                db.execSQL("ALTER TABLE entity_mentions ADD COLUMN confirmation TEXT NOT NULL DEFAULT 'SUGGESTED'")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS timeline_entries (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        startMs INTEGER,
                        endMs INTEGER,
                        precision TEXT NOT NULL,
                        sourceSegmentId TEXT NOT NULL,
                        sourceNoteId TEXT NOT NULL,
                        confidence REAL NOT NULL DEFAULT 0.5,
                        confirmation TEXT NOT NULL DEFAULT 'SUGGESTED',
                        originalExtraction TEXT,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_timeline_entries_startMs ON timeline_entries(startMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_timeline_entries_sourceNoteId ON timeline_entries(sourceNoteId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_timeline_entries_confirmation ON timeline_entries(confirmation)")
            }
        }

        /**
         * Migration from version 7 to version 8 (Phase 6: citation grounding).
         *
         * Adds the `quoteText` column to `answer_citations` table for storing
         * the app-computed quote excerpt from the validated citation's segment.
         * Existing rows get `quoteText = null`; new inserts include the quote.
         * Backfill: after migration, existing rows with a resolvable segment get
         * quoteText from the segment text using the computed quote range.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE answer_citations ADD COLUMN quoteText TEXT")
            }
        }

        /**
         * Migration from version 8 to version 9 (Phase 4 audit: schema repair).
         *
         * Two jobs:
         * 1. source_segments columns: no migration ever touched this table, so
         *    installs predating the newer columns (speaker → metadataJson)
         *    crash on Room schema validation at open. Columns are added only
         *    when PRAGMA shows them missing, so the migration is safe on any
         *    history (plain ALTER would fail on installs that already have
         *    the column).
         * 2. Query-backed indices: every index declared on the v9 entities is
         *    created IF NOT EXISTS, so old installs gain the review/due/stuck/
         *    eval query paths without a destructive migration.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ensureSourceSegmentColumns(db)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_objects_sourceSegmentId_normalizedStatement ON memory_objects(sourceSegmentId, normalizedStatement)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_decisions_reviewAt ON decisions(reviewAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_commitments_completedAt ON commitments(completedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_commitments_createdAt ON commitments(createdAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conflicts_objectIds ON conflicts(objectIds)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_answer_citations_answerId_claimIndex ON answer_citations(answerId, claimIndex)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_processing_status_updatedAt ON processing_status(updatedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_timeline_entries_sourceSegmentId ON timeline_entries(sourceSegmentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_entities_confirmation ON entities(confirmation)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_entities_canonicalName ON entities(canonicalName)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_entity_mentions_confirmation ON entity_mentions(confirmation)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_relations_fromId ON memory_relations(fromId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_relations_toId ON memory_relations(toId)")
            }

            private fun ensureSourceSegmentColumns(db: SupportSQLiteDatabase) {
                val existing = mutableSetOf<String>()
                db.query("PRAGMA table_info(`source_segments`)").use { cursor ->
                    val nameIdx = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) {
                        existing.add(cursor.getString(nameIdx))
                    }
                }
                // Full v9 column set with Room-compatible definitions.
                val columns = listOf(
                    "`id` TEXT NOT NULL",
                    "`sourceId` TEXT NOT NULL",
                    "`sourceType` TEXT NOT NULL",
                    "`text` TEXT NOT NULL",
                    "`normalizedText` TEXT NOT NULL",
                    "`startMs` INTEGER",
                    "`endMs` INTEGER",
                    "`pageNumber` INTEGER",
                    "`blockId` TEXT",
                    "`url` TEXT",
                    "`speaker` TEXT",
                    "`language` TEXT",
                    "`transcriptionEngine` TEXT",
                    "`confidence` REAL",
                    "`createdAt` INTEGER NOT NULL DEFAULT 0",
                    "`updatedAt` INTEGER NOT NULL DEFAULT 0",
                    "`isOriginalContent` INTEGER NOT NULL DEFAULT 1",
                    "`parentSegmentId` TEXT",
                    "`metadataJson` TEXT"
                )
                for (definition in columns) {
                    val name = definition.substring(1, definition.indexOf('`', 1))
                    if (name !in existing) {
                        db.execSQL("ALTER TABLE `source_segments` ADD COLUMN $definition")
                    }
                }
            }
        }

        private fun buildDatabase(context: Context): MemoryDatabase {
            // SQLCipher must be loaded before any encrypted database opens.
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(
                context.applicationContext,
                MemoryDatabase::class.java,
                DATABASE_NAME
            )
                .openHelperFactory(SupportOpenHelperFactory(memoryDbPassphrase(context)))
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                .build()
        }

        /**
         * Returns the SQLCipher passphrase for the memory database, generating
         * and persisting a random one on first use. It is stored in the same
         * EncryptedSharedPreferences file that protects the user's API keys
         * (itself encrypted by the Android Keystore master key), so the
         * passphrase never exists in plaintext on disk and survives restarts.
         */
        private fun memoryDbPassphrase(context: Context): ByteArray {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context,
                "noteflow_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            var passphrase = prefs.getString(ENC_MEMORY_DB_PASSPHRASE, null)
            if (passphrase == null) {
                val random = ByteArray(32)
                SecureRandom().nextBytes(random)
                passphrase = Base64.encodeToString(random, Base64.NO_WRAP)
                prefs.edit().putString(ENC_MEMORY_DB_PASSPHRASE, passphrase).apply()
            }
            return passphrase.toByteArray(Charsets.US_ASCII)
        }
    }
}
