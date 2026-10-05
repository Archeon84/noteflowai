package com.noteflowai.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import com.noteflowai.app.R
import com.noteflowai.app.viewmodel.YouTubeViewModel
import com.noteflowai.app.viewmodel.MainViewModel
import com.noteflowai.app.ui.theme.AppSpacing
import com.noteflowai.app.ui.components.EmptyState
import com.noteflowai.app.ui.components.ErrorRetryCard
import com.noteflowai.app.ui.components.YouTubeSkeleton
import com.noteflowai.app.ui.components.ListSkeleton
import kotlinx.coroutines.launch

// Compile once — this regex is checked on every keystroke of the URL field.
private val YOUTUBE_URL_REGEX =
    Regex("^(https?://)?(www\\.)?(youtube\\.com|youtu\\.be|m\\.youtube\\.com)/(watch\\?v=|shorts/|channel/|c/|@|playlist\\?list=|embed/).+\$")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeScreen(
    viewModel: YouTubeViewModel,
    mainViewModel: MainViewModel,
    slideViewModel: com.noteflowai.app.viewmodel.SlideViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var inputUrl by remember { mutableStateOf("") }
    var urlError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val clipboardManager = @Suppress("DEPRECATION") LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val emptyUrlMsg = stringResource(R.string.youtube_empty_url)
    val invalidUrlMsg = stringResource(R.string.youtube_invalid_url)

    fun isValidYouTubeInput(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        // Accept YouTube URLs (watch, youtu.be, shorts, channel, playlist) or an @handle.
        return YOUTUBE_URL_REGEX.containsMatchIn(t) || t.startsWith("@")
    }

    val noLinkMsg = stringResource(R.string.youtube_no_link_clipboard)
    suspend fun pasteFromClipboard(msg: String) {
        val clip = clipboardManager.getText()?.toString()?.trim() ?: ""
        if (clip.contains("youtube.com") || clip.contains("youtu.be")) {
            inputUrl = clip
            urlError = null
        } else {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    BackHandler(enabled = urlError != null) {
        urlError = null
    }

    com.noteflowai.app.ui.components.FreshScreen(
        title = stringResource(R.string.youtube_title),
        onBack = onBack,
        actions = {
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.youtube_desc_more), tint = MaterialTheme.colorScheme.onSurface)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.youtube_desc_paste)) },
                        leadingIcon = { Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        onClick = {
                            menuExpanded = false
                            coroutineScope.launch { pasteFromClipboard(noLinkMsg) }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.youtube_desc_new_search)) },
                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        onClick = {
                            menuExpanded = false
                            inputUrl = ""
                            viewModel.clearAll()
                        }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = AppSpacing.lg)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
            ) {
                Spacer(modifier = Modifier.height(AppSpacing.xs))

            OutlinedTextField(
                value = inputUrl,
                onValueChange = {
                    inputUrl = it
                    if (urlError != null) urlError = null
                },
                label = { Text(stringResource(R.string.youtube_url_label)) },
                placeholder = { Text(stringResource(R.string.youtube_url_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = urlError != null,
                supportingText = urlError?.let { { Text(it) } },
                leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                trailingIcon = {
                    Row {
                        IconButton(onClick = {
                            coroutineScope.launch { pasteFromClipboard(noLinkMsg) }
                        }) {
                            Icon(Icons.Default.ContentPaste, contentDescription = stringResource(R.string.youtube_desc_paste))
                        }
                        if (inputUrl.isNotEmpty()) {
                            IconButton(onClick = { inputUrl = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.youtube_desc_clear))
                            }
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(AppSpacing.xs))
            Text(
                stringResource(R.string.youtube_input_helper),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            Button(
                onClick = {
                    if (!isValidYouTubeInput(inputUrl)) {
                        urlError = if (inputUrl.isBlank()) {
                            emptyUrlMsg
                        } else {
                            invalidUrlMsg
                        }
                        return@Button
                    }
                    urlError = null
                    viewModel.processUrl(inputUrl)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = inputUrl.isNotBlank() && !uiState.isLoading
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.sm))
                    Text(stringResource(R.string.youtube_fetching))
                } else {
                    Icon(Icons.Default.DownloadForOffline, contentDescription = null)
                    Spacer(modifier = Modifier.width(AppSpacing.sm))
                    Text(stringResource(R.string.youtube_fetch_button))
                }
            }

            if (uiState.error.isNotEmpty()) {
                ErrorRetryCard(
                    message = uiState.error,
                    onRetry = {
                        if (isValidYouTubeInput(inputUrl)) viewModel.processUrl(inputUrl)
                        else viewModel.clearAll()
                    }
                )
            }

            if (uiState.isLoading) {
                YouTubeSkeleton()
            }

            if (uiState.videoTitle.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(AppSpacing.md)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.PlayCircle,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(AppSpacing.sm))
                            Text(
                                uiState.videoTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (uiState.captionLanguage.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(AppSpacing.xs))
                            Text(
                                stringResource(R.string.youtube_captions_label, uiState.captionLanguage),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            if (uiState.hasTranscript && uiState.transcript.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(AppSpacing.md)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Description,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(AppSpacing.sm))
                                Text(
                                    stringResource(R.string.youtube_transcript_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            IconButton(
                                onClick = {
                                    coroutineScope.launch { clipboardManager.setText(AnnotatedString(uiState.transcript)) }
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = stringResource(R.string.youtube_desc_copy),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(AppSpacing.sm))
                        // Bound the transcript layout cost: cap height and scroll internally
                        // so a very long transcript is not one unbounded Text node.
                        SelectionContainer {
                            Box(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                                Text(
                                    uiState.transcript,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }

            if (uiState.summaryError.isNotEmpty()) {
                ErrorRetryCard(
                    message = uiState.summaryError,
                    onRetry = { viewModel.summarizeTranscript() }
                )
            }

            if (uiState.isSummarizing) {
                ListSkeleton(count = 2)
            }

            if (uiState.summary.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(AppSpacing.md)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(AppSpacing.sm))
                                Text(
                                    stringResource(R.string.youtube_summary_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(
                                onClick = {
                                    coroutineScope.launch { clipboardManager.setText(AnnotatedString(uiState.summary)) }
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = stringResource(R.string.youtube_desc_copy),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(AppSpacing.sm))
                        SelectionContainer {
                            Text(
                                uiState.summary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Spacer(modifier = Modifier.height(AppSpacing.sm))
                        OutlinedButton(
                            onClick = { viewModel.saveSummaryAsNote() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(AppSpacing.sm))
                            Text(stringResource(R.string.youtube_save_button))
                        }
                    }
                }
            }

            if (!uiState.isLoading && uiState.videoId.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.PlayCircle,
                    title = stringResource(R.string.youtube_title),
                    message = stringResource(R.string.youtube_empty_hint),
                    action = {
                        OutlinedButton(onClick = {
                            coroutineScope.launch { pasteFromClipboard(noLinkMsg) }
                        }) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.youtube_empty_paste))
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(AppSpacing.lg))
            }

            if (uiState.hasTranscript && uiState.transcript.isNotEmpty()) {
                Spacer(modifier = Modifier.height(AppSpacing.sm))
                Text(
                    stringResource(R.string.youtube_actions_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(AppSpacing.sm))
                HorizontalDivider(modifier = Modifier.padding(vertical = AppSpacing.xs))
                Spacer(modifier = Modifier.height(AppSpacing.sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
                ) {
                    Button(
                        onClick = { viewModel.summarizeTranscript() },
                        modifier = Modifier.weight(1f),
                        enabled = !uiState.isSummarizing
                    ) {
                        if (uiState.isSummarizing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(AppSpacing.sm))
                            Text(stringResource(R.string.youtube_summarizing))
                        } else {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null)
                            Spacer(modifier = Modifier.width(AppSpacing.sm))
                            Text(stringResource(R.string.youtube_summarize_button))
                        }
                    }
                    OutlinedButton(
                        onClick = { slideViewModel.showSlideGenerateDialogFromText(uiState.transcript, uiState.videoTitle) },
                        modifier = Modifier.weight(1f),
                        enabled = uiState.transcript.isNotBlank() && !uiState.isSummarizing
                    ) {
                        Icon(Icons.Default.Slideshow, contentDescription = null)
                        Spacer(modifier = Modifier.width(AppSpacing.sm))
                        Text(stringResource(R.string.youtube_generate_slides))
                    }
                }
            }
            Spacer(modifier = Modifier.height(AppSpacing.sm))
        }
    }

    // Slide Generation Dialogs
    val showSlideGenerateDialog by slideViewModel.showSlideGenerateDialog.collectAsStateWithLifecycle()
    val slideGenerateTitle by slideViewModel.slideGenerateTitle.collectAsStateWithLifecycle()
    val showSlidePreview by slideViewModel.showSlidePreview.collectAsStateWithLifecycle()
    val generatedSlides by slideViewModel.generatedSlides.collectAsStateWithLifecycle()
    val isOnlineMode by mainViewModel.isOnlineMode.collectAsStateWithLifecycle()
    val isProcessingAi by slideViewModel.isProcessing.collectAsStateWithLifecycle()
    val aiProcessingStep by slideViewModel.processingStep.collectAsStateWithLifecycle()

    if (showSlideGenerateDialog) {
        SlideGenerateDialog(
            sourceTitle = slideGenerateTitle,
            isOnlineMode = isOnlineMode,
            isProcessing = isProcessingAi,
            processingStep = aiProcessingStep,
            onDismiss = { slideViewModel.dismissSlideGenerateDialog() },
            onGenerate = { customPrompt, mode -> slideViewModel.generateSlides(customPrompt, mode) }
        )
    }

    if (showSlidePreview && generatedSlides.isNotEmpty()) {
        val context = LocalContext.current
        SlidePreviewDialog(
            slides = generatedSlides,
            title = slideGenerateTitle,
            onDismiss = { slideViewModel.dismissSlidePreview() },
            onShare = { slideViewModel.shareSlidePptx(context) }
        )
    }
}
