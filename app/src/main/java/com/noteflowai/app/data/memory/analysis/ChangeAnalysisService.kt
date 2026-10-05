package com.noteflowai.app.data.memory.analysis

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.noteflowai.app.data.chat.AiChatRepository
import com.noteflowai.app.data.chat.ChatMessage
import com.noteflowai.app.data.memory.dao.DecisionDao
import com.noteflowai.app.data.memory.dao.MemoryObjectDao
import com.noteflowai.app.data.memory.db.MemoryDatabase
import com.noteflowai.app.data.memory.model.Decision
import com.noteflowai.app.data.memory.model.DecisionStatus
import com.noteflowai.app.data.memory.model.MemoryObject
import com.noteflowai.app.data.memory.model.MemoryType
import com.noteflowai.app.data.settings.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Provides chronological, evidence-backed analysis of changing opinions,
 * decisions, dates, and project states.
 *
 * Uses the LLM to classify changes conservatively. Never asserts a
 * contradiction unless evidence clearly supports it.
 */
class ChangeAnalysisService(
    private val memoryObjectDao: MemoryObjectDao,
    private val decisionDao: DecisionDao,
    private val aiChatRepository: AiChatRepository,
    private val settingsManager: SettingsManager,
    private val entityDao: com.noteflowai.app.data.memory.dao.EntityDao? = null,
    private val entityMentionDao: com.noteflowai.app.data.memory.dao.EntityMentionDao? = null,
    private val liteRtInferenceManager: com.noteflowai.app.data.LiteRtInferenceManager? = null
) {

    constructor(context: Context, liteRtInferenceManager: com.noteflowai.app.data.LiteRtInferenceManager? = null) : this(
        MemoryDatabase.getInstance(context).memoryObjectDao(),
        MemoryDatabase.getInstance(context).decisionDao(),
        AiChatRepository(),
        SettingsManager.getInstance(context),
        MemoryDatabase.getInstance(context).entityDao(),
        MemoryDatabase.getInstance(context).entityMentionDao(),
        liteRtInferenceManager ?: com.noteflowai.app.data.LiteRtInferenceManager(context)
    )

    private val gson = Gson()

    companion object {
        private const val TAG = "ChangeAnalysisService"
    }

    data class TimelineEntry(
        val date: String,
        val statement: String,
        val changeType: String,
        val sourceSegmentIds: List<String>,
        val memoryObjectIds: List<String>,
        val confidence: Float
    )

    data class ChangeAnalysisResult(
        val topic: String,
        val timeline: List<TimelineEntry>,
        val currentInterpretation: String,
        val confidence: Float,
        val needsUserConfirmation: Boolean
    )

    /**
     * Analyze changes in thinking about a topic over time.
     *
     * @param topic Optional topic to focus on (free text).
     * @param entity Optional entity name to filter by.
     * @param project Optional project entity ID.
     * @param dateRange Optional Pair(startMs, endMs) date range.
     * @return ChangeAnalysisResult with timeline and interpretation.
     */
    suspend fun analyze(
        topic: String? = null,
        entity: String? = null,
        project: String? = null,
        dateRange: Pair<Long, Long>? = null
    ): ChangeAnalysisResult = withContext(Dispatchers.IO) {
        val settings = settingsManager
        val (baseUrl, apiKey, model, provider) = coroutineScope {
            listOf(
                async { settings.aiBaseUrl.first() },
                async { settings.aiApiKey.first() },
                async { settings.aiModelName.first() },
                async { settings.aiProvider.first() }
            ).map { it.await() }
        }

        val isLocalOnly = settings.isLocalOnlyMode.first()
        val isRemoteConfigured = baseUrl.isNotBlank() && (provider == "Ollama" || provider == "LM Studio" || apiKey.isNotBlank())
        val isRemoteAllowed = isRemoteConfigured && (!isLocalOnly || com.noteflowai.app.data.NetworkModule.isLoopbackUrl(baseUrl))
        val hasLocalModel = liteRtInferenceManager?.getModelPath() != null

        if (!isRemoteConfigured && !hasLocalModel) {
            return@withContext ChangeAnalysisResult(
                topic = topic ?: "all",
                timeline = emptyList(),
                currentInterpretation = "AI not configured for change analysis.",
                confidence = 0f,
                needsUserConfirmation = false
            )
        }

        // Local-Only Mode: memory text must not leave the device. Loopback
        // endpoints (on-device Ollama/LM Studio) stay allowed.
        if (isLocalOnly && !com.noteflowai.app.data.NetworkModule.isLoopbackUrl(baseUrl) && !hasLocalModel) {
            return@withContext ChangeAnalysisResult(
                topic = topic ?: "all",
                timeline = emptyList(),
                currentInterpretation = "Local-Only Mode: remote change analysis disabled.",
                confidence = 0f,
                needsUserConfirmation = false
            )
        }

        // Gather relevant data. An entity filter narrows to entries whose
        // segment mentions the entity — it used to only decorate the prompt
        // while the corpus stayed unfiltered.
        val entitySegmentIds = resolveEntitySegments(entity)
        if (entity != null && entitySegmentIds != null && entitySegmentIds.isEmpty()) {
            return@withContext ChangeAnalysisResult(
                topic = entity,
                timeline = emptyList(),
                currentInterpretation = "No entries mention '$entity'.",
                confidence = 0f,
                needsUserConfirmation = false
            )
        }
        val memoryObjects = gatherMemoryObjects(dateRange, project)
            .filter { entitySegmentIds == null || it.sourceSegmentId in entitySegmentIds }
        val decisions = gatherDecisions(dateRange, project)
            .filter { entitySegmentIds == null || it.sourceSegmentId in entitySegmentIds }
        val allEntries = buildChronologicalEntries(memoryObjects, decisions)

        if (allEntries.isEmpty()) {
            return@withContext ChangeAnalysisResult(
                topic = topic ?: "all",
                timeline = emptyList(),
                currentInterpretation = "No relevant entries found for the specified criteria.",
                confidence = 0f,
                needsUserConfirmation = false
            )
        }

        // Build LLM prompt
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val systemPrompt = buildSystemPrompt()
        val userMessage = buildUserMessage(
            topic = topic,
            entity = entity,
            entries = allEntries,
            dateFormat = dateFormat
        )

        var responseText: String? = null
        var lastError: Exception? = null

        if (isRemoteAllowed) {
            try {
                val messages = listOf(
                    ChatMessage(role = "user", content = userMessage)
                )
                val response = aiChatRepository.getResponse(
                    baseUrl = baseUrl,
                    provider = provider,
                    apiKey = apiKey,
                    model = model,
                    systemPrompt = systemPrompt,
                    messages = messages,
                    temperature = 0.1f
                )
                responseText = response.content
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Remote change analysis failed, checking local fallback: ${e.message}")
            }
        }

        // Fallback to local LLM if remote wasn't allowed or failed
        if (responseText.isNullOrBlank() && hasLocalModel && liteRtInferenceManager != null) {
            try {
                Log.i(TAG, "Running change analysis via local LiteRT-LM...")
                val localPrompt = "<start_of_turn>user\n$systemPrompt\n\n$userMessage<end_of_turn>\n<start_of_turn>model\n"
                val localResult = liteRtInferenceManager.generate(
                    prompt = localPrompt,
                    maxTokens = 1024,
                    temperature = 0.1f
                )
                if (!localResult.startsWith("Error:") && localResult.isNotBlank()) {
                    responseText = localResult
                }
            } catch (e: Exception) {
                Log.w(TAG, "Local change analysis failed: ${e.message}", e)
            }
        }

        if (responseText.isNullOrBlank()) {
            val failureMessage = when {
                lastError != null -> "Analysis failed: ${lastError.message}"
                isRemoteAllowed -> "Empty response from AI."
                else -> "Analysis failed: No AI engine available."
            }
            return@withContext ChangeAnalysisResult(
                topic = topic ?: "all",
                timeline = emptyList(),
                currentInterpretation = failureMessage,
                confidence = 0f,
                needsUserConfirmation = true
            )
        }

        parseResponse(responseText, topic ?: "all")
    }

    /**
     * Segment ids that mention [entityName], or null when no entity filter
     * applies (null entity, or no entity DAOs wired in tests). An empty set
     * means the entity name resolved to nothing — the caller reports that
     * instead of analyzing the unfiltered corpus.
     */
    private suspend fun resolveEntitySegments(entityName: String?): Set<String>? {
        if (entityName.isNullOrBlank()) return null
        val entityDao = this.entityDao ?: return null
        val mentionDao = this.entityMentionDao ?: return null
        return try {
            val entity = entityDao.getByNormalizedName(entityName.lowercase().trim())
                ?: return emptySet()
            mentionDao.getByEntityId(entity.id).map { it.sourceSegmentId }.toSet()
        } catch (e: Exception) {
            Log.w(TAG, "Entity filter lookup failed: ${e.message}")
            null
        }
    }

    private suspend fun gatherMemoryObjects(
        dateRange: Pair<Long, Long>?,
        project: String?
    ): List<MemoryObject> {
        return when {
            dateRange != null && project != null -> {
                memoryObjectDao.getByDateRange(dateRange.first, dateRange.second)
                    .filter { it.projectEntityId == project }
            }
            dateRange != null -> {
                memoryObjectDao.getByDateRange(dateRange.first, dateRange.second)
            }
            project != null -> {
                memoryObjectDao.getByProject(project)
            }
            else -> {
                // Last 90 days by default
                val now = System.currentTimeMillis()
                val ninetyDaysAgo = now - 90L * 24 * 60 * 60 * 1000
                memoryObjectDao.getByDateRange(ninetyDaysAgo, now)
            }
        }
    }

    private suspend fun gatherDecisions(
        dateRange: Pair<Long, Long>?,
        project: String?
    ): List<Decision> {
        return when {
            dateRange != null && project != null -> {
                decisionDao.getByDateRange(dateRange.first, dateRange.second)
                    .filter { it.projectEntityId == project }
            }
            dateRange != null -> {
                decisionDao.getByDateRange(dateRange.first, dateRange.second)
            }
            project != null -> {
                decisionDao.getByProject(project)
            }
            else -> {
                decisionDao.getTimeline().take(100)
            }
        }
    }

    private fun buildChronologicalEntries(
        memoryObjects: List<MemoryObject>,
        decisions: List<Decision>
    ): List<Map<String, Any>> {
        val entries = mutableListOf<Map<String, Any>>()

        for (obj in memoryObjects) {
            entries.add(mapOf(
                "date" to obj.extractedAt,
                "text" to obj.statement,
                "type" to obj.type.name,
                "status" to obj.status.name,
                "sourceSegmentId" to obj.sourceSegmentId,
                "objectId" to obj.id
            ))
        }

        for (dec in decisions) {
            entries.add(mapOf(
                "date" to (dec.decidedAt ?: dec.createdAt),
                "text" to dec.statement + (dec.reason?.let { " Reason: $it" } ?: ""),
                "type" to "DECISION",
                "status" to dec.status.name,
                "sourceSegmentId" to dec.sourceSegmentId,
                "objectId" to dec.id
            ))
        }

        return entries.sortedBy { it["date"] as Long }
    }

    private fun buildSystemPrompt(): String {
        return """You analyze a chronological list of the user's memory entries (decisions, opinions, facts, commitments) and classify how thinking may have changed over time.

You must return ONLY valid JSON matching this schema:
{
  "topic": "string",
  "timeline": [
    {
      "date": "ISO-8601 date string",
      "statement": "the entry text",
      "changeType": "INITIAL_VIEW|CONCERN|CLARIFICATION|NEW_DECISION|POSSIBLE_CHANGE|DIRECT_REVERSAL|DEADLINE_CHANGE|STATUS_CHANGE",
      "sourceSegmentIds": ["source segment ID if available"],
      "memoryObjectIds": ["memory object ID"],
      "confidence": 0.0
    }
  ],
  "currentInterpretation": "string summarizing the current state",
  "confidence": 0.0,
  "needsUserConfirmation": true
}

Rules:
- Use conservative language: "This may represent a change", "The sources differ"
- Never declare a contradiction from semantic similarity alone
- Never declare a change solely because wording differs
- Never automatically mark old decisions as wrong
- Never automatically choose the newest source as correct
- INITIAL_VIEW: first mention of a topic
- CONCERN: expression of worry or uncertainty
- CLARIFICATION: refinement of an earlier statement
- NEW_DECISION: a new decision was made
- POSSIBLE_CHANGE: wording suggests a shift but is not certain
- DIRECT_REVERSAL: clear reversal of a previous decision
- DEADLINE_CHANGE: a deadline or due date changed
- STATUS_CHANGE: status of a project or commitment changed
- Always set needsUserConfirmation to true
- Confidence should reflect how certain you are about the classification (0.0-1.0)
- Include sourceSegmentIds and memoryObjectIds from the input entries"""
    }

    private fun buildUserMessage(
        topic: String?,
        entity: String?,
        entries: List<Map<String, Any>>,
        dateFormat: SimpleDateFormat
    ): String {
        val sb = StringBuilder()
        if (topic != null || entity != null) {
            sb.appendLine("Focus area: ${topic ?: entity}")
            sb.appendLine()
        }
        sb.appendLine("Chronological entries:")
        sb.appendLine()
        for (entry in entries) {
            val date = dateFormat.format(Date(entry["date"] as Long))
            val type = entry["type"]
            val status = entry["status"]
            val text = entry["text"]
            val sourceId = entry["sourceSegmentId"]
            val objectId = entry["objectId"]
            sb.appendLine("[$date] ($type/$status) $text")
            sb.appendLine("  sourceSegmentId=$sourceId objectId=$objectId")
        }
        sb.appendLine()
        sb.appendLine("Analyze these entries for changes in thinking. Return ONLY the JSON response.")
        return sb.toString()
    }

    private fun parseResponse(responseText: String, topic: String): ChangeAnalysisResult {
        return try {
            // Balanced-brace carve (may be wrapped in markdown code block). The
            // old greedy \{.*\} fallback grabbed trailing prose and zeroed the
            // analysis.
            val jsonStr = com.noteflowai.app.data.memory.extraction.JsonExtraction
                .extractJsonObject(responseText) ?: return ChangeAnalysisResult(
                    topic = topic,
                    timeline = emptyList(),
                    currentInterpretation = "Failed to parse analysis response.",
                    confidence = 0f,
                    needsUserConfirmation = true
                )
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val map: Map<String, Any> = gson.fromJson(jsonStr, type)

            val timelineRaw = map["timeline"] as? List<*> ?: emptyList<Any>()
            val timeline = timelineRaw.mapNotNull { item ->
                if (item is Map<*, *>) {
                    TimelineEntry(
                        date = item["date"]?.toString() ?: "",
                        statement = item["statement"]?.toString() ?: "",
                        changeType = item["changeType"]?.toString() ?: "POSSIBLE_CHANGE",
                        sourceSegmentIds = (item["sourceSegmentIds"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList(),
                        memoryObjectIds = (item["memoryObjectIds"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList(),
                        confidence = (item["confidence"] as? Number)?.toFloat() ?: 0.5f
                    )
                } else null
            }

            ChangeAnalysisResult(
                topic = map["topic"]?.toString() ?: topic,
                timeline = timeline,
                currentInterpretation = map["currentInterpretation"]?.toString() ?: "",
                confidence = (map["confidence"] as? Number)?.toFloat() ?: 0.5f,
                needsUserConfirmation = true
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse change analysis response", e)
            ChangeAnalysisResult(
                topic = topic,
                timeline = emptyList(),
                currentInterpretation = "Failed to parse analysis response.",
                confidence = 0f,
                needsUserConfirmation = true
            )
        }
    }

}
