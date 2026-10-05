package com.noteflowai.app.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.noteflowai.app.data.chat.ChatRecallMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AiPreset(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val provider: String,
    val apiKey: String? = "",
    val baseUrl: String,
    val modelName: String,
    val systemPrompt: String,
    val temperature: Float,
    val presencePenalty: Float,
    val topP: Float? = 1.0f,
    val contextTokens: Int? = 2048,
    val useKvCache: Boolean? = true
)

class SettingsManager(
    context: Context,
    private val dataStore: DataStore<Preferences> = context.applicationContext.dataStore
) {
    /** Expose raw DataStore for one-shot preference reads without adding per-key flows. */
    fun dataSnapshot(): Flow<Preferences> = dataStore.data
    init {
        try {
            java.io.File(context.filesDir, "datastore").mkdirs()
        } catch (_: Exception) {}
    }

    private val gson = Gson()

    // Encrypted storage for sensitive keys
    private val masterKey by lazy {
        try {
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        } catch (e: Exception) {
            null
        }
    }

    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            val key = masterKey
            if (key != null) {
                EncryptedSharedPreferences.create(
                    context,
                    "noteflow_secure_prefs",
                    key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } else {
                context.getSharedPreferences("noteflow_secure_prefs_fallback", Context.MODE_PRIVATE)
            }
        } catch (e: Exception) {
            // Fallback for JVM/Robolectric test environments or devices without KeyStore
            context.getSharedPreferences("noteflow_secure_prefs_fallback", Context.MODE_PRIVATE)
        }
    }

    companion object {
        const val DEFAULT_EMBEDDING_MODEL = "ibm-granite/granite-embedding-311m-multilingual-r2"
        @Volatile private var instance: SettingsManager? = null

        fun getInstance(context: Context): SettingsManager {
            return instance ?: synchronized(this) {
                instance ?: SettingsManager(context.applicationContext).also { instance = it }
            }
        }

        val WHISPER_MODEL = stringPreferencesKey("whisper_model")
        val CUSTOM_MODEL_PATH = stringPreferencesKey("custom_model_path")

        // Scheduled recording (persisted so BootReceiver can re-arm alarms after reboot)
        val SCHEDULED_RECORDING_ENABLED = booleanPreferencesKey("scheduled_recording_enabled")
        val SCHEDULED_RECORDING_HOUR = intPreferencesKey("scheduled_recording_hour")
        val SCHEDULED_RECORDING_MINUTE = intPreferencesKey("scheduled_recording_minute")
        val SCHEDULED_RECORDING_DURATION = intPreferencesKey("scheduled_recording_duration")
        val IS_DARK_MODE = booleanPreferencesKey("is_dark_mode")
        val DARK_MODE_OPTION = stringPreferencesKey("dark_mode_option")
        val TRANSLATE_MODE = booleanPreferencesKey("translate_mode")

        // AI Chat Settings
        val AI_PROVIDER = stringPreferencesKey("ai_provider")
        val AI_BASE_URL = stringPreferencesKey("ai_base_url")
        val AI_MODEL_NAME = stringPreferencesKey("ai_model_name")
        val AI_SYSTEM_PROMPT = stringPreferencesKey("ai_system_prompt")
        val AI_THINKING_MODE = booleanPreferencesKey("ai_thinking_mode")
        val AI_TEMPERATURE = floatPreferencesKey("ai_temperature")
        val AI_PRESENCE_PENALTY = floatPreferencesKey("ai_presence_penalty")
        val AI_TOP_P = floatPreferencesKey("ai_top_p")
        val AI_CONTEXT_TOKENS = intPreferencesKey("ai_context_tokens")
        val AI_KV_CACHE = booleanPreferencesKey("ai_kv_cache")
        val AI_MAX_REQUESTS_PER_MIN = intPreferencesKey("ai_max_requests_per_min")

        // Web Search
        val AI_WEB_SEARCH_ENABLED = booleanPreferencesKey("ai_web_search_enabled")
        val AI_SEARCH_API_URL = stringPreferencesKey("ai_search_api_url")

        val TRANSCRIPTION_LANGUAGE = stringPreferencesKey("transcription_language")
        val APP_THEME = stringPreferencesKey("app_theme")
        val APP_FONT = stringPreferencesKey("app_font")

        val IS_ONLINE_MODE = booleanPreferencesKey("is_online_mode")
        val LAST_CHAT_SESSION_ID = stringPreferencesKey("last_chat_session_id")
        val CHAT_RECALL_MODE = stringPreferencesKey("chat_recall_mode")

        // RAG (Retrieval-Augmented Generation) settings
        val RAG_ENABLED = booleanPreferencesKey("rag_enabled")
        val RAG_MAX_EXCERPTS = intPreferencesKey("rag_max_excerpts")
        val RAG_MAX_CONTEXT_TOKENS = intPreferencesKey("rag_max_context_tokens")
        val EMBEDDING_ENABLED = booleanPreferencesKey("embedding_enabled")
        val EMBEDDING_MODEL = stringPreferencesKey("embedding_model")
        val CITATION_ENABLED = booleanPreferencesKey("citation_enabled")
        val RERANKER_ENABLED = booleanPreferencesKey("reranker_enabled")
        // Phase 0 safety: user consent for shipping whole-note text (up to
        // 28k chars) to a cloud provider. Defaults on for behavior parity;
        // turning it off falls back to snippet budgets.
        val CLOUD_FULL_NOTE_ENABLED = booleanPreferencesKey("cloud_full_note_enabled")

        // Retrieval evaluation settings (Phase 5)
        val RETRIEVAL_BM25_WEIGHT = floatPreferencesKey("retrieval_bm25_weight")
        val RETRIEVAL_VECTOR_WEIGHT = floatPreferencesKey("retrieval_vector_weight")
        val RETRIEVAL_ENTITY_WEIGHT = floatPreferencesKey("retrieval_entity_weight")
        val RETRIEVAL_RECENCY_WEIGHT = floatPreferencesKey("retrieval_recency_weight")
        val RETRIEVAL_TOP_K = intPreferencesKey("retrieval_top_k")
        val RETRIEVAL_MIN_SCORE = floatPreferencesKey("retrieval_min_score")
        val RETRIEVAL_MAX_CONTEXT_TOKENS = intPreferencesKey("retrieval_max_context_tokens")

        // RAG v2 settings
        val CONCEPT_GRAPH_ENABLED = booleanPreferencesKey("concept_graph_enabled")
        val MULTI_HOP_ENABLED = booleanPreferencesKey("multi_hop_enabled")
        val SEARCH_EXPLANATIONS_ENABLED = booleanPreferencesKey("search_explanations_enabled")
        val SMART_SNIPPETS_ENABLED = booleanPreferencesKey("smart_snippets_enabled")

        // TTS settings
        val TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        val TTS_VOICE = stringPreferencesKey("tts_voice")

        val DAILY_DIGEST_ENABLED = booleanPreferencesKey("daily_digest_enabled")
        val RECALL_REMINDERS_ENABLED = booleanPreferencesKey("recall_reminders_enabled")

        // Memory Layer feature flags (Phase 1-7)
        val ENABLE_SOURCE_SEGMENTS = booleanPreferencesKey("enable_source_segments")
        val ENABLE_MEMORY_DATABASE = booleanPreferencesKey("enable_memory_database")
        val ENABLE_MEMORY_EXTRACTION = booleanPreferencesKey("enable_memory_extraction")
        val ENABLE_QUERY_PLANNER = booleanPreferencesKey("enable_query_planner")
        val ENABLE_GROUNDED_MEMORY_CHAT = booleanPreferencesKey("enable_grounded_memory_chat")
        val ENABLE_DECISION_TIMELINE = booleanPreferencesKey("enable_decision_timeline")
        val ENABLE_COMMITMENT_DASHBOARD = booleanPreferencesKey("enable_commitment_dashboard")
        val ENABLE_CHANGE_ANALYSIS = booleanPreferencesKey("enable_change_analysis")
        val ENABLE_CONFLICT_DETECTION = booleanPreferencesKey("enable_conflict_detection")
        val ENABLE_WEEKLY_REVIEW = booleanPreferencesKey("enable_weekly_review")
        val ENABLE_EVALUATION_DASHBOARD = booleanPreferencesKey("enable_evaluation_dashboard")
        val ENABLE_SEGMENT_INDEXING = booleanPreferencesKey("enable_segment_indexing")
        val IS_LOCAL_ONLY_MODE = booleanPreferencesKey("is_local_only_mode")
        val ENABLE_LOCAL_LLM_FALLBACK = booleanPreferencesKey("enable_local_llm_fallback")
        val LOCAL_LLM_MODEL_ID = stringPreferencesKey("local_llm_model_id")

        // Search history
        val SEARCH_HISTORY = stringPreferencesKey("search_history")

        // Last Cloud AI configuration (preserved across Local/Cloud toggles)
        val LAST_CLOUD_PROVIDER = stringPreferencesKey("last_cloud_provider")
        val LAST_CLOUD_BASE_URL = stringPreferencesKey("last_cloud_base_url")
        val LAST_CLOUD_MODEL_NAME = stringPreferencesKey("last_cloud_model_name")
        val LAST_CLOUD_SYSTEM_PROMPT = stringPreferencesKey("last_cloud_system_prompt")
        val LAST_CLOUD_TEMPERATURE = floatPreferencesKey("last_cloud_temperature")
        val LAST_CLOUD_PRESENCE_PENALTY = floatPreferencesKey("last_cloud_presence_penalty")
        val LAST_CLOUD_TOP_P = floatPreferencesKey("last_cloud_top_p")
        val LAST_CLOUD_CONTEXT_TOKENS = intPreferencesKey("last_cloud_context_tokens")
        val LAST_CLOUD_KV_CACHE = booleanPreferencesKey("last_cloud_kv_cache")

        // Encrypted preference keys
        private const val ENC_AI_API_KEY = "enc_ai_api_key"
        private const val ENC_LAST_CLOUD_API_KEY = "enc_last_cloud_api_key"
        private const val ENC_DEEPGRAM_API_KEY = "enc_deepgram_api_key"
        private const val ENC_AI_SEARCH_API_KEY = "enc_ai_search_api_key"
        private const val ENC_AI_PRESETS = "enc_ai_presets"
    }

    val whisperModel: Flow<String> = dataStore.data.map { it[WHISPER_MODEL] ?: "tiny" }

    // Scheduled recording persistence (blocking variants for BroadcastReceiver use)
    val scheduledRecordingEnabledBlocking: Boolean
        get() = runBlocking { dataStore.data.first()[SCHEDULED_RECORDING_ENABLED] ?: false }
    val scheduledRecordingConfigBlocking: Triple<Int, Int, Int>
        get() = runBlocking {
            val prefs = dataStore.data.first()
            Triple(
                prefs[SCHEDULED_RECORDING_HOUR] ?: 9,
                prefs[SCHEDULED_RECORDING_MINUTE] ?: 0,
                prefs[SCHEDULED_RECORDING_DURATION] ?: 30
            )
        }

    suspend fun setScheduledRecordingConfig(enabled: Boolean, hour: Int, minute: Int, durationMinutes: Int) {
        dataStore.edit {
            it[SCHEDULED_RECORDING_ENABLED] = enabled
            it[SCHEDULED_RECORDING_HOUR] = hour
            it[SCHEDULED_RECORDING_MINUTE] = minute
            it[SCHEDULED_RECORDING_DURATION] = durationMinutes
        }
    }

    val whisperModelBlocking: String
        get() = runBlocking { dataStore.data.first()[WHISPER_MODEL] ?: "tiny" }
    val customModelPath: Flow<String?> = dataStore.data.map { it[CUSTOM_MODEL_PATH] }
    val isDarkMode: Flow<Boolean> = dataStore.data.map { it[IS_DARK_MODE] ?: false }
    val isDarkModeBlocking: Boolean
        get() = runBlocking { dataStore.data.first()[IS_DARK_MODE] ?: false }

    val darkModeOption: Flow<String> = dataStore.data.map { it[DARK_MODE_OPTION] ?: "system" }
    val darkModeOptionBlocking: String
        get() = runBlocking { dataStore.data.first()[DARK_MODE_OPTION] ?: "system" }
    val isTranslateMode: Flow<Boolean> = dataStore.data.map { it[TRANSLATE_MODE] ?: true }
    val transcriptionLanguage: Flow<String> = dataStore.data.map { it[TRANSCRIPTION_LANGUAGE] ?: "English" }
    val appTheme: Flow<String> = dataStore.data.map { it[APP_THEME] ?: "Default" }
    val appFont: Flow<String> = dataStore.data.map { it[APP_FONT] ?: "Default" }

    val aiProvider: Flow<String> = dataStore.data.map { it[AI_PROVIDER] ?: "Ollama" }
    val aiProviderBlocking: String
        get() = runBlocking { dataStore.data.first()[AI_PROVIDER] ?: "Ollama" }
    val aiBaseUrl: Flow<String> = dataStore.data.map { it[AI_BASE_URL] ?: "http://10.0.2.2:11434/" }
    val aiBaseUrlBlocking: String
        get() = runBlocking { dataStore.data.first()[AI_BASE_URL] ?: "http://10.0.2.2:11434/" }
    val aiModelName: Flow<String> = dataStore.data.map { it[AI_MODEL_NAME] ?: "llama3.2" }
    val aiModelNameBlocking: String
        get() = runBlocking { dataStore.data.first()[AI_MODEL_NAME] ?: "llama3.2" }
    val aiSystemPrompt: Flow<String> = dataStore.data.map { it[AI_SYSTEM_PROMPT] ?: "You are a helpful AI assistant." }
    val aiSystemPromptBlocking: String
        get() = runBlocking { dataStore.data.first()[AI_SYSTEM_PROMPT] ?: "You are a helpful AI assistant." }
    val aiThinkingMode: Flow<Boolean> = dataStore.data.map { it[AI_THINKING_MODE] ?: false }
    val aiTemperature: Flow<Float> = dataStore.data.map { it[AI_TEMPERATURE] ?: 0.7f }
    val aiTemperatureBlocking: Float
        get() = runBlocking { dataStore.data.first()[AI_TEMPERATURE] ?: 0.7f }
    val aiPresencePenalty: Flow<Float> = dataStore.data.map { it[AI_PRESENCE_PENALTY] ?: 0.0f }
    val aiPresencePenaltyBlocking: Float
        get() = runBlocking { dataStore.data.first()[AI_PRESENCE_PENALTY] ?: 0.0f }
    val aiTopP: Flow<Float> = dataStore.data.map { it[AI_TOP_P] ?: 1.0f }
    val aiTopPBlocking: Float
        get() = runBlocking { dataStore.data.first()[AI_TOP_P] ?: 1.0f }
    val aiContextTokens: Flow<Int> = dataStore.data.map { it[AI_CONTEXT_TOKENS] ?: 2048 }
    val aiContextTokensBlocking: Int
        get() = runBlocking { dataStore.data.first()[AI_CONTEXT_TOKENS] ?: 2048 }
    val aiKvCache: Flow<Boolean> = dataStore.data.map { it[AI_KV_CACHE] ?: true }
    val aiKvCacheBlocking: Boolean
        get() = runBlocking { dataStore.data.first()[AI_KV_CACHE] ?: true }
    val aiMaxRequestsPerMin: Flow<Int> = dataStore.data.map { it[AI_MAX_REQUESTS_PER_MIN] ?: 0 }
    val aiMaxRequestsPerMinBlocking: Int
        get() = runBlocking { dataStore.data.first()[AI_MAX_REQUESTS_PER_MIN] ?: 0 }

    val aiWebSearchEnabled: Flow<Boolean> = dataStore.data.map { it[AI_WEB_SEARCH_ENABLED] ?: false }
    val aiWebSearchEnabledBlocking: Boolean
        get() = runBlocking { dataStore.data.first()[AI_WEB_SEARCH_ENABLED] ?: false }
    val aiSearchApiUrl: Flow<String> = dataStore.data.map { it[AI_SEARCH_API_URL] ?: "" }
    val aiSearchApiUrlBlocking: String
        get() = runBlocking { dataStore.data.first()[AI_SEARCH_API_URL] ?: "" }

    val isOnlineMode: Flow<Boolean> = dataStore.data.map { it[IS_ONLINE_MODE] ?: false }
    val isLocalOnlyMode: Flow<Boolean> = dataStore.data.map { it[IS_LOCAL_ONLY_MODE] ?: false }
    val isLocalOnlyModeBlocking: Boolean
        get() = runBlocking { dataStore.data.first()[IS_LOCAL_ONLY_MODE] ?: false }

    suspend fun setLocalOnlyMode(enabled: Boolean) {
        dataStore.edit { it[IS_LOCAL_ONLY_MODE] = enabled }
    }

    val enableLocalLlmFallback: Flow<Boolean> = dataStore.data.map { it[ENABLE_LOCAL_LLM_FALLBACK] ?: true }
    val enableLocalLlmFallbackBlocking: Boolean
        get() = runBlocking { dataStore.data.first()[ENABLE_LOCAL_LLM_FALLBACK] ?: true }

    suspend fun setEnableLocalLlmFallback(enabled: Boolean) {
        dataStore.edit { it[ENABLE_LOCAL_LLM_FALLBACK] = enabled }
    }

    val localLlmModelId: Flow<String> = dataStore.data.map { it[LOCAL_LLM_MODEL_ID] ?: "gemma4_e2b" }
    val localLlmModelIdBlocking: String
        get() = runBlocking { dataStore.data.first()[LOCAL_LLM_MODEL_ID] ?: "gemma4_e2b" }

    suspend fun setLocalLlmModelId(modelId: String) {
        dataStore.edit { it[LOCAL_LLM_MODEL_ID] = modelId }
    }
    val lastChatSessionId: Flow<String?> = dataStore.data.map { it[LAST_CHAT_SESSION_ID] }
    val chatRecallMode: Flow<ChatRecallMode> = dataStore.data.map {
        ChatRecallMode.fromString(it[CHAT_RECALL_MODE])
    }
    val chatRecallModeBlocking: ChatRecallMode
        get() = runBlocking { ChatRecallMode.fromString(dataStore.data.first()[CHAT_RECALL_MODE]) }

    // RAG settings
    val ragEnabled: Flow<Boolean> = dataStore.data.map { it[RAG_ENABLED] ?: true }
    val ragEnabledBlocking: Boolean
        get() = runBlocking { dataStore.data.first()[RAG_ENABLED] ?: true }
    val ragMaxExcerpts: Flow<Int> = dataStore.data.map { it[RAG_MAX_EXCERPTS] ?: 3 }
    val ragMaxExcerptsBlocking: Int
        get() = runBlocking { dataStore.data.first()[RAG_MAX_EXCERPTS] ?: 3 }
    val ragMaxContextTokens: Flow<Int> = dataStore.data.map { it[RAG_MAX_CONTEXT_TOKENS] ?: 500 }
    val ragMaxContextTokensBlocking: Int
        get() = runBlocking { dataStore.data.first()[RAG_MAX_CONTEXT_TOKENS] ?: 500 }

    // TTS settings
    val ttsEnabled: Flow<Boolean> = dataStore.data.map { it[TTS_ENABLED] ?: false }
    val ttsEnabledBlocking: Boolean
        get() = runBlocking { dataStore.data.first()[TTS_ENABLED] ?: false }
    val ttsVoice: Flow<String> = dataStore.data.map { it[TTS_VOICE] ?: "aura-2-thalia-en" }
    val ttsVoiceBlocking: String
        get() = runBlocking { dataStore.data.first()[TTS_VOICE] ?: "aura-2-thalia-en" }

    // Daily Digest settings
    val dailyDigestEnabled: Flow<Boolean> = dataStore.data.map { it[DAILY_DIGEST_ENABLED] ?: false }
    val dailyDigestEnabledBlocking: Boolean
        get() = runBlocking { dataStore.data.first()[DAILY_DIGEST_ENABLED] ?: false }

    // Recall Reminders settings
    val recallRemindersEnabled: Flow<Boolean> = dataStore.data.map { it[RECALL_REMINDERS_ENABLED] ?: false }
    val recallRemindersEnabledBlocking: Boolean
        get() = runBlocking { dataStore.data.first()[RECALL_REMINDERS_ENABLED] ?: false }

    // Encrypted flows for sensitive data
    private val _aiApiKey by lazy { MutableStateFlow(encryptedPrefs.getString(ENC_AI_API_KEY, "") ?: "") }
    val aiApiKey: Flow<String> by lazy { _aiApiKey.asStateFlow() }
    val aiApiKeyBlocking: String
        get() = encryptedPrefs.getString(ENC_AI_API_KEY, "") ?: ""

    private val _aiSearchApiKey by lazy { MutableStateFlow(encryptedPrefs.getString(ENC_AI_SEARCH_API_KEY, "") ?: "") }
    val aiSearchApiKey: Flow<String> by lazy { _aiSearchApiKey.asStateFlow() }
    val aiSearchApiKeyBlocking: String
        get() = encryptedPrefs.getString(ENC_AI_SEARCH_API_KEY, "") ?: ""

    private val _deepgramApiKey by lazy { MutableStateFlow(encryptedPrefs.getString(ENC_DEEPGRAM_API_KEY, "") ?: "") }
    val deepgramApiKey: Flow<String> by lazy { _deepgramApiKey.asStateFlow() }
    val deepgramApiKeyBlocking: String
        get() = encryptedPrefs.getString(ENC_DEEPGRAM_API_KEY, "") ?: ""

    private val _aiPresets: MutableStateFlow<List<AiPreset>> by lazy {
        val json = encryptedPrefs.getString(ENC_AI_PRESETS, "[]") ?: "[]"
        val type = object : TypeToken<List<AiPreset>>() {}.type
        MutableStateFlow(try { gson.fromJson(json, type) ?: emptyList() } catch (e: Exception) { emptyList() })
    }
    val aiPresets: Flow<List<AiPreset>> by lazy { _aiPresets.asStateFlow() }
    val aiPresetsBlocking: String
        get() = encryptedPrefs.getString(ENC_AI_PRESETS, "[]") ?: "[]"

    /**
     * Read all chat-related settings in a single DataStore snapshot.
     * Returns a data class so callers avoid 13+ individual blocking reads.
     */
    data class ChatConfig(
        val provider: String,
        val apiKey: String,
        val baseUrl: String,
        val model: String,
        val systemPrompt: String,
        val temperature: Float,
        val presencePenalty: Float,
        val topP: Float,
        val contextTokens: Int,
        val kvCache: Boolean,
        val webSearchEnabled: Boolean,
        val searchApiUrl: String,
        val searchApiKey: String,
        // RAG settings
        val ragEnabled: Boolean = true,
        val ragMaxExcerpts: Int = 3,
        val ragMaxContextTokens: Int = 500,
        val embeddingEnabled: Boolean = false,
        val embeddingModel: String = DEFAULT_EMBEDDING_MODEL,
        val citationEnabled: Boolean = true,
        val cloudFullNoteEnabled: Boolean = true,
        val maxRequestsPerMin: Int = 0,
        // RAG v2 settings
        val conceptGraphEnabled: Boolean = false,
        val multiHopEnabled: Boolean = false,
        val searchExplanationsEnabled: Boolean = false,
        val smartSnippetsEnabled: Boolean = false,
        val enableLocalLlmFallback: Boolean = true,
        val chatRecallMode: ChatRecallMode = ChatRecallMode.NOTES_ONLY
    )

    suspend fun readChatConfig(): ChatConfig {
        val p = dataStore.data.first()
        return ChatConfig(
            provider    = p[AI_PROVIDER]    ?: "Ollama",
            apiKey      = aiApiKeyBlocking,
            baseUrl     = p[AI_BASE_URL]    ?: "http://10.0.2.2:11434/",
            model       = p[AI_MODEL_NAME]  ?: "llama3.2",
            systemPrompt= p[AI_SYSTEM_PROMPT] ?: "You are a helpful AI assistant.",
            temperature = p[AI_TEMPERATURE] ?: 0.7f,
            presencePenalty = p[AI_PRESENCE_PENALTY] ?: 0.0f,
            topP        = p[AI_TOP_P]       ?: 1.0f,
            contextTokens = p[AI_CONTEXT_TOKENS] ?: 2048,
            kvCache     = p[AI_KV_CACHE]    ?: true,
            webSearchEnabled = p[AI_WEB_SEARCH_ENABLED] ?: false,
            searchApiUrl = p[AI_SEARCH_API_URL] ?: "",
            searchApiKey = aiSearchApiKeyBlocking,
            // RAG settings (read in same DataStore snapshot)
            ragEnabled  = p[RAG_ENABLED] ?: true,
            ragMaxExcerpts = p[RAG_MAX_EXCERPTS] ?: 3,
            ragMaxContextTokens = p[RAG_MAX_CONTEXT_TOKENS] ?: 500,
            embeddingEnabled = p[EMBEDDING_ENABLED] ?: false,
            embeddingModel = p[EMBEDDING_MODEL] ?: DEFAULT_EMBEDDING_MODEL,
            citationEnabled = p[CITATION_ENABLED] ?: true,
            cloudFullNoteEnabled = p[CLOUD_FULL_NOTE_ENABLED] ?: true,
            maxRequestsPerMin = p[AI_MAX_REQUESTS_PER_MIN] ?: 0,
            // RAG v2 settings (read in same DataStore snapshot)
            conceptGraphEnabled = p[CONCEPT_GRAPH_ENABLED] ?: false,
            multiHopEnabled = p[MULTI_HOP_ENABLED] ?: false,
            searchExplanationsEnabled = p[SEARCH_EXPLANATIONS_ENABLED] ?: false,
            smartSnippetsEnabled = p[SMART_SNIPPETS_ENABLED] ?: false,
            enableLocalLlmFallback = p[ENABLE_LOCAL_LLM_FALLBACK] ?: true,
            chatRecallMode = ChatRecallMode.fromString(p[CHAT_RECALL_MODE])
        )
    }

    /**
     * Retrieval pipeline config (guide §Phase 5), read in one DataStore snapshot so
     * disabling RAG and tuning weights never tear. Defaults mirror RetrievalConfig.
     */
    suspend fun readRetrievalConfig(): com.noteflowai.app.data.search.RetrievalConfig {
        val p = dataStore.data.first()
        return com.noteflowai.app.data.search.RetrievalConfig(
            bm25Weight = p[RETRIEVAL_BM25_WEIGHT] ?: 0.4f,
            vectorWeight = p[RETRIEVAL_VECTOR_WEIGHT] ?: 0.4f,
            entityWeight = p[RETRIEVAL_ENTITY_WEIGHT] ?: 0.12f,
            recencyWeight = p[RETRIEVAL_RECENCY_WEIGHT] ?: 0.08f,
            topK = p[RETRIEVAL_TOP_K] ?: 40,
            minimumScore = p[RETRIEVAL_MIN_SCORE] ?: 0.28f,
            maxContextTokens = p[RETRIEVAL_MAX_CONTEXT_TOKENS] ?: 1000
        )
    }

    /** Blocking variant for synchronous construction paths (Query Planner init). */
    val readRetrievalConfigBlocking: com.noteflowai.app.data.search.RetrievalConfig
        get() = runBlocking { readRetrievalConfig() }

    suspend fun setDeepgramApiKey(key: String) {
        encryptedPrefs.edit().putString(ENC_DEEPGRAM_API_KEY, key).commit()
        _deepgramApiKey.value = key
    }

    suspend fun setAiApiKey(key: String) {
        encryptedPrefs.edit().putString(ENC_AI_API_KEY, key).commit()
        _aiApiKey.value = key
    }

    suspend fun setAiSearchSettings(enabled: Boolean, apiUrl: String, apiKey: String) {
        encryptedPrefs.edit().putString(ENC_AI_SEARCH_API_KEY, apiKey).commit()
        _aiSearchApiKey.value = apiKey
        dataStore.edit {
            it[AI_WEB_SEARCH_ENABLED] = enabled
            it[AI_SEARCH_API_URL] = apiUrl
        }
    }

    suspend fun setLastChatSessionId(id: String?) {
        dataStore.edit {
            if (id == null) it.remove(LAST_CHAT_SESSION_ID)
            else it[LAST_CHAT_SESSION_ID] = id
        }
    }

    suspend fun setWhisperModel(model: String) {
        dataStore.edit { it[WHISPER_MODEL] = model }
    }

    suspend fun setCustomModelPath(path: String?) {
        dataStore.edit {
            if (path == null) it.remove(CUSTOM_MODEL_PATH)
            else it[CUSTOM_MODEL_PATH] = path
        }
    }

    suspend fun setDarkMode(enabled: Boolean) {
        dataStore.edit { it[IS_DARK_MODE] = enabled }
    }

    suspend fun setDarkModeOption(option: String) {
        val validated = if (option in listOf("light", "dark", "system")) option else "system"
        dataStore.edit { it[DARK_MODE_OPTION] = validated }
    }

    /**
     * Migrate from the old boolean IS_DARK_MODE to the new string DARK_MODE_OPTION.
     * - true  → "dark"
     * - false → "light"
     * New installs default to "system" (no migration needed).
     */
    suspend fun migrateDarkModeSetting() {
        dataStore.edit { prefs ->
            val oldValue = prefs[IS_DARK_MODE]
            if (oldValue != null && !prefs.contains(DARK_MODE_OPTION)) {
                prefs[DARK_MODE_OPTION] = if (oldValue) "dark" else "light"
            }
            prefs.remove(IS_DARK_MODE)
        }
    }

    suspend fun setTranslateMode(enabled: Boolean) {
        dataStore.edit { it[TRANSLATE_MODE] = enabled }
    }

    suspend fun setAiThinkingMode(enabled: Boolean) {
        dataStore.edit { it[AI_THINKING_MODE] = enabled }
    }

    suspend fun setTranscriptionLanguage(lang: String) {
        dataStore.edit { it[TRANSCRIPTION_LANGUAGE] = lang }
    }

    suspend fun setAppTheme(theme: String) {
        dataStore.edit { it[APP_THEME] = theme }
    }

    suspend fun setAppFont(font: String) {
        dataStore.edit { it[APP_FONT] = font }
    }

    suspend fun setOnlineMode(enabled: Boolean) {
        dataStore.edit { it[IS_ONLINE_MODE] = enabled }
    }

    suspend fun setChatRecallMode(mode: ChatRecallMode) {
        dataStore.edit { it[CHAT_RECALL_MODE] = mode.name }
    }

    suspend fun setRagEnabled(enabled: Boolean) {
        dataStore.edit { it[RAG_ENABLED] = enabled }
    }

    suspend fun setRagMaxExcerpts(value: Int) {
        dataStore.edit { it[RAG_MAX_EXCERPTS] = value.coerceIn(1, 10) }
    }

    suspend fun setRagMaxContextTokens(value: Int) {
        dataStore.edit { it[RAG_MAX_CONTEXT_TOKENS] = value.coerceIn(100, 2000) }
    }

    val embeddingEnabled: Flow<Boolean> = dataStore.data.map { it[EMBEDDING_ENABLED] ?: false }
    val embeddingEnabledBlocking: Boolean
        get() = runBlocking { dataStore.data.first()[EMBEDDING_ENABLED] ?: false }
    val embeddingModel: Flow<String> = dataStore.data.map { it[EMBEDDING_MODEL] ?: DEFAULT_EMBEDDING_MODEL }
    val embeddingModelBlocking: String
        get() = runBlocking { dataStore.data.first()[EMBEDDING_MODEL] ?: DEFAULT_EMBEDDING_MODEL }
    val citationEnabled: Flow<Boolean> = dataStore.data.map { it[CITATION_ENABLED] ?: true }
    val citationEnabledBlocking: Boolean
        get() = runBlocking { dataStore.data.first()[CITATION_ENABLED] ?: true }
    val rerankerEnabled: Flow<Boolean> = dataStore.data.map { it[RERANKER_ENABLED] ?: false }
    val rerankerEnabledBlocking: Boolean
        get() = runBlocking { dataStore.data.first()[RERANKER_ENABLED] ?: false }
    val cloudFullNoteEnabled: Flow<Boolean> = dataStore.data.map { it[CLOUD_FULL_NOTE_ENABLED] ?: true }
    val cloudFullNoteEnabledBlocking: Boolean
        get() = runBlocking { dataStore.data.first()[CLOUD_FULL_NOTE_ENABLED] ?: true }

    // RAG v2 settings
    val conceptGraphEnabled: Flow<Boolean> = dataStore.data.map { it[CONCEPT_GRAPH_ENABLED] ?: false }
    val conceptGraphEnabledBlocking: Boolean
        get() = runBlocking { dataStore.data.first()[CONCEPT_GRAPH_ENABLED] ?: false }
    val multiHopEnabled: Flow<Boolean> = dataStore.data.map { it[MULTI_HOP_ENABLED] ?: false }
    val multiHopEnabledBlocking: Boolean
        get() = runBlocking { dataStore.data.first()[MULTI_HOP_ENABLED] ?: false }
    val searchExplanationsEnabled: Flow<Boolean> = dataStore.data.map { it[SEARCH_EXPLANATIONS_ENABLED] ?: false }
    val searchExplanationsEnabledBlocking: Boolean
        get() = runBlocking { dataStore.data.first()[SEARCH_EXPLANATIONS_ENABLED] ?: false }
    val smartSnippetsEnabled: Flow<Boolean> = dataStore.data.map { it[SMART_SNIPPETS_ENABLED] ?: false }
    val smartSnippetsEnabledBlocking: Boolean
        get() = runBlocking { dataStore.data.first()[SMART_SNIPPETS_ENABLED] ?: false }

    suspend fun setEmbeddingEnabled(enabled: Boolean) {
        dataStore.edit { it[EMBEDDING_ENABLED] = enabled }
    }

    suspend fun setEmbeddingModel(model: String) {
        dataStore.edit { it[EMBEDDING_MODEL] = model }
    }

    suspend fun setCitationEnabled(enabled: Boolean) {
        dataStore.edit { it[CITATION_ENABLED] = enabled }
    }

    suspend fun setRerankerEnabled(enabled: Boolean) {
        dataStore.edit { it[RERANKER_ENABLED] = enabled }
    }

    suspend fun setCloudFullNoteEnabled(enabled: Boolean) {
        dataStore.edit { it[CLOUD_FULL_NOTE_ENABLED] = enabled }
    }

    suspend fun setConceptGraphEnabled(enabled: Boolean) {
        dataStore.edit { it[CONCEPT_GRAPH_ENABLED] = enabled }
    }

    suspend fun setMultiHopEnabled(enabled: Boolean) {
        dataStore.edit { it[MULTI_HOP_ENABLED] = enabled }
    }

    suspend fun setSearchExplanationsEnabled(enabled: Boolean) {
        dataStore.edit { it[SEARCH_EXPLANATIONS_ENABLED] = enabled }
    }

    suspend fun setSmartSnippetsEnabled(enabled: Boolean) {
        dataStore.edit { it[SMART_SNIPPETS_ENABLED] = enabled }
    }

    // Memory Layer feature flags (Phase 1-7) — DataStore-backed
    val enableSourceSegments: Flow<Boolean> = dataStore.data.map { it[ENABLE_SOURCE_SEGMENTS] ?: false }
    val enableSourceSegmentsBlocking: Boolean
        get() = runBlocking { dataStore.data.first()[ENABLE_SOURCE_SEGMENTS] ?: false }

    val enableMemoryDatabase: Flow<Boolean> = dataStore.data.map { it[ENABLE_MEMORY_DATABASE] ?: false }
    val enableMemoryDatabaseBlocking: Boolean
        get() = runBlocking { dataStore.data.first()[ENABLE_MEMORY_DATABASE] ?: false }

    val enableMemoryExtraction: Flow<Boolean> = dataStore.data.map { it[ENABLE_MEMORY_EXTRACTION] ?: false }
    val enableMemoryExtractionBlocking: Boolean
        get() = runBlocking { dataStore.data.first()[ENABLE_MEMORY_EXTRACTION] ?: false }

    val enableQueryPlanner: Flow<Boolean> = dataStore.data.map { it[ENABLE_QUERY_PLANNER] ?: false }
    val enableQueryPlannerBlocking: Boolean
        get() = runBlocking { dataStore.data.first()[ENABLE_QUERY_PLANNER] ?: false }

    val enableGroundedMemoryChat: Flow<Boolean> = dataStore.data.map { it[ENABLE_GROUNDED_MEMORY_CHAT] ?: false }
    val enableGroundedMemoryChatBlocking: Boolean
        get() = runBlocking { dataStore.data.first()[ENABLE_GROUNDED_MEMORY_CHAT] ?: false }

    val enableDecisionTimeline: Flow<Boolean> = dataStore.data.map { it[ENABLE_DECISION_TIMELINE] ?: false }
    val enableDecisionTimelineBlocking: Boolean
        get() = runBlocking { dataStore.data.first()[ENABLE_DECISION_TIMELINE] ?: false }

    val enableCommitmentDashboard: Flow<Boolean> = dataStore.data.map { it[ENABLE_COMMITMENT_DASHBOARD] ?: false }
    val enableCommitmentDashboardBlocking: Boolean
        get() = runBlocking { dataStore.data.first()[ENABLE_COMMITMENT_DASHBOARD] ?: false }

    val enableChangeAnalysis: Flow<Boolean> = dataStore.data.map { it[ENABLE_CHANGE_ANALYSIS] ?: false }
    val enableChangeAnalysisBlocking: Boolean
        get() = runBlocking { dataStore.data.first()[ENABLE_CHANGE_ANALYSIS] ?: false }

    val enableConflictDetection: Flow<Boolean> = dataStore.data.map { it[ENABLE_CONFLICT_DETECTION] ?: false }
    val enableConflictDetectionBlocking: Boolean
        get() = runBlocking { dataStore.data.first()[ENABLE_CONFLICT_DETECTION] ?: false }

    val enableWeeklyReview: Flow<Boolean> = dataStore.data.map { it[ENABLE_WEEKLY_REVIEW] ?: false }
    val enableWeeklyReviewBlocking: Boolean
        get() = runBlocking { dataStore.data.first()[ENABLE_WEEKLY_REVIEW] ?: false }

    val enableEvaluationDashboard: Flow<Boolean> = dataStore.data.map { it[ENABLE_EVALUATION_DASHBOARD] ?: false }
    val enableEvaluationDashboardBlocking: Boolean
        get() = runBlocking { dataStore.data.first()[ENABLE_EVALUATION_DASHBOARD] ?: false }

    val enableSegmentIndexing: Flow<Boolean> = dataStore.data.map { it[ENABLE_SEGMENT_INDEXING] ?: true }
    val enableSegmentIndexingBlocking: Boolean
        get() = runBlocking { dataStore.data.first()[ENABLE_SEGMENT_INDEXING] ?: true }

    suspend fun setEnableSourceSegments(enabled: Boolean) {
        dataStore.edit { it[ENABLE_SOURCE_SEGMENTS] = enabled }
    }

    suspend fun setEnableMemoryDatabase(enabled: Boolean) {
        dataStore.edit { it[ENABLE_MEMORY_DATABASE] = enabled }
    }

    suspend fun setEnableMemoryExtraction(enabled: Boolean) {
        dataStore.edit { it[ENABLE_MEMORY_EXTRACTION] = enabled }
    }

    suspend fun setEnableQueryPlanner(enabled: Boolean) {
        dataStore.edit { it[ENABLE_QUERY_PLANNER] = enabled }
    }

    suspend fun setEnableGroundedMemoryChat(enabled: Boolean) {
        dataStore.edit { it[ENABLE_GROUNDED_MEMORY_CHAT] = enabled }
    }

    suspend fun setEnableDecisionTimeline(enabled: Boolean) {
        dataStore.edit { it[ENABLE_DECISION_TIMELINE] = enabled }
    }

    suspend fun setEnableCommitmentDashboard(enabled: Boolean) {
        dataStore.edit { it[ENABLE_COMMITMENT_DASHBOARD] = enabled }
    }

    suspend fun setEnableChangeAnalysis(enabled: Boolean) {
        dataStore.edit { it[ENABLE_CHANGE_ANALYSIS] = enabled }
    }

    suspend fun setEnableConflictDetection(enabled: Boolean) {
        dataStore.edit { it[ENABLE_CONFLICT_DETECTION] = enabled }
    }

    suspend fun setEnableWeeklyReview(enabled: Boolean) {
        dataStore.edit { it[ENABLE_WEEKLY_REVIEW] = enabled }
    }

    suspend fun setEnableEvaluationDashboard(enabled: Boolean) {
        dataStore.edit { it[ENABLE_EVALUATION_DASHBOARD] = enabled }
    }

    suspend fun setEnableSegmentIndexing(enabled: Boolean) {
        dataStore.edit { it[ENABLE_SEGMENT_INDEXING] = enabled }
    }

    suspend fun setTtsEnabled(enabled: Boolean) {
        dataStore.edit { it[TTS_ENABLED] = enabled }
    }

    suspend fun setTtsVoice(voice: String) {
        dataStore.edit { it[TTS_VOICE] = voice }
    }

    // ── Search History ────────────────────────────────────────────────

    val searchHistory: Flow<List<String>> = dataStore.data.map { prefs ->
        (prefs[SEARCH_HISTORY] ?: "")
            .split("|||")
            .filter { it.isNotBlank() }
    }

    /** Record a search query, keeping at most [maxHistory] entries. Most recent first. */
    suspend fun addSearchHistory(query: String, maxHistory: Int = 10) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        dataStore.edit { prefs ->
            val current = (prefs[SEARCH_HISTORY] ?: "")
                .split("|||")
                .filter { it.isNotBlank() }
            val updated = (listOf(trimmed) + current)
                .distinct()
                .take(maxHistory)
            prefs[SEARCH_HISTORY] = updated.joinToString("|||")
        }
    }

    suspend fun removeSearchHistory(query: String) {
        dataStore.edit { prefs ->
            val current = (prefs[SEARCH_HISTORY] ?: "")
                .split("|||")
                .filter { it.isNotBlank() && it != query }
            prefs[SEARCH_HISTORY] = current.joinToString("|||")
        }
    }

    suspend fun clearSearchHistory() {
        dataStore.edit { it.remove(SEARCH_HISTORY) }
    }

    suspend fun setAiMaxRequestsPerMin(value: Int) {
        dataStore.edit { it[AI_MAX_REQUESTS_PER_MIN] = value.coerceAtLeast(0) }
    }

    val lastCloudProvider: Flow<String> = dataStore.data.map { it[LAST_CLOUD_PROVIDER] ?: "Gemini" }
    val lastCloudProviderBlocking: String
        get() = runBlocking { dataStore.data.first()[LAST_CLOUD_PROVIDER] ?: "Gemini" }

    val lastCloudBaseUrl: Flow<String> = dataStore.data.map {
        it[LAST_CLOUD_BASE_URL] ?: "https://generativelanguage.googleapis.com/v1beta/openai/"
    }
    val lastCloudBaseUrlBlocking: String
        get() = runBlocking {
            dataStore.data.first()[LAST_CLOUD_BASE_URL] ?: "https://generativelanguage.googleapis.com/v1beta/openai/"
        }

    val lastCloudModelName: Flow<String> = dataStore.data.map { it[LAST_CLOUD_MODEL_NAME] ?: "gemini-1.5-flash" }
    val lastCloudModelNameBlocking: String
        get() = runBlocking { dataStore.data.first()[LAST_CLOUD_MODEL_NAME] ?: "gemini-1.5-flash" }

    val lastCloudApiKeyBlocking: String
        get() {
            val savedKey = encryptedPrefs.getString(ENC_LAST_CLOUD_API_KEY, "") ?: ""
            if (savedKey.isNotBlank()) return savedKey
            return encryptedPrefs.getString(ENC_AI_API_KEY, "") ?: ""
        }

    suspend fun updateAiSettings(
        provider: String,
        apiKey: String,
        baseUrl: String,
        modelName: String,
        systemPrompt: String,
        temperature: Float,
        presencePenalty: Float,
        topP: Float = 1.0f,
        contextTokens: Int = 2048,
        useKvCache: Boolean = true
    ) {
        val isLocal = provider.equals("Local", ignoreCase = true) || provider.equals("Local (On-Device)", ignoreCase = true)
        if (!isLocal) {
            // Save as the latest configured Cloud AI settings
            encryptedPrefs.edit().putString(ENC_LAST_CLOUD_API_KEY, apiKey).apply()
        }

        // Store active API key in encrypted storage
        setAiApiKey(apiKey)

        // Store non-sensitive settings in DataStore in a single atomic edit
        dataStore.edit {
            if (!isLocal) {
                it[LAST_CLOUD_PROVIDER] = provider
                it[LAST_CLOUD_BASE_URL] = baseUrl
                it[LAST_CLOUD_MODEL_NAME] = modelName
                it[LAST_CLOUD_SYSTEM_PROMPT] = systemPrompt
                it[LAST_CLOUD_TEMPERATURE] = temperature
                it[LAST_CLOUD_PRESENCE_PENALTY] = presencePenalty
                it[LAST_CLOUD_TOP_P] = topP
                it[LAST_CLOUD_CONTEXT_TOKENS] = contextTokens
                it[LAST_CLOUD_KV_CACHE] = useKvCache
            }

            it[AI_PROVIDER] = provider
            it[AI_BASE_URL] = baseUrl
            it[AI_MODEL_NAME] = modelName
            it[AI_SYSTEM_PROMPT] = systemPrompt
            it[AI_TEMPERATURE] = temperature
            it[AI_PRESENCE_PENALTY] = presencePenalty
            it[AI_TOP_P] = topP
            it[AI_CONTEXT_TOKENS] = contextTokens
            it[AI_KV_CACHE] = useKvCache
        }
    }

    /**
     * Restores the latest previous Cloud AI configuration (provider, API key, base URL, model name, etc.).
     * Returns the name of the restored provider.
     */
    suspend fun restoreLastCloudAiConfig(): String {
        val p = dataStore.data.first()
        val provider = p[LAST_CLOUD_PROVIDER] ?: "Gemini"
        val baseUrl = p[LAST_CLOUD_BASE_URL] ?: "https://generativelanguage.googleapis.com/v1beta/openai/"
        val modelName = p[LAST_CLOUD_MODEL_NAME] ?: "gemini-1.5-flash"
        val apiKey = lastCloudApiKeyBlocking
        val systemPrompt = p[LAST_CLOUD_SYSTEM_PROMPT] ?: p[AI_SYSTEM_PROMPT] ?: "You are a helpful AI assistant."
        val temperature = p[LAST_CLOUD_TEMPERATURE] ?: p[AI_TEMPERATURE] ?: 0.7f
        val presencePenalty = p[LAST_CLOUD_PRESENCE_PENALTY] ?: p[AI_PRESENCE_PENALTY] ?: 0.0f
        val topP = p[LAST_CLOUD_TOP_P] ?: p[AI_TOP_P] ?: 1.0f
        val contextTokens = p[LAST_CLOUD_CONTEXT_TOKENS] ?: p[AI_CONTEXT_TOKENS] ?: 2048
        val useKvCache = p[LAST_CLOUD_KV_CACHE] ?: p[AI_KV_CACHE] ?: true

        setAiApiKey(apiKey)
        dataStore.edit {
            it[AI_PROVIDER] = provider
            it[AI_BASE_URL] = baseUrl
            it[AI_MODEL_NAME] = modelName
            it[AI_SYSTEM_PROMPT] = systemPrompt
            it[AI_TEMPERATURE] = temperature
            it[AI_PRESENCE_PENALTY] = presencePenalty
            it[AI_TOP_P] = topP
            it[AI_CONTEXT_TOKENS] = contextTokens
            it[AI_KV_CACHE] = useKvCache
        }
        return provider
    }

    /**
     * Switches to the active Local On-Device AI configuration.
     * Preserves the active cloud configuration beforehand if coming from Cloud AI.
     * Returns the active local model name.
     */
    suspend fun switchToLocalAiConfig(): String {
        val p = dataStore.data.first()
        val currentProvider = p[AI_PROVIDER] ?: "Ollama"
        val isCurrentLocal = currentProvider.equals("Local", ignoreCase = true) || currentProvider.equals("Local (On-Device)", ignoreCase = true)

        if (!isCurrentLocal) {
            val currentApiKey = aiApiKeyBlocking
            if (currentApiKey.isNotBlank()) {
                encryptedPrefs.edit().putString(ENC_LAST_CLOUD_API_KEY, currentApiKey).apply()
            }
        }

        val activeLocalModelId = p[LOCAL_LLM_MODEL_ID] ?: "gemma4_e2b"
        val localModelInfo = com.noteflowai.app.data.llm.AvailableModels.get(com.noteflowai.app.data.llm.ModelId.fromId(activeLocalModelId))
        val localModelName = localModelInfo.name

        val systemPrompt = p[AI_SYSTEM_PROMPT] ?: "You are a helpful AI assistant."
        val temperature = p[AI_TEMPERATURE] ?: 0.7f
        val presencePenalty = p[AI_PRESENCE_PENALTY] ?: 0.0f
        val topP = p[AI_TOP_P] ?: 1.0f
        val contextTokens = p[AI_CONTEXT_TOKENS] ?: 2048
        val useKvCache = p[AI_KV_CACHE] ?: true

        setAiApiKey("")
        dataStore.edit {
            if (!isCurrentLocal) {
                it[LAST_CLOUD_PROVIDER] = currentProvider
                it[LAST_CLOUD_BASE_URL] = p[AI_BASE_URL] ?: "https://generativelanguage.googleapis.com/v1beta/openai/"
                it[LAST_CLOUD_MODEL_NAME] = p[AI_MODEL_NAME] ?: "gemini-1.5-flash"
                it[LAST_CLOUD_SYSTEM_PROMPT] = systemPrompt
                it[LAST_CLOUD_TEMPERATURE] = temperature
                it[LAST_CLOUD_PRESENCE_PENALTY] = presencePenalty
                it[LAST_CLOUD_TOP_P] = topP
                it[LAST_CLOUD_CONTEXT_TOKENS] = contextTokens
                it[LAST_CLOUD_KV_CACHE] = useKvCache
            }

            it[AI_PROVIDER] = "Local"
            it[AI_BASE_URL] = "local"
            it[AI_MODEL_NAME] = localModelName
            it[AI_SYSTEM_PROMPT] = systemPrompt
            it[AI_TEMPERATURE] = temperature
            it[AI_PRESENCE_PENALTY] = presencePenalty
            it[AI_TOP_P] = topP
            it[AI_CONTEXT_TOKENS] = contextTokens
            it[AI_KV_CACHE] = useKvCache
        }
        return localModelName
    }

    suspend fun saveAiPreset(preset: AiPreset) {
        val list = _aiPresets.value.toMutableList()
        list.add(preset)
        encryptedPrefs.edit().putString(ENC_AI_PRESETS, gson.toJson(list)).apply()
        _aiPresets.value = list
    }

    suspend fun deleteAiPreset(presetId: String) {
        val list = _aiPresets.value.toMutableList()
        list.removeAll { it.id == presetId }
        encryptedPrefs.edit().putString(ENC_AI_PRESETS, gson.toJson(list)).apply()
        _aiPresets.value = list
    }

    /**
     * Replace all AI presets from a raw JSON string (used during backup restore).
     */
    suspend fun setAiPresets(presetsJson: String) {
        val type = object : TypeToken<List<AiPreset>>() {}.type
        val list: List<AiPreset> = try { gson.fromJson(presetsJson, type) ?: emptyList() } catch (e: Exception) { emptyList() }
        encryptedPrefs.edit().putString(ENC_AI_PRESETS, presetsJson).apply()
        _aiPresets.value = list
    }

    suspend fun loadAiPreset(preset: AiPreset) {
        updateAiSettings(
            provider = preset.provider,
            apiKey = preset.apiKey ?: "",
            baseUrl = preset.baseUrl,
            modelName = preset.modelName,
            systemPrompt = preset.systemPrompt,
            temperature = preset.temperature,
            presencePenalty = preset.presencePenalty,
            topP = preset.topP ?: 1.0f,
            contextTokens = preset.contextTokens ?: 2048,
            useKvCache = preset.useKvCache ?: true
        )
    }

    suspend fun setDailyDigestEnabled(enabled: Boolean) {
        dataStore.edit { it[DAILY_DIGEST_ENABLED] = enabled }
    }

    suspend fun setRecallRemindersEnabled(enabled: Boolean) {
        dataStore.edit { it[RECALL_REMINDERS_ENABLED] = enabled }
    }
}
