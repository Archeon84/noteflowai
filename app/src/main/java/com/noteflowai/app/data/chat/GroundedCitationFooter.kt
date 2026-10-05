package com.noteflowai.app.data.chat

data class GroundedCitationFooter(
    val chunkId: String,
    val sourceId: String,
    val quoteText: String? = null,
    val citationIndex: Int = 1
)
