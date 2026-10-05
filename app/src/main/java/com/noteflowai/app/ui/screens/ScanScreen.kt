package com.noteflowai.app.ui.screens

import android.Manifest
import android.content.Context
import android.util.Log
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.noteflowai.app.R
import com.noteflowai.app.ui.components.EmptyState
import com.noteflowai.app.ui.components.ScanIllustration
import com.noteflowai.app.ui.components.ErrorRetryCard
import com.noteflowai.app.ui.components.LanguagePickerDialog
import com.noteflowai.app.ui.theme.AppRadius
import com.noteflowai.app.ui.theme.AppSpacing
import com.noteflowai.app.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "ScanScreen"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScanScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var extractedText by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var processingStatus by remember { mutableStateOf("") }
    var extractionSuccess by remember { mutableStateOf(false) }
    var selectedLanguage by remember { mutableStateOf("english") }
    var showLangPicker by remember { mutableStateOf(false) }
    var showFullText by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    fun isExtractedTextValid(text: String): Boolean {
        return text.isNotBlank() &&
            !text.startsWith("[") &&
            !text.startsWith("No text found") &&
            !text.startsWith("OCR failed") &&
            !text.startsWith("Error:")
    }

    var lastScanRedo by remember { mutableStateOf<(() -> Unit)?>(null) }
    val onScanProgress: (String) -> Unit = { processingStatus = it }
    val onScanResult: (String) -> Unit = { text ->
        extractedText = text
        extractionSuccess = isExtractedTextValid(text)
        isProcessing = false
        processingStatus = ""
        showFullText = false
        // Phase 1 extension: durable-capture a successful OCR extraction (non-blocking).
        if (extractionSuccess) {
            viewModel.captureOcr(text, selectedLanguage)
        }
    }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            isProcessing = true
            processingStatus = context.getString(R.string.scan_extracting_text)
            extractAndTranslate(context, it, selectedLanguage, viewModel,
                onProgress = onScanProgress,
                onResult = onScanResult
            )
            lastScanRedo = {
                extractAndTranslate(context, it, selectedLanguage, viewModel,
                    onProgress = onScanProgress,
                    onResult = onScanResult)
            }
        }
    }

    var cameraGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }

    val scanSnackbarHost = remember { SnackbarHostState() }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraGranted = granted
        if (!granted) {
            scope.launch { scanSnackbarHost.showSnackbar(context.getString(R.string.scan_camera_denied)) }
        }
    }

    LaunchedEffect(Unit) {
        if (!cameraGranted) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(cameraError) {
        cameraError?.let {
            scanSnackbarHost.showSnackbar(it)
            cameraError = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scan_title), style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        snackbarHost = { SnackbarHost(scanSnackbarHost) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AppSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Language selector
            Text(
                stringResource(R.string.scan_language_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(AppSpacing.xs))
            OutlinedButton(onClick = { showLangPicker = true }) {
                Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(AppSpacing.sm))
                Text(getLanguageName(context, selectedLanguage))
            }
            if (showLangPicker) {
                LanguagePickerDialog(
                    selectedCode = selectedLanguage,
                    onSelect = { selectedLanguage = it },
                    onDismiss = { showLangPicker = false }
                )
            }

            Spacer(modifier = Modifier.height(AppSpacing.sm))

            if (cameraGranted) {
                // Camera preview - shows real capture area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f)
                        .clip(RoundedCornerShape(AppRadius.large))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    val cameraDesc = stringResource(R.string.camera_viewfinder)
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).also { previewView ->
                                previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
                                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                                cameraProviderFuture.addListener({
                                    val cameraProvider = cameraProviderFuture.get()
                                    val preview = Preview.Builder()
                                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                                        .build().also {
                                        it.setSurfaceProvider(previewView.surfaceProvider)
                                    }
                                    try {
                                        cameraProvider.unbindAll()
                                        cameraProvider.bindToLifecycle(
                                            lifecycleOwner,
                                            CameraSelector.DEFAULT_BACK_CAMERA,
                                            preview,
                                            imageCapture
                                        )
                                    } catch (e: Exception) {
                                        cameraError = context.getString(R.string.scan_camera_error)
                                    }
                                }, ContextCompat.getMainExecutor(ctx))
                            }
                        },
                        modifier = Modifier.fillMaxSize().semantics { contentDescription = cameraDesc }
                    )
                    Canvas(Modifier.matchParentSize()) {
                        val len = 28.dp.toPx()
                        val stroke = 4.dp.toPx()
                        val pad = 14.dp.toPx()
                        val x0 = pad; val y0 = pad
                        val x1 = size.width - pad; val y1 = size.height - pad
                        val white = Color.White
                        drawLine(white, Offset(x0, y0), Offset(x0 + len, y0), strokeWidth = stroke)
                        drawLine(white, Offset(x0, y0), Offset(x0, y0 + len), strokeWidth = stroke)
                        drawLine(white, Offset(x1, y0), Offset(x1 - len, y0), strokeWidth = stroke)
                        drawLine(white, Offset(x1, y0), Offset(x1, y0 + len), strokeWidth = stroke)
                        drawLine(white, Offset(x0, y1), Offset(x0 + len, y1), strokeWidth = stroke)
                        drawLine(white, Offset(x0, y1), Offset(x0, y1 - len), strokeWidth = stroke)
                        drawLine(white, Offset(x1, y1), Offset(x1 - len, y1), strokeWidth = stroke)
                        drawLine(white, Offset(x1, y1), Offset(x1, y1 - len), strokeWidth = stroke)
                    }
                    if (extractedText.isEmpty() && !isProcessing) {
                        Box(
                            modifier = Modifier.align(Alignment.Center).padding(AppSpacing.lg)
                                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(AppRadius.medium))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                stringResource(R.string.scan_align_hint),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White
                            )
                        }
                    }
                }
            } else {
                com.noteflowai.app.ui.components.EmptyState(
                    icon = Icons.Default.CameraAlt,
                    title = stringResource(R.string.scan_camera_title),
                    message = stringResource(R.string.scan_camera_denied),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f)
                ) {
                    Button(onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(AppSpacing.sm))
                        Text(stringResource(R.string.scan_grant_camera))
                    }
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.sm))

            // Scrollable results region. The camera preview above stays fixed
            // (outside the scroll) so its Surface is never detached while scrolling.
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
            ) {
                if (cameraGranted) {
                    Button(
                    onClick = {
                        val redo = {
                            captureAndRecognize(context, imageCapture, selectedLanguage, viewModel,
                                onProgress = onScanProgress,
                                onResult = onScanResult)
                        }
                        lastScanRedo = redo
                        redo()
                        isProcessing = true
                        processingStatus = context.getString(R.string.scan_extracting_text)
                    },
                        enabled = !isProcessing,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(AppSpacing.sm))
                        Text(stringResource(R.string.scan_capture_button))
                    }
                }
                OutlinedButton(
                    onClick = { galleryLauncher.launch("image/*") },
                    enabled = !isProcessing,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Photo, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(AppSpacing.sm))
                    Text(stringResource(R.string.scan_gallery_button))
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.md))

            // Processing indicator
            if (isProcessing) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 2.dp)
                Spacer(Modifier.height(AppSpacing.sm))
                Text(processingStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }

            // Extracted text result
            if (extractedText.isNotEmpty()) {
                Spacer(modifier = Modifier.height(AppSpacing.sm))
                if (!extractionSuccess) {
                    ErrorRetryCard(
                        message = extractedText,
                        onRetry = { lastScanRedo?.invoke() }
                    )
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(AppRadius.large),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(modifier = Modifier.padding(AppSpacing.md)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.DocumentScanner, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(AppSpacing.sm))
                                Text(stringResource(R.string.scan_extracted_title), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Surface(
                                    shape = RoundedCornerShape(AppRadius.medium),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.height(24.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = AppSpacing.sm)) {
                                        Text(getLanguageName(context, selectedLanguage), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                }
                                IconButton(onClick = { extractedText = ""; extractionSuccess = false }) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.notes_desc_close))
                                }
                            }
                            Spacer(modifier = Modifier.height(AppSpacing.sm))
                            Text(
                                text = extractedText,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = if (showFullText) Int.MAX_VALUE else 6,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (extractedText.lines().size > 6) {
                                TextButton(onClick = { showFullText = !showFullText }) {
                                    Text(
                                        if (showFullText) stringResource(R.string.scan_show_less)
                                        else stringResource(R.string.scan_show_more)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(AppSpacing.md))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
                    ) {
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("OCR Text", extractedText)
                                clipboard.setPrimaryClip(clip)
                                scope.launch { scanSnackbarHost.showSnackbar(context.getString(R.string.scan_copied_toast)) }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.scan_copy_button))
                        }
                        Button(
                            onClick = {
                                val saved = extractedText
                                val dateStr = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
                                viewModel.saveOcrAsNote("Scan_$dateStr.txt", saved)
                                scope.launch {
                                    val result = scanSnackbarHost.showSnackbar(
                                        message = context.getString(R.string.scan_saved_toast),
                                        actionLabel = context.getString(R.string.scan_keep_result),
                                        duration = SnackbarDuration.Short
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        extractedText = saved
                                        extractionSuccess = true
                                    } else {
                                        extractedText = ""
                                        extractionSuccess = false
                                    }
                                }
                            },
                            enabled = extractionSuccess,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.scan_save_button))
                        }
                    }
                }
            }

            if (extractedText.isEmpty() && !isProcessing) {
                EmptyState(
                    icon = Icons.Default.DocumentScanner,
                    title = stringResource(R.string.scan_empty_title),
                    message = stringResource(R.string.scan_empty_subtitle),
                    modifier = Modifier.fillMaxWidth(),
                    illustration = { ScanIllustration() }
                )
            }
            }
        }
    }
}

