package com.noteflowai.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.noteflowai.app.data.search.RetrievalConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsManagerRetrievalConfigTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val sm = SettingsManager(context)

    /**
     * Reaches the same DataStore instance SettingsManager reads from, so persisted
     * values written here are visible to readRetrievalConfig()/readRetrievalConfigBlocking.
     */
    private fun productionDataStore(): DataStore<Preferences> {
        val field = SettingsManager::class.java.getDeclaredField("dataStore")
        @Suppress("UNCHECKED_CAST")
        return field.apply { isAccessible = true }.get(sm) as DataStore<Preferences>
    }

    @Test
    fun `defaults satisfy weight-normalization invariant and match spec`() = runTest {
        val cfg = sm.readRetrievalConfig()
        assertEquals(0.4f, cfg.bm25Weight, 0.001f)
        assertEquals(0.4f, cfg.vectorWeight, 0.001f)
        assertEquals(0.12f, cfg.entityWeight, 0.001f)
        assertEquals(0.08f, cfg.recencyWeight, 0.001f)
        assertEquals(1.0f, cfg.bm25Weight + cfg.vectorWeight + cfg.entityWeight + cfg.recencyWeight, 0.001f)
        assertEquals(40, cfg.topK)
        assertEquals(0.28f, cfg.minimumScore, 0.001f)
        assertEquals(1000, cfg.maxContextTokens)
    }

    @Test
    fun `blocking accessor returns same defaults as reading path`() = runTest {
        val blocking = sm.readRetrievalConfigBlocking
        val async = sm.readRetrievalConfig()
        assertEquals(async, blocking)
        assertEquals(0.4f, blocking.bm25Weight, 0.001f)
        assertEquals(0.4f, blocking.vectorWeight, 0.001f)
        assertEquals(0.12f, blocking.entityWeight, 0.001f)
        assertEquals(0.08f, blocking.recencyWeight, 0.001f)
        assertEquals(40, blocking.topK)
        assertEquals(0.28f, blocking.minimumScore, 0.001f)
        assertEquals(1000, blocking.maxContextTokens)
    }

    @Test
    fun `blocking accessor reflects persisted overrides`() = runTest {
        productionDataStore().edit {
            it[floatPreferencesKey("retrieval_bm25_weight")] = 0.6f
            it[floatPreferencesKey("retrieval_vector_weight")] = 0.2f
            it[floatPreferencesKey("retrieval_entity_weight")] = 0.1f
            it[floatPreferencesKey("retrieval_recency_weight")] = 0.1f
            it[intPreferencesKey("retrieval_top_k")] = 25
            it[floatPreferencesKey("retrieval_min_score")] = 0.5f
            it[intPreferencesKey("retrieval_max_context_tokens")] = 1500
        }
        val blocking = sm.readRetrievalConfigBlocking
        val async = sm.readRetrievalConfig()
        assertEquals(async, blocking)
        assertEquals(0.6f, blocking.bm25Weight, 0.001f)
        assertEquals(0.2f, blocking.vectorWeight, 0.001f)
        assertEquals(0.1f, blocking.entityWeight, 0.001f)
        assertEquals(0.1f, blocking.recencyWeight, 0.001f)
        assertEquals(25, blocking.topK)
        assertEquals(0.5f, blocking.minimumScore, 0.001f)
        assertEquals(1500, blocking.maxContextTokens)
    }
}