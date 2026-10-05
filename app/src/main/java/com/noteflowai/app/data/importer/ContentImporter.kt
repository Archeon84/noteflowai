package com.noteflowai.app.data.importer

/**
 * Common abstraction for all content ingestion strategies (PDF, DOCX, TXT, HTML, OCR, YouTube, Audio).
 */
interface ContentImporter {
    /**
     * Inspects input metadata, support status, and encryption/password requirements
     * without performing full ingestion.
     */
    suspend fun inspect(input: ImportInput): ImportInspection

    /**
     * Ingests the content and extracts structured text and metadata.
     */
    suspend fun import(input: ImportInput): ImportResult
}
