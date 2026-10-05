package com.noteflowai.app.data.chat

object CitationMarkupParser {
    data class Marker(val markerIndex: Int, val start: Int, val end: Int)
    private val PATTERN = Regex("\\[(\\d+)\\]")

    fun scan(text: String): List<Marker> =
        PATTERN.findAll(text).map {
            Marker(markerIndex = it.groupValues[1].toInt(), start = it.range.first, end = it.range.last + 1)
        }.toList()
}
