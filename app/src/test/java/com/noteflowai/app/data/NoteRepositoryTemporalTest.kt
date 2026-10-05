package com.noteflowai.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteRepositoryTemporalTest {

    private lateinit var context: Context
    private lateinit var repository: NoteRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val notesDir = File(context.filesDir, "notes")
        notesDir.deleteRecursively()
        notesDir.mkdirs()
        repository = NoteRepository(context)
    }

    @Test
    fun `saveNote records createdAt and createdAtEpoch and preserves them on update`() = runBlocking {
        repository.saveNote("TestNote.json", "Initial content")
        repository.refreshNotesList()

        val initialList = repository.notesFlow.value
        assertEquals(1, initialList.size)
        val initialNote = initialList[0]

        val initialCreatedEpoch = initialNote.createdAtEpoch
        val initialCreated = initialNote.createdAt
        assertTrue(initialCreatedEpoch > 0)
        assertTrue(initialCreated.isNotBlank())

        // Small delay to ensure timestamp difference on update
        Thread.sleep(50)

        repository.saveNote("TestNote.json", "Updated content with more details")
        repository.refreshNotesList()

        val updatedList = repository.notesFlow.value
        assertEquals(1, updatedList.size)
        val updatedNote = updatedList[0]

        // Created timestamps must be preserved
        assertEquals(initialCreatedEpoch, updatedNote.createdAtEpoch)
        assertEquals(initialCreated, updatedNote.createdAt)

        // Modified timestamps should reflect the update
        assertTrue(updatedNote.lastModifiedEpoch >= initialNote.lastModifiedEpoch)
    }
}
