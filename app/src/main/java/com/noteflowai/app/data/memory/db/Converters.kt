package com.noteflowai.app.data.memory.db

import android.util.Log
import androidx.room.TypeConverter
import com.noteflowai.app.data.capture.CaptureStatus
import com.noteflowai.app.data.memory.model.CommitmentStatus
import com.noteflowai.app.data.memory.model.ConfirmationState
import com.noteflowai.app.data.memory.model.ConflictStatus
import com.noteflowai.app.data.memory.model.DecisionStatus
import com.noteflowai.app.data.memory.model.EntityType
import com.noteflowai.app.data.memory.model.MemoryObjectStatus
import com.noteflowai.app.data.memory.model.MemoryType
import com.noteflowai.app.data.memory.model.ProcessingStage
import com.noteflowai.app.data.memory.model.ProcessingState
import com.noteflowai.app.data.memory.model.RelationType
import com.noteflowai.app.data.memory.model.ReviewItemStatus
import com.noteflowai.app.data.memory.model.ReviewItemType
import com.noteflowai.app.data.memory.model.SourceType
import com.noteflowai.app.data.memory.model.TemporalPrecision

/**
 * Room type converters for the Personal Memory Layer database.
 * All enums are stored as their name strings.
 */
class Converters {

    private inline fun <reified T : Enum<T>> safeValueOf(value: String, fallback: T): T {
        return try {
            enumValueOf<T>(value)
        } catch (e: IllegalArgumentException) {
            // Loud by design: a corrupt/renamed stored value must never pass
            // silently. The key enums fall back to UNKNOWN (never a real type).
            Log.e("Converters", "Unknown enum value '$value' for ${T::class.simpleName}, using $fallback")
            fallback
        }
    }

    @TypeConverter
    fun fromSourceType(value: SourceType): String = value.name
    @TypeConverter
    fun toSourceType(value: String): SourceType = safeValueOf(value, SourceType.UNKNOWN)

    @TypeConverter
    fun fromEntityType(value: EntityType): String = value.name
    @TypeConverter
    fun toEntityType(value: String): EntityType = safeValueOf(value, EntityType.UNKNOWN)

    @TypeConverter
    fun fromMemoryType(value: MemoryType): String = value.name
    @TypeConverter
    fun toMemoryType(value: String): MemoryType = safeValueOf(value, MemoryType.UNKNOWN)

    @TypeConverter
    fun fromMemoryObjectStatus(value: MemoryObjectStatus): String = value.name
    @TypeConverter
    fun toMemoryObjectStatus(value: String): MemoryObjectStatus = safeValueOf(value, MemoryObjectStatus.DETECTED)

    @TypeConverter
    fun fromDecisionStatus(value: DecisionStatus): String = value.name
    @TypeConverter
    fun toDecisionStatus(value: String): DecisionStatus = safeValueOf(value, DecisionStatus.DETECTED)

    @TypeConverter
    fun fromCommitmentStatus(value: CommitmentStatus): String = value.name
    @TypeConverter
    fun toCommitmentStatus(value: String): CommitmentStatus = safeValueOf(value, CommitmentStatus.DETECTED)

    @TypeConverter
    fun fromRelationType(value: RelationType): String = value.name
    @TypeConverter
    fun toRelationType(value: String): RelationType = safeValueOf(value, RelationType.RELATED_TO)

    @TypeConverter
    fun fromReviewItemStatus(value: ReviewItemStatus): String = value.name
    @TypeConverter
    fun toReviewItemStatus(value: String): ReviewItemStatus = safeValueOf(value, ReviewItemStatus.PENDING)

    @TypeConverter
    fun fromReviewItemType(value: ReviewItemType): String = value.name
    @TypeConverter
    fun toReviewItemType(value: String): ReviewItemType = safeValueOf(value, ReviewItemType.ENTITY)

    @TypeConverter
    fun fromConflictStatus(value: ConflictStatus): String = value.name
    @TypeConverter
    fun toConflictStatus(value: String): ConflictStatus = safeValueOf(value, ConflictStatus.PENDING)

    @TypeConverter
    fun fromProcessingStage(value: ProcessingStage): String = value.name
    @TypeConverter
    fun toProcessingStage(value: String): ProcessingStage {
        // Phase 3 renamed the coarse stages to granular, user-facing ones. Rows persisted
        // before this change still hold the legacy names; map them forward so an in-flight
        // source resumes at the equivalent granular stage instead of restarting from scratch.
        val legacy = when (value) {
            "EXTRACTION" -> ProcessingStage.EXTRACTING_ENTITIES
            "RELATION_DETECTION" -> ProcessingStage.EXTRACTING_TIMELINE
            "EMBEDDING" -> ProcessingStage.BUILDING_SEMANTIC_INDEX
            "INDEXING" -> ProcessingStage.PREPARING_SEARCH
            else -> null
        }
        return legacy ?: safeValueOf(value, ProcessingStage.SEGMENTS_CREATED)
    }

    @TypeConverter
    fun fromProcessingState(value: ProcessingState): String = value.name
    @TypeConverter
    fun toProcessingState(value: String): ProcessingState = safeValueOf(value, ProcessingState.PENDING)

    @TypeConverter
    fun fromCaptureStatus(value: CaptureStatus): String = value.name
    @TypeConverter
    fun toCaptureStatus(value: String): CaptureStatus = safeValueOf(value, CaptureStatus.CAPTURED)

    @TypeConverter
    fun fromConfirmationState(value: ConfirmationState): String = value.name
    @TypeConverter
    fun toConfirmationState(value: String): ConfirmationState = safeValueOf(value, ConfirmationState.SUGGESTED)

    @TypeConverter
    fun fromTemporalPrecision(value: TemporalPrecision): String = value.name
    @TypeConverter
    fun toTemporalPrecision(value: String): TemporalPrecision = safeValueOf(value, TemporalPrecision.UNKNOWN)
}
