package com.noteflowai.app.data.importer

import android.content.Context
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class ImageOcrImporter(private val context: Context) : ContentImporter {

    override suspend fun inspect(input: ImportInput): ImportInspection = withContext(Dispatchers.IO) {
        ImportInspection(
            isSupported = true,
            formatLabel = "OCR",
            estimatedBytes = input.file?.length() ?: 0L
        )
    }

    override suspend fun import(input: ImportInput): ImportResult = withContext(Dispatchers.IO) {
        try {
            val bitmap = when {
                input.file != null -> BitmapFactory.decodeFile(input.file.absolutePath)
                input.uri != null -> context.contentResolver.openInputStream(input.uri)?.use {
                    BitmapFactory.decodeStream(it)
                }
                else -> null
            } ?: return@withContext ImportResult.Failure(ImportError.MalformedContent, "Cannot decode image file")

            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val image = InputImage.fromBitmap(bitmap, 0)
            val visionText = recognizer.process(image).await()
            val text = visionText.text.trim()

            if (text.isBlank()) {
                return@withContext ImportResult.Failure(
                    error = ImportError.EmptyContent,
                    rawMessage = "No text detected in image",
                    fallbackAction = FallbackAction.CHOOSE_ANOTHER_FILE
                )
            }

            val title = input.title ?: input.file?.name ?: "Scanned Image Note"
            val wordCount = text.split(Regex("\\s+")).filter { it.isNotBlank() }.size

            ImportResult.Success(
                text = text,
                title = title,
                format = "OCR",
                wordCount = wordCount,
                charCount = text.length
            )
        } catch (e: Exception) {
            ImportResult.Failure(
                error = ImportError.MalformedContent,
                rawMessage = e.message ?: "OCR recognition failed",
                fallbackAction = FallbackAction.CHOOSE_ANOTHER_FILE
            )
        }
    }
}
