package com.noteflowai.app.data.memory.model

/**
 * How precise a timeline entry's date/range is (guide §Phase 4).
 *
 * Stored as `.name` strings via [com.noteflowai.app.data.memory.db.Converters].
 * Drives how retrieval treats the entry's [TimelineEntry.startMs]/[TimelineEntry.endMs]:
 * EXACT and DAY_RANGE entries participate in precise temporal matching; YEAR/MONTH only
 * bucket; RELATIVE / UNKNOWN carry the original extraction but no absolute bounds.
 */
enum class TemporalPrecision {
    /** A single exact point in time (startMs == endMs == that instant). */
    EXACT,

    /** A range spanning at least one full day (startMs < endMs). */
    DAY_RANGE,

    /** A calendar month (bounds are the month's first/last day). */
    MONTH,

    /** A calendar year (bounds are Jan 1 / Dec 31). */
    YEAR,

    /** A relative expression ("last week", "next quarter", "soon"). */
    RELATIVE,

    /** No usable date material was found. */
    UNKNOWN
}