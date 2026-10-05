package com.noteflowai.app.data.memory.extraction

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.noteflowai.app.data.chat.AiChatRepository
import com.noteflowai.app.data.chat.ChatMessage
import com.noteflowai.app.data.memory.model.Commitment
import com.noteflowai.app.data.memory.model.CommitmentStatus
import com.noteflowai.app.data.memory.model.Decision
import com.noteflowai.app.data.memory.model.DecisionStatus
import com.noteflowai.app.data.memory.model.Entity
import com.noteflowai.app.data.memory.model.EntityMention
import com.noteflowai.app.data.memory.model.EntityType
import com.noteflowai.app.data.memory.model.MemoryObject
import com.noteflowai.app.data.memory.model.MemoryObjectStatus
import com.noteflowai.app.data.memory.model.MemoryRelation
import com.noteflowai.app.data.memory.model.MemoryReviewItem
import com.noteflowai.app.data.memory.model.MemoryType
import com.noteflowai.app.data.memory.model.RelationType
import com.noteflowai.app.data.memory.model.ReviewItemType
import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.repository.CommitmentRepository
import com.noteflowai.app.data.memory.repository.DecisionRepository
import com.noteflowai.app.data.memory.repository.EntityRepository
import com.noteflowai.app.data.memory.repository.MemoryRepository
import com.noteflowai.app.data.memory.repository.MemoryReviewRepository
import com.noteflowai.app.data.memory.timeline.TimelineRepository
import com.noteflowai.app.data.settings.SettingsManager
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * Orchestrates memory extraction from source segments.
 * Sends text to the LLM, parses the response, validates it,
 * and stores the results in the database with review items.
 *
 * Conservative rules:
 * - All extracted decisions/commitments start as DETECTED
 * - User confirmation is required before anything becomes ACTIVE
 * - Due dates and owners are only set if explicitly stated
 */
class MemoryExtractionService(private val context: Context) {

    private val gson = Gson()
    private val aiChatRepository = AiChatRepository()
    private val entityRepository = EntityRepository(context)
    private val memoryRepository = MemoryRepository(context)
    private val decisionRepository = DecisionRepository(context)
    private val commitmentRepository = CommitmentRepository(context)
    private val reviewRepository = MemoryReviewRepository(context)
    private val timelineRepository = TimelineRepository(context)
    private val relationDao by lazy {
        com.noteflowai.app.data.memory.db.MemoryDatabase.getInstance(context).memoryRelationDao()
    }

