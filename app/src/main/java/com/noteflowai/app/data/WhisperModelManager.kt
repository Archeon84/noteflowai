package com.noteflowai.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class WhisperModelManager(private val context: Context) {

    private val modelsDir = File(context.filesDir, "models").also { it.mkdirs() }
    private val qnnModelsDir = File(context.filesDir, "qnn_models").also { it.mkdirs() }
    private val onnxModelsDir = File(context.filesDir, "onnx_models").also { it.mkdirs() }

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _currentDownloadingModel = MutableStateFlow("")
    val currentDownloadingModel: StateFlow<String> = _currentDownloadingModel.asStateFlow()

    companion object {
        private const val TAG = "WhisperModelManager"
        private const val GGML_BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"
        private const val ONNX_BASE_URL = "https://huggingface.co/onnx-community/whisper"
        private const val QNN_BASE_URL = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models"

        val MODELS = listOf("tiny", "base", "small", "large-v3-turbo")
        val GGML_QUANT_VARIANTS = listOf("q5_1", "q8_0")
        val MODEL_SIZES = mapOf(
            "tiny" to "~75 MB (GGML) / ~33 MB (ONNX)",
            "tiny-q5_1" to "~45 MB (GGML q5_1)",
            "tiny-q8_0" to "~75 MB (GGML q8_0)",
            "base" to "~142 MB (GGML) / ~74 MB (ONNX)",
            "base-q5_1" to "~85 MB (GGML q5_1)",
            "base-q8_0" to "~142 MB (GGML q8_0)",
            "small" to "~466 MB (GGML) / ~244 MB (ONNX)",
            "small-q5_1" to "~180 MB (GGML q5_1)",
            "small-q8_0" to "~466 MB (GGML q8_0)",
            "large-v3-turbo" to "~809 MB (GGML) / ~782 MB (ONNX)",
            "large-v3-turbo-q5_0" to "~440 MB (GGML q5_0)",
            "large-v3-turbo-q8_0" to "~809 MB (GGML q8_0)"
        )
        val MODEL_DESCRIPTIONS = mapOf(
            "tiny" to "Fast, lower accuracy",
            "tiny-q5_1" to "Fast, smaller, lower accuracy",
            "tiny-q8_0" to "Fast, full quality tiny",
            "base" to "Good balance",
            "base-q5_1" to "Balanced, smaller download",
            "base-q8_0" to "Balanced, full quality",
            "small" to "Best accuracy on-device",
            "small-q5_1" to "Best accuracy, smaller download",
            "small-q8_0" to "Best accuracy, full quality",
            "large-v3-turbo" to "Highest accuracy",
            "large-v3-turbo-q5_0" to "Highest accuracy, smaller",
            "large-v3-turbo-q8_0" to "Highest accuracy, full quality"
        )

        private val ONNX_MODEL_URLS = mapOf(
            "tiny" to "tiny",
            "base" to "base",
            "small" to "small",
            "large-v3-turbo" to "large-v3-turbo"
        )

        private val QNN_MODEL_URLS = mapOf(
            "tiny" to "$QNN_BASE_URL/whisper_tiny/releases/v0.57.1/whisper_tiny-precompiled_qnn_onnx-float-qualcomm_snapdragon_8gen3.zip",
            "base" to "$QNN_BASE_URL/whisper_base/releases/v0.57.1/whisper_base-precompiled_qnn_onnx-float-qualcomm_snapdragon_8gen3.zip",
            "small" to "$QNN_BASE_URL/whisper_small/releases/v0.57.1/whisper_small-precompiled_qnn_onnx-float-qualcomm_snapdragon_8gen3.zip",
            "large-v3-turbo" to "$QNN_BASE_URL/whisper_large_v3_turbo/releases/v0.57.1/whisper_large_v3_turbo-precompiled_qnn_onnx-float-qualcomm_snapdragon_8gen3.zip"
        )
    }

    // --- GGML Model Management (whisper.cpp CPU) ---

    fun isGgmlModelDownloaded(model: String): Boolean {
        // Check for quantized variant first, then standard
        val quantFile = File(modelsDir, "ggml-$model.bin")
        if (quantFile.exists() && quantFile.length() > 100_000) return true
        // Check base model without quant suffix
        val baseModel = model.replace(Regex("-q[58]_[01]$"), "")
        val baseFile = File(modelsDir, "ggml-$baseModel.bin")
        return baseFile.exists() && baseFile.length() > 100_000
    }

    fun getGgmlModelPath(model: String): String? {
        // Check for exact match first (quantized)
        val quantFile = File(modelsDir, "ggml-$model.bin")
        if (quantFile.exists() && quantFile.length() > 100_000) return quantFile.absolutePath
        // Check base model without quant suffix
        val baseModel = model.replace(Regex("-q[58]_[01]$"), "")
        val baseFile = File(modelsDir, "ggml-$baseModel.bin")
        return if (baseFile.exists() && baseFile.length() > 100_000) baseFile.absolutePath else null
    }

    fun isDraftModelAvailable(mainModel: String): Boolean {
        val draftModel = when (mainModel) {
            "large-v3-turbo" -> "small"
            "small" -> "base"
            "base" -> "tiny"
            else -> return false
        }
        return isGgmlModelDownloaded(draftModel) || isOnnxModelDownloaded(draftModel)
    }

    suspend fun downloadGgmlModel(modelName: String) {
        if (_isDownloading.value) return
        _isDownloading.value = true
        _currentDownloadingModel.value = "ggml-$modelName"
        _downloadProgress.value = 0f

        withContext(Dispatchers.IO) {
            try {
                val destFile = File(modelsDir, "ggml-$modelName.bin")
                val url = URL("$GGML_BASE_URL/ggml-$modelName.bin")
                downloadFile(url, destFile)
                Log.i(TAG, "GGML model downloaded: ${destFile.absolutePath} (${destFile.length()} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "GGML model download failed: $modelName", e)
                File(modelsDir, "ggml-$modelName.bin").delete()
            } finally {
                _isDownloading.value = false
                _currentDownloadingModel.value = ""
            }
        }
    }

    fun deleteGgmlModel(modelName: String) {
        val file = File(modelsDir, "ggml-$modelName.bin")
        if (file.exists()) {
            file.delete()
            Log.i(TAG, "GGML model deleted: $modelName")
        }
    }

    // --- QNN ONNX Model Management (ORT NPU) ---

    fun isQnnModelDownloaded(model: String): Boolean {
        val modelDir = File(qnnModelsDir, model)
        val encoderOnnx = File(modelDir, "encoder.onnx")
        val encoderCtx = File(modelDir, "encoder_qairt_context.bin")
        val decoderOnnx = File(modelDir, "decoder.onnx")
        val decoderCtx = File(modelDir, "decoder_qairt_context.bin")
        return encoderOnnx.exists() && encoderCtx.exists() &&
                decoderOnnx.exists() && decoderCtx.exists() &&
                encoderOnnx.length() > 100 && encoderCtx.length() > 100_000
    }

    fun getQnnEncoderPath(model: String): String? {
        val file = File(qnnModelsDir, "$model/encoder.onnx")
        return if (file.exists() && file.length() > 100) file.absolutePath else null
    }

    fun getQnnDecoderPath(model: String): String? {
        val file = File(qnnModelsDir, "$model/decoder.onnx")
        return if (file.exists() && file.length() > 100) file.absolutePath else null
    }

    fun getQnnModelDir(model: String): String? {
        val dir = File(qnnModelsDir, model)
        return if (isQnnModelDownloaded(model)) dir.absolutePath else null
    }

    suspend fun downloadQnnModel(modelName: String) {
        if (_isDownloading.value) return
        val zipUrl = QNN_MODEL_URLS[modelName] ?: return

        _isDownloading.value = true
        _currentDownloadingModel.value = "qnn-$modelName"
        _downloadProgress.value = 0f

        withContext(Dispatchers.IO) {
            try {
                val tempFile = File(qnnModelsDir, "$modelName-qnn.zip")
                val url = URL(zipUrl)
                downloadFile(url, tempFile)

                Log.i(TAG, "QNN ZIP downloaded: ${tempFile.length()} bytes, extracting...")

                val modelDir = File(qnnModelsDir, modelName)
                modelDir.deleteRecursively()
                modelDir.mkdirs()

                val zipFile = java.util.zip.ZipFile(tempFile)
                val entries = zipFile.entries().toList()
                val totalEntries = entries.size
                var extractedCount = 0

                for (entry in entries) {
                    if (!entry.isDirectory) {
                        val entryName = entry.name
                        val flatName = if (entryName.contains("/")) {
                            entryName.substringAfterLast("/")
                        } else {
                            entryName
                        }
                        val outFile = File(modelDir, flatName)
                        zipFile.getInputStream(entry).use { input ->
                            FileOutputStream(outFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        extractedCount++
                        _downloadProgress.value = 0.9f + (0.1f * extractedCount / totalEntries)
                        Log.i(TAG, "  Extracted: $flatName (${outFile.length()} bytes)")
                    }
                }
                zipFile.close()
                tempFile.delete()

                _downloadProgress.value = 1f
                Log.i(TAG, "QNN model ready: ${modelDir.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "QNN model download failed: $modelName", e)
                Log.e(TAG, "QNN model download failed details: ${e.message}")
                File(qnnModelsDir, modelName).deleteRecursively()
                File(qnnModelsDir, "$modelName-qnn.zip").delete()
            } finally {
                _isDownloading.value = false
                _currentDownloadingModel.value = ""
            }
        }
    }

    fun deleteQnnModel(modelName: String) {
        val dir = File(qnnModelsDir, modelName)
        if (dir.exists()) {
            dir.deleteRecursively()
            Log.i(TAG, "QNN model deleted: $modelName")
        }
    }

    // --- ONNX NNAPI Model Management ---

    fun isOnnxModelDownloaded(model: String): Boolean {
        val modelDir = File(onnxModelsDir, model)
        val encoder = File(modelDir, "encoder_model.onnx")
        val decoder = File(modelDir, "decoder_model_merged.onnx")
        return encoder.exists() && decoder.exists() &&
                encoder.length() > 100_000 && decoder.length() > 100_000
    }

    fun getOnnxEncoderPath(model: String): String? {
        val file = File(onnxModelsDir, "$model/encoder_model.onnx")
        return if (file.exists() && file.length() > 100_000) file.absolutePath else null
    }

    fun getOnnxDecoderPath(model: String): String? {
        val file = File(onnxModelsDir, "$model/decoder_model_merged.onnx")
        return if (file.exists() && file.length() > 100_000) file.absolutePath else null
    }

    suspend fun downloadOnnxModel(modelName: String) {
        if (_isDownloading.value) return
        val modelRepo = ONNX_MODEL_URLS[modelName] ?: return

        _isDownloading.value = true
        _currentDownloadingModel.value = "onnx-$modelName"
        _downloadProgress.value = 0f

        withContext(Dispatchers.IO) {
            try {
                val modelDir = File(onnxModelsDir, modelName)
                modelDir.deleteRecursively()
                modelDir.mkdirs()

                val encoderUrl = URL("https://huggingface.co/onnx-community/whisper-$modelRepo/resolve/main/onnx/encoder_model.onnx")
                val decoderUrl = URL("https://huggingface.co/onnx-community/whisper-$modelRepo/resolve/main/onnx/decoder_model_merged.onnx")

                Log.i(TAG, "Downloading ONNX encoder from $encoderUrl")
                _downloadProgress.value = 0.05f
                downloadFile(encoderUrl, File(modelDir, "encoder_model.onnx"))

                _downloadProgress.value = 0.5f
                Log.i(TAG, "Downloading ONNX merged decoder from $decoderUrl")
                downloadFile(decoderUrl, File(modelDir, "decoder_model_merged.onnx"))

                _downloadProgress.value = 1f
                Log.i(TAG, "ONNX model ready: ${modelDir.absolutePath}")
                Log.i(TAG, "  encoder: ${File(modelDir, "encoder_model.onnx").length()} bytes")
                Log.i(TAG, "  decoder: ${File(modelDir, "decoder_model_merged.onnx").length()} bytes")
            } catch (e: Exception) {
                Log.e(TAG, "ONNX model download failed: $modelName", e)
                File(onnxModelsDir, modelName).deleteRecursively()
            } finally {
                _isDownloading.value = false
                _currentDownloadingModel.value = ""
            }
        }
    }

    fun deleteOnnxModel(modelName: String) {
        val dir = File(onnxModelsDir, modelName)
        if (dir.exists()) {
            dir.deleteRecursively()
            Log.i(TAG, "ONNX model deleted: $modelName")
        }
    }

    // --- Generic Utilities ---

    private fun downloadFile(url: URL, destFile: File) {
        val urlStr = url.toString()
        val expected = ModelDownloader.getContentLength(urlStr)
        Log.i(TAG, "Downloading ${url.file} -> ${destFile.name} (expected: $expected bytes)")
        ModelDownloader.download(urlStr, destFile) { bytes ->
            if (expected > 0) {
                _downloadProgress.value = (bytes.toFloat() / expected).coerceIn(0f, 0.85f)
            }
        }
        Log.i(TAG, "Download complete: ${destFile.length()} bytes")
    }
}