private fun captureAndRecognize(
    context: Context,
    imageCapture: ImageCapture,
    targetLanguage: String,
    viewModel: MainViewModel,
    onProgress: (String) -> Unit,
    onResult: (String) -> Unit
) {
    val photoFile = File(
        context.cacheDir,
        "ocr_${System.currentTimeMillis()}.jpg"
    )
    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

    imageCapture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val uri = Uri.fromFile(photoFile)
                extractAndTranslate(
                    context,
                    uri,
                    targetLanguage,
                    viewModel,
                    onProgress = onProgress,
                    onResult = { text ->
                        onResult(text)
                        try {
                            photoFile.delete()
                        } catch (_: Exception) {
                        }
                    }
                )
            }
            override fun onError(exception: ImageCaptureException) {
                try {
                    photoFile.delete()
                } catch (_: Exception) {
                }
                onResult(context.getString(R.string.scan_capture_failed, exception.message ?: context.getString(R.string.scan_unknown_error)))
            }
        }
    )
}

private fun extractAndTranslate(
    context: Context,
    uri: Uri,
    targetLanguage: String,
    viewModel: MainViewModel,
    onProgress: (String) -> Unit,
    onResult: (String) -> Unit
) {
    try {
        val image = InputImage.fromFilePath(context, uri)
        val recognizer = getTextRecognizer(targetLanguage)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                recognizer.close()
                val extractedText = visionText.text
                if (extractedText.isBlank()) {
                    onResult(context.getString(R.string.scan_no_text_found))
                    return@addOnSuccessListener
                }
                // Now detect language and translate if needed
                detectAndTranslate(context, extractedText, targetLanguage, viewModel, onProgress, onResult)
            }
            .addOnFailureListener { e ->
                recognizer.close()
                onResult(context.getString(R.string.scan_ocr_failed, e.message ?: context.getString(R.string.scan_unknown_error)))
            }
    } catch (e: Exception) {
        onResult(context.getString(R.string.scan_error_prefix, e.message ?: context.getString(R.string.scan_unknown_error)))
    }
}