    companion object {
        private const val TAG = "MemoryExtractionService"

        @Volatile
        private var INSTANCE: MemoryExtractionService? = null

        fun getInstance(context: Context): MemoryExtractionService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MemoryExtractionService(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    /**
     * Extract entities and memory objects from source segments.
     * This is the main entry point called by the worker.
     *
     * @param segments The source segments to extract from.
     * @return ExtractionResult with counts of what was extracted.
     */
    suspend fun extract(segments: List<SourceSegment>): ExtractionResult {
        if (segments.isEmpty()) {
            return ExtractionResult(0, 0, 0, 0, 0, emptyList())
        }

        val settings = SettingsManager.getInstance(context)
        // One concurrent read instead of four sequential DataStore hits.
        val (baseUrl, apiKey, model, provider) = coroutineScope {
            val baseUrlAsync = async { settings.aiBaseUrl.first() }
            val apiKeyAsync = async { settings.aiApiKey.first() }
            val modelAsync = async { settings.aiModelName.first() }
            val providerAsync = async { settings.aiProvider.first() }
            listOf(
                baseUrlAsync.await(),
                apiKeyAsync.await(),
                modelAsync.await(),
                providerAsync.await()
            )
        }

        if (baseUrl.isBlank() || (provider != "Ollama" && provider != "LM Studio" && apiKey.isBlank())) {
            Log.w(TAG, "AI not configured, skipping extraction")
            return ExtractionResult(0, 0, 0, 0, 0, listOf("AI not configured"))
        }

        // Local-Only Mode: segment text must not leave the device. Loopback
        // endpoints (on-device Ollama/LM Studio) stay allowed.
        if (settings.isLocalOnlyMode.first() &&
            !com.noteflowai.app.data.NetworkModule.isLoopbackUrl(baseUrl)
        ) {
            Log.w(TAG, "Local-Only Mode: skipping remote extraction (non-loopback endpoint)")
            return ExtractionResult(0, 0, 0, 0, 0, listOf("Local-Only Mode: remote extraction disabled"))
        }

        // Build input segments
        val inputSegments = segments.map { seg ->
            ExtractionInputSegment(
                segmentId = seg.id,
                sourceId = seg.sourceId,
                sourceType = seg.sourceType.name,
                text = seg.text,
                date = seg.createdAt.takeIf { it > 0 }?.let {
                    java.time.Instant.ofEpochMilli(it).toString()
                },
                speaker = seg.speaker
            )
        }

        val validSegmentIds = segments.map { it.id }.toSet()

        // Build prompt
        val systemPrompt = ExtractionPrompt.buildSystemPrompt()
        val userMessage = ExtractionPrompt.buildUserMessage(inputSegments)

        val messages = listOf(
            ChatMessage(role = "user", content = userMessage)
        )

        return try {
            // Call LLM (non-streaming for extraction)
            val response = aiChatRepository.getResponse(
                baseUrl = baseUrl,
                provider = provider,
                apiKey = apiKey,
                model = model,
                systemPrompt = systemPrompt,
                messages = messages,
                temperature = 0.1f // Low temperature for precise extraction
            )
            val responseText = response.content

            if (responseText.isNullOrBlank()) {
                return ExtractionResult(0, 0, 0, 0, 0, listOf("Empty response from LLM"))
            }

            // Parse JSON response
            val extractionResponse = parseResponse(responseText)
            if (extractionResponse == null) {
                return ExtractionResult(0, 0, 0, 0, 0, listOf("Failed to parse LLM response as JSON"))
            }

            // Validate
            val validation = ExtractionValidator.validate(extractionResponse, validSegmentIds)

            // Store results. Store failures are collected (not just logged):
            // the pipeline marks success while data is lost if they vanish.
            val storeErrors = mutableListOf<String>()
            var entitiesCreated = 0
            var decisionsCreated = 0
            var commitmentsCreated = 0
            var otherMemoryCreated = 0
            var timelineCreated = 0
            var relationsCreated = 0

            // Store entities
            for (extractedEntity in validation.entities) {
                try {
                    val entityType = EntityType.valueOf(extractedEntity.type)
                    val normalizedName = extractedEntity.canonical_name.lowercase(java.util.Locale.ROOT).trim()
                    val entity = Entity(
                        id = "ent_${UUID.randomUUID()}",
                        type = entityType,
                        canonicalName = extractedEntity.canonical_name,
                        normalizedName = normalizedName,
                        confidence = extractedEntity.confidence.coerceIn(0f, 1f),
                        aliasesJson = if (extractedEntity.aliases.isNotEmpty()) gson.toJson(extractedEntity.aliases) else null
                    )
                    // Link entity mention to the correct source segment. A
                    // missing/invalid segment id skips the entity — attributing
                    // it to the first segment would corrupt citations.
                    val segmentId = extractedEntity.source_segment_id
                        ?.takeIf { it in validSegmentIds }
                    if (segmentId == null) {
                        storeErrors.add("Entity '${extractedEntity.canonical_name}' has no valid segment; skipped")
                        continue
                    }
                    val mention = EntityMention(
                        entityId = entity.id,
                        sourceSegmentId = segmentId,
                        mentionText = extractedEntity.canonical_name,
                        confidence = extractedEntity.confidence.coerceIn(0f, 1f)
                    )
                    entityRepository.insertWithMention(entity, mention)
                    entitiesCreated++
                } catch (e: Exception) {
                    Log.e(TAG, "Error storing entity: ${e.message}")
                    storeErrors.add("Entity '${extractedEntity.canonical_name}': ${e.message}")
                }
            }

            // Store memory objects. Re-extraction dedups against the database:
            // the same normalized statement on the same segment is skipped, so
            // decisions, commitments, and their review items never stack.
            for (extractedObj in validation.memoryObjects) {
                try {
                    val memoryType = MemoryType.valueOf(extractedObj.type)
                    val segmentId = extractedObj.source_segment_id
                    val sourceSegment = segments.firstOrNull { it.id == segmentId }
                    val normalizedStatement = extractedObj.statement.lowercase(java.util.Locale.ROOT).trim()

                    val existing = memoryRepository.getBySegmentAndStatement(segmentId, normalizedStatement)
                    if (existing != null) {
                        Log.d(TAG, "Skipping duplicate memory object for segment $segmentId")
                        continue
                    }

                    val memoryObject = MemoryObject(
                        id = "mem_${UUID.randomUUID()}",
                        type = memoryType,
                        statement = extractedObj.statement,
                        normalizedStatement = normalizedStatement,
                        status = MemoryObjectStatus.DETECTED,
                        sourceSegmentId = segmentId,
                        sourceId = sourceSegment?.sourceId ?: segments.first().sourceId,
                        confidence = extractedObj.confidence.coerceIn(0f, 1f),
                        extractionModel = model,
                        dueAt = parseDate(extractedObj.due_at),
                        reviewAt = parseDate(extractedObj.review_at)
                    )

                    // Link owner/project entities when they resolve: this feeds
                    // project-grouped conflict detection, which is otherwise
                    // dead because these columns stay null.
                    val projectEntityId = findProjectEntityId(segmentId)
                    val ownerEntityId = extractedObj.owner
                        ?.lowercase()?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { findOwnerEntityId(it) }

                    when (memoryType) {
                        MemoryType.DECISION -> {
                            val decidedAtMs = parseDate(extractedObj.date)
                                ?: sourceSegment?.createdAt?.takeIf { it > 0 }
                                ?: System.currentTimeMillis()
                            val decision = Decision(
                                id = "dec_${UUID.randomUUID()}",
                                memoryObjectId = memoryObject.id,
                                statement = extractedObj.statement,
                                reason = extractedObj.reason,
                                status = DecisionStatus.DETECTED,
                                sourceSegmentId = segmentId,
                                decidedAt = decidedAtMs,
                                projectEntityId = projectEntityId,
                                confidence = extractedObj.confidence.coerceIn(0f, 1f)
                            )
                            decisionRepository.createDecision(memoryObject, decision)
                            decisionsCreated++

                            // Create review item
                            reviewRepository.insert(MemoryReviewItem(
                                id = "rev_${UUID.randomUUID()}",
                                type = ReviewItemType.DECISION,
                                referencedObjectId = decision.id,
                                reason = "AI detected a possible decision. Please confirm.",
                                status = com.noteflowai.app.data.memory.model.ReviewItemStatus.PENDING
                            ))
                        }
                        MemoryType.COMMITMENT -> {
                            val commitment = Commitment(
                                id = "com_${UUID.randomUUID()}",
                                memoryObjectId = memoryObject.id,
                                action = extractedObj.statement,
                                ownerText = extractedObj.owner,
                                ownerEntityId = ownerEntityId,
                                dueAt = parseDate(extractedObj.due_at),
                                status = CommitmentStatus.DETECTED,
                                sourceSegmentId = segmentId,
                                projectEntityId = projectEntityId,
                                confidence = extractedObj.confidence.coerceIn(0f, 1f)
                            )
                            commitmentRepository.createCommitment(memoryObject, commitment)
                            commitmentsCreated++

                            reviewRepository.insert(MemoryReviewItem(
                                id = "rev_${UUID.randomUUID()}",
                                type = ReviewItemType.COMMITMENT,
                                referencedObjectId = commitment.id,
                                reason = "AI detected a possible commitment. Please confirm.",
                                status = com.noteflowai.app.data.memory.model.ReviewItemStatus.PENDING
                            ))
                        }
                        else -> {
                            memoryRepository.insert(
                                memoryObject.copy(projectEntityId = projectEntityId)
                            )
                            otherMemoryCreated++
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error storing memory object: ${e.message}")
                    storeErrors.add("Memory object '${extractedObj.statement.take(80)}': ${e.message}")
                }
            }

            // Store timeline entries (dedup-safe: an existing row with the same
            // title + segment + start date is skipped, so a re-extract never stacks
            // a duplicate and a confirmed/rejected entry survives the rebuild).
            // Batched: one existing-row scan per source instead of per entry.
            val segmentsBySegmentId = segments.associateBy { it.id }
            val validTimeline = validation.timeline.filter {
                it.source_segment_id != null && it.source_segment_id in validSegmentIds &&
                    segmentsBySegmentId.containsKey(it.source_segment_id)
            }
            try {
                timelineCreated =
                    timelineRepository.insertManyFromExtraction(validTimeline, segmentsBySegmentId).size
            } catch (e: Exception) {
                Log.e(TAG, "Error storing timeline entries: ${e.message}")
                storeErrors.add("Timeline batch: ${e.message}")
            }

            // Store relations — resolve entity names to IDs, skip if either endpoint not found.
            val entityNameToId = mutableMapOf<String, String>()
            for (rel in validation.relations) {
                try {
                    val fromId = entityNameToId.getOrPut(rel.from_entity.lowercase().trim()) {
                        entityRepository.getByNormalizedName(rel.from_entity)?.id ?: ""
                    }
                    val toId = entityNameToId.getOrPut(rel.to_entity.lowercase().trim()) {
                        entityRepository.getByNormalizedName(rel.to_entity)?.id ?: ""
                    }
                    if (fromId.isBlank() || toId.isBlank()) continue
                    val relationType = runCatching { RelationType.valueOf(rel.relation) }.getOrNull() ?: continue
                    relationDao.insert(
                        MemoryRelation(
                            id = "rel_${UUID.randomUUID()}",
                            fromType = "ENTITY",
                            fromId = fromId,
                            toType = "ENTITY",
                            toId = toId,
                            relationType = relationType,
                            sourceSegmentId = rel.source_segment_id?.takeIf { it in validSegmentIds },
                            confidence = rel.confidence.coerceIn(0f, 1f)
                        )
                    )
                    relationsCreated++
                } catch (e: Exception) {
                    Log.e(TAG, "Error storing relation: ${e.message}")
                    storeErrors.add("Relation ${rel.from_entity}->${rel.to_entity}: ${e.message}")
                }
            }

            Log.i(TAG, "Extraction complete: $entitiesCreated entities, $decisionsCreated decisions, $commitmentsCreated commitments, $otherMemoryCreated other, $timelineCreated timeline, $relationsCreated relations")
            ExtractionResult(
                entitiesCreated = entitiesCreated,
                decisionsCreated = decisionsCreated,
                commitmentsCreated = commitmentsCreated,
                otherMemoryCreated = otherMemoryCreated,
                timelineCreated = timelineCreated,
                errors = validation.errors + storeErrors,
                relationsCreated = relationsCreated
            )
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed: ${e.message}", e)
            ExtractionResult(
                entitiesCreated = 0,
                decisionsCreated = 0,
                commitmentsCreated = 0,
                otherMemoryCreated = 0,
                timelineCreated = 0,
                errors = listOf("Extraction failed: ${e.message}")
            )
        }
    }

    private fun parseResponse(responseText: String): ExtractionResponse? {
        return try {
            val json = JsonExtraction.extractJsonObject(responseText) ?: return null
            gson.fromJson(json, ExtractionResponse::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse extraction response: ${e.message}")
            null
        }
    }

    private fun parseDate(dateStr: String?): Long? = LenientDates.parseToMillis(dateStr)

    /**
     * Resolve the PROJECT entity mentioned in [segmentId], if any. Mentions
     * were just stored above, so same-extract entities resolve too.
     */
    private suspend fun findProjectEntityId(segmentId: String): String? {
        return try {
            val mentions = entityRepository.getMentionsBySegmentIds(listOf(segmentId))
            for (mention in mentions) {
                val entity = entityRepository.getById(mention.entityId)
                if (entity?.type == EntityType.PROJECT) return entity.id
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Project link lookup failed: ${e.message}")
            null
        }
    }

    /**
     * Resolve an owner name ("alice") to a person/org entity id, if one with
     * that normalized name exists.
     */
    private suspend fun findOwnerEntityId(normalizedOwner: String): String? {
        return try {
            val entity = entityRepository.getByNormalizedName(normalizedOwner)
            entity?.takeIf {
                it.type == EntityType.PERSON ||
                    it.type == EntityType.ORGANIZATION ||
                    it.type == EntityType.COMPANY
            }?.id
        } catch (e: Exception) {
            Log.w(TAG, "Owner link lookup failed: ${e.message}")
            null
        }
    }
}

data class ExtractionResult(
    val entitiesCreated: Int,
    val decisionsCreated: Int,
    val commitmentsCreated: Int,
    val otherMemoryCreated: Int,
    val timelineCreated: Int = 0,
    val errors: List<String>,
    val relationsCreated: Int = 0
)
