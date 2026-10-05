package com.noteflowai.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test: editing the "Fridge Photo Magnet Idea" note crashed with
 * IllegalArgumentException (LazyColumn duplicate key). Root cause: persistNote
 * appends via addNoteToList on every save, so re-saving an existing note
 * duplicated it in the in-memory list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteRepositoryUpsertTest {

    @Test
    fun `re-saving an existing note replaces it instead of duplicating`() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val repo = NoteRepository(context)
        repo.init()

        repo.saveNote("EditMe.json", "version one")
        repo.saveNote("EditMe.json", "version two")

        val matches = repo.notesFlow.value.filter { it.fileName == "EditMe.json" }
        assertEquals("expected exactly one entry after re-save, got: ${matches.size}", 1, matches.size)
        assertEquals("version two", matches.first().content)
    }
}
