package com.noteflowai.app.data.memory.model

/**
 * Types of content sources that can produce SourceSegments.
 * Used as a Room type converter enum.
 */
enum class SourceType {
    NOTE,
    AUDIO,
    PDF,
    DOCUMENT,
    OCR,
    YOUTUBE,
    GENERATED_NOTE,
    /**
     * Corrupt or future-unknown value read from disk. Routes to a safe
     * fallback in navigation/adapters — never silently treated as NOTE.
     */
    UNKNOWN
}