private fun detectAndTranslate(
    context: Context,
    text: String,
    targetLanguage: String,
    viewModel: MainViewModel,
    onProgress: (String) -> Unit,
    onResult: (String) -> Unit
) {
    val languageIdentifier = com.google.mlkit.nl.languageid.LanguageIdentification.getClient()
    languageIdentifier.identifyPossibleLanguages(text)
        .addOnSuccessListener { identifiedLanguages ->
            languageIdentifier.close()
            if (identifiedLanguages.isNullOrEmpty()) {
                onResult(text)
                return@addOnSuccessListener
            }

            val detectedLang = identifiedLanguages[0].languageTag
            val targetTranslateLang = mapToTranslateLanguage(targetLanguage)

            // If detected language matches target, no translation needed.
            // Compare NLLB FLORES-200 codes so canonical codes line up.
            val targetNllb = com.noteflowai.app.data.nllbCodeFor(targetLanguage)
            val detectedAppCode = com.noteflowai.app.data.normalizeLangCode(detectedLang)
            val detectedNllb = com.noteflowai.app.data.nllbCodeFor(detectedAppCode)
            if (detectedNllb == targetNllb) {
                onResult(text)
                return@addOnSuccessListener
            }

            val targetName = getLanguageName(context, targetLanguage)
            onProgress(context.getString(R.string.scan_translating_status, targetName))

            viewModel.viewModelScope.launch(Dispatchers.IO) {
                // Try NLLB first (fully offline, better quality)
                if (viewModel.isNllbModelReady.value) {
                    val nllbResult = viewModel.translateWithNllb(text, detectedAppCode, targetLanguage, isOcr = true)
                    if (nllbResult != text && nllbResult.length > 0) {
                        // Quality check: if translating TO a non-Latin script (Chinese,
                        // Japanese, Korean, Arabic, etc.), the result should NOT contain
                        // mostly Latin characters. If it does, NLLB only translated some
                        // chunks and left others as English — reject and fall back to ML Kit.
                        val cjkTarget = targetLanguage in listOf("chinese", "japanese", "korean")
                        val arabicTarget = targetLanguage == "arabic"
                        val cyrillicTarget = targetLanguage in listOf("russian", "ukrainian")
                        val shouldCheckScript = cjkTarget || arabicTarget || cyrillicTarget

                        if (shouldCheckScript) {
                            val alphaChars = nllbResult.filter { it.isLetter() }
                            if (alphaChars.isNotEmpty()) {
                                // Use Unicode block to detect Latin characters (includes
                                // accented chars like é, ñ, ü that ASCII range checks miss)
                                val latinRatio = alphaChars.count {
                                    val block = Character.UnicodeBlock.of(it)
                                    block == Character.UnicodeBlock.BASIC_LATIN ||
                                    block == Character.UnicodeBlock.LATIN_1_SUPPLEMENT ||
                                    block == Character.UnicodeBlock.LATIN_EXTENDED_A ||
                                    block == Character.UnicodeBlock.LATIN_EXTENDED_B
                                }.toDouble() / alphaChars.length
                                // If >40% of letters are still Latin, translation is partial
                                if (latinRatio > 0.4) {
                                    Log.w(TAG, "NLLB quality: ${"%.0f".format(latinRatio * 100)}% Latin in ${targetLanguage} output, falling back to ML Kit")
                                    withContext(Dispatchers.IO) {
                                        translateWithMlKit(context, text, detectedLang, targetTranslateLang, onProgress, onResult)
                                    }
                                    return@launch
                                }
                            }
                        }

                        withContext(Dispatchers.Main) {
                            onResult(nllbResult)
                        }
                        return@launch
                    }
                }

                // Fallback to ML Kit Translation (downloads language model on demand).
                // Run OFF the main thread so model download never blocks the UI.
                withContext(Dispatchers.IO) {
                    translateWithMlKit(context, text, detectedLang, targetTranslateLang, onProgress, onResult)
                }
            }
        }
        .addOnFailureListener {
            languageIdentifier.close()
            onResult(text)
        }
}

