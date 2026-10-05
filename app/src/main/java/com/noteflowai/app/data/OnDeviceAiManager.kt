package com.noteflowai.app.data

import android.content.Context
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.proofreading.Proofreading
import com.google.mlkit.genai.proofreading.ProofreaderOptions
import com.google.mlkit.genai.rewriting.RewriterOptions
import com.google.mlkit.genai.rewriting.Rewriting
import com.google.mlkit.genai.summarization.Summarization
import com.google.mlkit.genai.summarization.SummarizerOptions
import kotlinx.coroutines.guava.await

class OnDeviceAiManager(private val context: Context) {

    sealed class AiResult {
        data class Success(val text: String) : AiResult()
        data class Error(val message: String) : AiResult()
        object Unavailable : AiResult()
    }

    private fun detectLanguage(text: String): String {
        val sample = text.take(500)
        val cjkCount = sample.count { it in '\u4E00'..'\u9FFF' || it in '\u3400'..'\u4DBF' }
        val jpCount = sample.count { it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' }
        val koCount = sample.count { it in '\uAC00'..'\uD7AF' || it in '\u1100'..'\u11FF' }
        val total = sample.length.coerceAtLeast(1)
        return when {
            jpCount > total * 0.1 -> "Japanese"
            koCount > total * 0.1 -> "Korean"
            cjkCount > total * 0.1 -> "Mandarin"
            else -> "English"
        }
    }

    private fun getMlKitLanguage(lang: String): Int = when (lang) {
        "Japanese" -> SummarizerOptions.Language.JAPANESE
        "Korean" -> SummarizerOptions.Language.KOREAN
        else -> SummarizerOptions.Language.ENGLISH
    }

    private fun getProofreadLanguage(lang: String): Int = when (lang) {
        "Japanese" -> ProofreaderOptions.Language.JAPANESE
        "Korean" -> ProofreaderOptions.Language.KOREAN
        else -> ProofreaderOptions.Language.ENGLISH
    }

    private fun getRewriteLanguage(lang: String): Int = when (lang) {
        "Japanese" -> RewriterOptions.Language.JAPANESE
        "Korean" -> RewriterOptions.Language.KOREAN
        else -> RewriterOptions.Language.ENGLISH
    }

    suspend fun checkAvailability(): Boolean {
        return try {
            val summarizer = Summarization.getClient(
                SummarizerOptions.builder(context)
                    .setInputType(SummarizerOptions.InputType.ARTICLE)
                    .setOutputType(SummarizerOptions.OutputType.THREE_BULLETS)
                    .setLanguage(SummarizerOptions.Language.ENGLISH)
                    .build()
            )
            val status = summarizer.checkFeatureStatus().await()
            summarizer.close()
            status == FeatureStatus.AVAILABLE || status == FeatureStatus.DOWNLOADABLE
        } catch (e: Exception) {
            false
        }
    }

    suspend fun summarize(text: String): AiResult {
        return try {
            val lang = detectLanguage(text)
            val mlLang = getMlKitLanguage(lang)
            val summarizer = Summarization.getClient(
                SummarizerOptions.builder(context)
                    .setInputType(SummarizerOptions.InputType.ARTICLE)
                    .setOutputType(SummarizerOptions.OutputType.THREE_BULLETS)
                    .setLanguage(mlLang)
                    .setLongInputAutoTruncationEnabled(true)
                    .build()
            )
            val status = summarizer.checkFeatureStatus().await()
            if (status != FeatureStatus.AVAILABLE) {
                summarizer.close()
                return AiResult.Unavailable
            }
            val request = com.google.mlkit.genai.summarization.SummarizationRequest.builder(text).build()
            val result = summarizer.runInference(request).await()
            val summary = result.summary
            summarizer.close()
            AiResult.Success(summary)
        } catch (e: Exception) {
            AiResult.Error("Summarization failed: ${e.message}")
        }
    }

    suspend fun proofread(text: String): AiResult {
        return try {
            val lang = detectLanguage(text)
            val mlLang = getProofreadLanguage(lang)
            val proofreader = Proofreading.getClient(
                ProofreaderOptions.builder(context)
                    .setInputType(ProofreaderOptions.InputType.KEYBOARD)
                    .setLanguage(mlLang)
                    .build()
            )
            val status = proofreader.checkFeatureStatus().await()
            if (status != FeatureStatus.AVAILABLE) {
                proofreader.close()
                return AiResult.Unavailable
            }
            val request = com.google.mlkit.genai.proofreading.ProofreadingRequest.builder(text).build()
            val results = proofreader.runInference(request).await().results
            val corrected = results?.firstOrNull()?.text ?: text
            proofreader.close()
            AiResult.Success(corrected)
        } catch (e: Exception) {
            AiResult.Error("Proofreading failed: ${e.message}")
        }
    }

    suspend fun rewrite(text: String, style: RewriteStyle = RewriteStyle.PROFESSIONAL): AiResult {
        return try {
            val lang = detectLanguage(text)
            val mlLang = getRewriteLanguage(lang)
            val outputType = when (style) {
                RewriteStyle.PROFESSIONAL -> RewriterOptions.OutputType.PROFESSIONAL
                RewriteStyle.FRIENDLY -> RewriterOptions.OutputType.FRIENDLY
                RewriteStyle.SHORTEN -> RewriterOptions.OutputType.SHORTEN
                RewriteStyle.ELABORATE -> RewriterOptions.OutputType.ELABORATE
                RewriteStyle.REPHRASE -> RewriterOptions.OutputType.REPHRASE
            }
            val rewriter = Rewriting.getClient(
                RewriterOptions.builder(context)
                    .setOutputType(outputType)
                    .setLanguage(mlLang)
                    .build()
            )
            val status = rewriter.checkFeatureStatus().await()
            if (status != FeatureStatus.AVAILABLE) {
                rewriter.close()
                return AiResult.Unavailable
            }
            val request = com.google.mlkit.genai.rewriting.RewritingRequest.builder(text).build()
            val results = rewriter.runInference(request).await().results
            val rewritten = results?.firstOrNull()?.text ?: text
            rewriter.close()
            AiResult.Success(rewritten)
        } catch (e: Exception) {
            AiResult.Error("Rewriting failed: ${e.message}")
        }
    }

    enum class RewriteStyle {
        PROFESSIONAL, FRIENDLY, SHORTEN, ELABORATE, REPHRASE
    }
}