private fun translateWithMlKit(
    context: Context,
    text: String,
    sourceLang: String,
    targetLang: String,
    onProgress: (String) -> Unit,
    onResult: (String) -> Unit
) {
    // Callbacks may fire from ML Kit listener threads; bounce them to the main thread
    // since they update Compose state.
    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    val safeProgress: (String) -> Unit = { mainHandler.post { onProgress(it) } }
    val safeResult: (String) -> Unit = { mainHandler.post { onResult(it) } }
    try {
        val sourceTranslateLang = TranslateLanguage.fromLanguageTag(sourceLang)
            ?: TranslateLanguage.ENGLISH
        val targetTranslateLang = TranslateLanguage.fromLanguageTag(targetLang)
            ?: return safeResult(text)

        if (sourceTranslateLang == targetTranslateLang) {
            safeResult(text)
            return
        }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceTranslateLang)
            .setTargetLanguage(targetTranslateLang)
            .build()
        val translator = Translation.getClient(options)
        val conditions = DownloadConditions.Builder().build()

        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                translator.translate(text)
                    .addOnSuccessListener { translatedText ->
                        translator.close()
                        safeResult(translatedText)
                    }
                    .addOnFailureListener { e ->
                        translator.close()
                        safeResult(context.getString(R.string.scan_translation_failed, e.message ?: context.getString(R.string.scan_unknown_error), text))
                    }
            }
            .addOnFailureListener { e ->
                translator.close()
                safeResult(context.getString(R.string.scan_model_download_failed, e.message ?: context.getString(R.string.scan_unknown_error), text))
            }
    } catch (e: Exception) {
        safeResult(context.getString(R.string.scan_translation_error, e.message ?: context.getString(R.string.scan_unknown_error), text))
    }
}

private fun mapToTranslateLanguage(languageCode: String): String {
    // ML Kit short code for the fallback translator (null -> English default inside).
    return com.noteflowai.app.data.mlKitCodeFor(languageCode) ?: "en"
}

private fun getLanguageName(context: Context, languageCode: String): String {
    return com.noteflowai.app.data.TRANSLATION_LANGS.firstOrNull { it.code == languageCode }?.let {
        context.getString(it.displayRes)
    } ?: context.getString(R.string.scan_lang_english)
}

private fun getTextRecognizer(language: String): TextRecognizer {
    return when (language) {
        "chinese" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        "japanese" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        "korean" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
}
