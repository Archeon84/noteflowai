package com.noteflowai.app.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.noteflowai.app.ui.theme.AppRadius
import com.noteflowai.app.ui.theme.AppSpacing
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material3.ripple
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import com.noteflowai.app.R
import com.noteflowai.app.data.AudioRecording
import com.noteflowai.app.viewmodel.MainViewModel
import com.noteflowai.app.ui.components.EmptyState
import com.noteflowai.app.ui.components.RecordingsIllustration
import com.noteflowai.app.ui.components.ErrorRetryCard
import com.noteflowai.app.ui.components.ListSkeleton
import com.noteflowai.app.ui.components.MarkdownRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RecordScreen(viewModel: MainViewModel, lazyListState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState()) {
    val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()
    val isPaused by viewModel.isPaused.collectAsStateWithLifecycle()
    val recordings by viewModel.filteredRecordings.collectAsStateWithLifecycle()
    val allRecordings by viewModel.savedRecordings.collectAsStateWithLifecycle()
    val recordingSearchQuery by viewModel.recordingSearchQuery.collectAsStateWithLifecycle()
    val playingRecording by viewModel.playingRecording.collectAsStateWithLifecycle()
    val audioLevels by viewModel.audioLevels.collectAsStateWithLifecycle()
    val selectedRecordingIds by viewModel.selectedRecordingIds.collectAsStateWithLifecycle()
    val isRecordingSelectionMode by viewModel.isRecordingSelectionMode.collectAsStateWithLifecycle()

    val isTranscribing by viewModel.isTranscribing.collectAsStateWithLifecycle()
    val transcribedText by viewModel.transcribedText.collectAsStateWithLifecycle()
    val activeRecording by viewModel.activeTranscriptionRecording.collectAsStateWithLifecycle()
    val isOnlineMode by viewModel.isOnlineMode.collectAsStateWithLifecycle()
    val clipboardManager = @Suppress("DEPRECATION") LocalClipboardManager.current
    val playbackSpeed by viewModel.playbackSpeed.collectAsStateWithLifecycle()
    val transcriptionLanguage by viewModel.transcriptionLanguage.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    val refreshComplete by viewModel.refreshComplete.collectAsStateWithLifecycle()
    val pullToRefreshState = rememberPullToRefreshState()
    var showTranscribeDialog by remember { mutableStateOf(false) }
    var selectedRecordingForTranscribe by remember { mutableStateOf<AudioRecording?>(null) }
    var isRecordingsLoading by remember { mutableStateOf(true) }
    LaunchedEffect(recordings) {
        isRecordingsLoading = false
    }
    LaunchedEffect(refreshComplete) {
        if (refreshComplete > 0) isRefreshing = false
    }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var recordingToDelete by remember { mutableStateOf<AudioRecording?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var recordingToRename by remember { mutableStateOf<AudioRecording?>(null) }
    var renameText by remember { mutableStateOf("") }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.importAudioFile(it) }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    LaunchedEffect(errorMessage) {
        if (errorMessage.isNotEmpty()) {
            snackbarHostState.showSnackbar(message = errorMessage)
            viewModel.clearError()
        }
    }

    val recordingDeleteSnackbarMsg = stringResource(R.string.record_delete_snackbar)
    val recordingUndoLabel = stringResource(R.string.record_undo_label)
    val recordTranscriptSavedMsg = stringResource(R.string.record_transcript_saved)
    fun performDeleteRecordingWithUndo(rec: AudioRecording) {
        viewModel.deleteRecordingWithUndo(rec)
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = recordingDeleteSnackbarMsg,
                actionLabel = recordingUndoLabel,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoDeleteRecording()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    if (isRecordingSelectionMode) {
                        Column {
                            Text(stringResource(R.string.record_selected_count, selectedRecordingIds.size), style = MaterialTheme.typography.titleLarge)
                        }
                    } else {
                        Column {
                            Text(stringResource(R.string.record_title), style = MaterialTheme.typography.titleLarge)
                            Text(if (isOnlineMode) stringResource(R.string.record_online_mode) else stringResource(R.string.record_offline_mode), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                actions = {
                    if (isRecordingSelectionMode) {
                        IconButton(onClick = { viewModel.deleteSelectedRecordings() }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.record_desc_delete_selected), tint = MaterialTheme.colorScheme.error)
                        }
                        IconButton(onClick = { viewModel.clearRecordingSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.record_desc_clear_selection))
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = AppSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Live Transcription Preview Box
            Card(
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 96.dp).padding(vertical = AppSpacing.xs),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(AppRadius.large)
            ) {
                Column(modifier = Modifier.padding(AppSpacing.sm).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isTranscribing || isRecording) {
                                PulsingDot(modifier = Modifier.padding(end = 6.dp))
                            }
                            Text(
                                text = if (isTranscribing || isRecording) stringResource(R.string.record_live_feed) else stringResource(R.string.record_preview),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (transcribedText.isNotEmpty()) {
                            Row {
                                IconButton(onClick = {
                                    viewModel.saveTranscriptionAsNote()
                                    scope.launch { snackbarHostState.showSnackbar(recordTranscriptSavedMsg) }
                                }, modifier = Modifier.size(48.dp)) {
                                    Icon(Icons.Default.Save, contentDescription = stringResource(R.string.record_save_as_note), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = { scope.launch { clipboardManager.setText(AnnotatedString(transcribedText)) } }, modifier = Modifier.size(48.dp)) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.record_desc_copy), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(AppSpacing.xs))
                    if (transcribedText.isNotEmpty()) {
                        MarkdownRenderer(
                            text = transcribedText,
                            textColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(
                            text = if (isRecording) stringResource(R.string.record_listening)
                            else stringResource(R.string.record_placeholder_text),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            // Audio Visualizer
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(vertical = AppSpacing.xs),
                contentAlignment = Alignment.Center
            ) {
                if (isRecording && !isPaused) {
                    AudioVisualizer(audioLevels)
                }
            }

            // Main Record Icon (pulses while recording). Gate the infinite transition
            // so it is only created/running while actually recording (avoids a 60fps
            // recomposition loop when the screen is just being viewed).
            val recordPulse by if (isRecording && !isPaused) {
                rememberInfiniteTransition(label = "recordPulse").animateFloat(
                    initialValue = 1f,
                    targetValue = 1.08f,
                    animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse)
                )
            } else {
                remember { mutableStateOf(1f) }
            }
            val recordHaptic = LocalHapticFeedback.current
            // Gradient accent behind recording button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(vertical = AppSpacing.lg),
                contentAlignment = Alignment.Center
            ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .scale(if (isRecording && !isPaused) recordPulse else 1f)
                    .clip(CircleShape)
                    .background(
                        if (isRecording) {
                            if (isPaused) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                            else MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                        } else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    )
                    .clickable(
                        indication = ripple(bounded = false, radius = 44.dp),
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        if (!isRecording) {
                            recordHaptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.startRecording()
                        } else {
                            recordHaptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.stopRecording()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.MicNone,
                    contentDescription = if (isRecording) stringResource(R.string.record_tap_to_stop) else stringResource(R.string.record_tap_to_record),
                    modifier = Modifier.size(44.dp),
                    tint = if (isRecording && !isPaused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
            } // end gradient wrapper

            Spacer(modifier = Modifier.height(AppSpacing.xs))
            if (isRecording) {
                RecordingTimer(viewModel, isPaused)
            } else {
                Text(
                    text = stringResource(R.string.record_tap_mic),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            Spacer(modifier = Modifier.height(AppSpacing.xs))

            // Recording options (online/offline + language) in one compact row
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.record_toggle_whisper), style = MaterialTheme.typography.labelSmall)
                        Switch(
                            checked = isOnlineMode,
                            onCheckedChange = { viewModel.toggleOnlineMode(it) },
                            modifier = Modifier.padding(horizontal = AppSpacing.sm)
                        )
                        Text(stringResource(R.string.record_toggle_deepgram), style = MaterialTheme.typography.labelSmall)
                    }
                    var lExpanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(
                            onClick = { lExpanded = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.record_language_label, transcriptionLanguage), style = MaterialTheme.typography.bodySmall)
                                Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        }
                        DropdownMenu(expanded = lExpanded, onDismissRequest = { lExpanded = false }) {
                            TRANSCRIPTION_LANGUAGES.forEach { lang ->
                                DropdownMenuItem(text = { Text(lang) }, onClick = { viewModel.setTranscriptionLanguage(lang); lExpanded = false })
                            }
                        }
                    }
                }
            }

            // Control Buttons (pause/resume only relevant while recording)
            if (isRecording) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LargeIconButton(
                        onClick = {
                            recordHaptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (isPaused) viewModel.resumeRecording() else viewModel.pauseRecording()
                        },
                        icon = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        backgroundColor = MaterialTheme.colorScheme.secondary,
                        contentDescription = stringResource(R.string.record_desc_pause_resume)
                    )
                }
                Spacer(modifier = Modifier.height(AppSpacing.sm))
            }
            
            // Local Drive Area (collapsible)
            var showLocalDrive by remember { mutableStateOf(false) }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(AppSpacing.sm).fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { showLocalDrive = !showLocalDrive },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(AppSpacing.sm))
                            Text(stringResource(R.string.record_local_drive_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                        Icon(
                            if (showLocalDrive) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = stringResource(
                                if (showLocalDrive) R.string.common_collapse else R.string.common_expand
                            ),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    if (showLocalDrive) {
                        Spacer(Modifier.height(AppSpacing.sm))
                        Text(stringResource(R.string.record_local_drive_desc), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Spacer(Modifier.height(AppSpacing.sm))
                        Button(onClick = { audioPicker.launch("audio/*") }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                            Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.record_pick_button), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.sm))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.record_history_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.record_items_count, recordings.size), style = MaterialTheme.typography.labelSmall)
            }

            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = AppSpacing.xs)
            ) {
                com.noteflowai.app.ui.components.FreshSearchField(
                    value = recordingSearchQuery,
                    onValueChange = { viewModel.setRecordingSearchQuery(it) },
                    placeholder = stringResource(R.string.record_search_placeholder),
                    clearContentDescription = stringResource(R.string.clear_search)
                )
            }
            
            HorizontalDivider(modifier = Modifier.padding(vertical = AppSpacing.xs))

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { isRefreshing = true; viewModel.refreshData() },
                state = pullToRefreshState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (isRecordingsLoading) {
                    ListSkeleton(count = 4, modifier = Modifier.fillMaxSize().padding(horizontal = AppSpacing.lg).padding(top = AppSpacing.md))
                } else if (recordings.isEmpty()) {
                    if (allRecordings.isNotEmpty() && recordingSearchQuery.isNotBlank()) {
                        EmptyState(
                            icon = Icons.Default.SearchOff,
                            title = stringResource(R.string.record_no_matches),
                            message = stringResource(R.string.record_no_matches_message),
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        EmptyState(
                            icon = Icons.Default.Mic,
                            title = stringResource(R.string.record_empty_title),
                            message = stringResource(R.string.record_empty_subtitle),
                            modifier = Modifier.fillMaxSize(),
                            illustration = { RecordingsIllustration() }
                        )
                    }
                } else {
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                    ) {
                        items(
                            recordings,
                            key = { it.fileName },
                            contentType = { if (it == playingRecording) "playing" else "normal" }
                        ) { rec ->
                            val dismissState = rememberSwipeToDismissBoxState(
                                positionalThreshold = { distance -> distance * 0.75f }
                            )
                            LaunchedEffect(dismissState.currentValue) {
                                if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
                                    performDeleteRecordingWithUndo(rec)
                                }
                            }
                            SwipeToDismissBox(
                                state = dismissState,
                                enableDismissFromStartToEnd = false,
                                enableDismissFromEndToStart = !lazyListState.isScrollInProgress,
                                modifier = Modifier.animateItem(),
                                backgroundContent = {
                                    val revealed = dismissState.currentValue == SwipeToDismissBoxValue.EndToStart
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(if (revealed) MaterialTheme.colorScheme.errorContainer else Color.Transparent)
                                                .padding(horizontal = 20.dp),
                                            contentAlignment = Alignment.CenterEnd
                                        ) {
                                            if (revealed) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = stringResource(R.string.notes_desc_delete),
                                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                                )
                                            }
                                        }
                                }
                            ) {
                                RecordingItem(
                                    recording = rec,
                                    isPlaying = playingRecording == rec,
                                    isSelected = selectedRecordingIds.contains(rec.fileName),
                                    playbackSpeed = if (playingRecording == rec) playbackSpeed else 1.0f,
                                    onPlay = remember(rec.fileName) { { viewModel.playRecording(rec) } },
                                    onStop = remember(rec.fileName) { { viewModel.stopPlayback() } },
                                    onSpeedChange = { viewModel.setPlaybackSpeed(it) },
                                    onClick = remember(rec.fileName, isRecordingSelectionMode) {
                                        {
                                            if (isRecordingSelectionMode) {
                                                viewModel.toggleRecordingSelection(rec.fileName)
                                            } else {
                                                selectedRecordingForTranscribe = rec
                                                showTranscribeDialog = true
                                            }
                                        }
                                    },
                                    onLongClick = remember(rec.fileName) {
                                        { viewModel.toggleRecordingSelection(rec.fileName) }
                                    },
                                    onDelete = remember(rec.fileName) {
                                        {
                                            recordingToDelete = rec
                                            showDeleteConfirm = true
                                        }
                                    },
                                    onShare = remember(rec.fileName) { { shareAudioFile(context, rec) } },
                                    onRename = remember(rec.fileName) {
                                        {
                                            recordingToRename = rec
                                            renameText = rec.fileName.removeSuffix(".wav")
                                            showRenameDialog = true
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showTranscribeDialog && selectedRecordingForTranscribe != null) {
        AlertDialog(
            onDismissRequest = { if (!isTranscribing) showTranscribeDialog = false },
            title = { Text(stringResource(R.string.record_dialog_title), style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 250.dp)) {
                    Text(
                        text = selectedRecordingForTranscribe!!.fileName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.md))
                    
                    if (isTranscribing && activeRecording == selectedRecordingForTranscribe) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            Text(stringResource(R.string.record_ai_processing), modifier = Modifier.padding(top = AppSpacing.md), style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(AppSpacing.lg))
                            Button(onClick = { viewModel.stopTranscription() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                                Text(stringResource(R.string.record_stop_ai), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    } else if (transcribedText.isNotEmpty() && activeRecording == selectedRecordingForTranscribe) {
                        if (transcribedText.startsWith("Error:")) {
                            ErrorRetryCard(
                                message = transcribedText.removePrefix("Error: ").removePrefix("Error:"),
                                onRetry = {
                                    selectedRecordingForTranscribe?.let { viewModel.transcribeRecording(it) }
                                }
                            )
                        } else {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(AppSpacing.md).verticalScroll(rememberScrollState())) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        IconButton(onClick = { scope.launch { clipboardManager.setText(AnnotatedString(transcribedText)) } }, modifier = Modifier.size(48.dp)) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.record_desc_copy), modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    MarkdownRenderer(
                                        text = transcribedText,
                                        textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    } else {
                        Text(stringResource(R.string.record_transcribe_hint), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (transcribedText.isNotEmpty() && activeRecording == selectedRecordingForTranscribe) {
                        Button(
                            onClick = {
                                viewModel.saveTranscriptionAsNote()
                                showTranscribeDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(AppSpacing.sm))
                            Text(stringResource(R.string.record_save_as_note), style = MaterialTheme.typography.labelLarge)
                        }
                        Spacer(modifier = Modifier.height(AppSpacing.sm))
                    }
                    
                    if (!isTranscribing) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                            Button(
                                onClick = { viewModel.transcribeRecording(selectedRecordingForTranscribe!!) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.record_transcribe_button), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            },
            dismissButton = {
                if (!isTranscribing) {
                    TextButton(onClick = { showTranscribeDialog = false }) {
                        Text(stringResource(R.string.record_close_button), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        )
    }

    if (showDeleteConfirm && recordingToDelete != null) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.record_delete_title),
            message = stringResource(R.string.record_delete_message, recordingToDelete!!.fileName),
            onConfirm = {
                performDeleteRecordingWithUndo(recordingToDelete!!)
                showDeleteConfirm = false
                recordingToDelete = null
            },
            onDismiss = {
                showDeleteConfirm = false
                recordingToDelete = null
            }
        )
    }

    if (showRenameDialog && recordingToRename != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.record_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.record_file_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (renameText.isNotBlank()) {
                            viewModel.renameRecording(recordingToRename!!, renameText)
                            showRenameDialog = false
                            recordingToRename = null
                        }
                    }
                ) { Text(stringResource(R.string.record_save_button)) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text(stringResource(R.string.record_cancel_button)) }
            }
        )
    }
}

@Composable
private fun RecordingTimer(viewModel: MainViewModel, isPaused: Boolean) {
    val seconds by viewModel.recordingSeconds.collectAsStateWithLifecycle()
    val formatted = String.format("%02d:%02d", seconds / 60, seconds % 60)
    val displayText = if (isPaused) stringResource(R.string.record_paused, formatted) else stringResource(R.string.record_recording, formatted)
    Text(
        text = displayText,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = if (!isPaused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
fun PulsingDot(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val alpha by rememberInfiniteTransition(label = "dotPulse").animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse)
    )
    Box(modifier = modifier.size(8.dp).background(color.copy(alpha = alpha), CircleShape))
}

@Composable
fun AudioVisualizer(levels: List<Float>) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val barWidth = 8.dp.toPx()
        val spacing = 4.dp.toPx()
        val maxBars = (width / (barWidth + spacing)).toInt()
        
        val displayLevels = levels.takeLast(maxBars)
        
        displayLevels.forEachIndexed { index, level ->
            val barHeight = (level * height * 2).coerceIn(4f, height)
            val x = width - (displayLevels.size - index) * (barWidth + spacing)
            drawLine(
                color = color.copy(alpha = (index / displayLevels.size.toFloat()).coerceAtLeast(0.3f)),
                start = Offset(x, (height - barHeight) / 2),
                end = Offset(x, (height + barHeight) / 2),
                strokeWidth = barWidth
            )
        }
    }
}

@Composable
fun LargeIconButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    backgroundColor: Color,
    contentDescription: String
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
        color = backgroundColor,
        tonalElevation = 4.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun WaveformVisualizer(filePath: String, isPlaying: Boolean = false, modifier: Modifier = Modifier) {
    val samples by produceState(ShortArray(0), filePath) {
        value = withContext(Dispatchers.IO) {
            try {
                val raf = java.io.RandomAccessFile(filePath, "r")
                raf.seek(44)
                val bytes = ByteArray(minOf(raf.length() - 44, 8000L).toInt())
                raf.readFully(bytes)
                raf.close()
                val shorts = ShortArray(bytes.size / 2)
                java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                val step = maxOf(1, shorts.size / 60)
                ShortArray(60) { i -> shorts[minOf(i * step, shorts.size - 1)] }
            } catch (_: Exception) { ShortArray(0) }
        }
    }
    val playedColor = MaterialTheme.colorScheme.primary
    val unplayedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    val animProgress by if (isPlaying) {
        infiniteTransition.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ), label = "waveform-progress"
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }
    if (samples.isNotEmpty()) {
        Canvas(modifier = modifier.fillMaxWidth().height(40.dp)) {
            val barWidth = size.width / samples.size
            val maxVal = samples.maxOfOrNull { kotlin.math.abs(it.toInt()) }?.toFloat()?.coerceAtLeast(1f) ?: 1f
            samples.forEachIndexed { i, sample ->
                val normalizedHeight = (kotlin.math.abs(sample.toInt()) / maxVal) * size.height * 0.9f
                val played = i.toFloat() / samples.size < animProgress
                drawRect(
                    color = if (played) playedColor else unplayedColor,
                    topLeft = Offset(i * barWidth, (size.height - normalizedHeight) / 2),
                    size = androidx.compose.ui.geometry.Size(barWidth * 0.7f, normalizedHeight)
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RecordingItem(
    recording: AudioRecording, 
    isPlaying: Boolean,
    isSelected: Boolean = false,
    playbackSpeed: Float = 1.0f,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onSpeedChange: (Float) -> Unit = {},
    onClick: () -> Unit, 
    onLongClick: (() -> Unit)? = null,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .let { mod ->
                if (onLongClick != null) mod.then(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick))
                else mod.clickable(onClick = onClick)
            },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = if (isSelected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(AppRadius.large)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = if (isPlaying) onStop else onPlay, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = stringResource(if (isPlaying) R.string.record_desc_stop_playback else R.string.record_desc_play),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            
            Column(modifier = Modifier.weight(1f).padding(horizontal = AppSpacing.sm)) {
                Text(
                    text = recording.fileName, 
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val durationMins = recording.durationSeconds / 60
                val durationSecs = recording.durationSeconds % 60
                val sizeKb = recording.fileSize / 1024
                val sizeText = if (sizeKb > 1024) "${String.format("%.1f", sizeKb / 1024f)} MB" else "$sizeKb KB"
                Text(
                    text = "${recording.lastModified} | ${String.format("%02d:%02d", durationMins, durationSecs)} | $sizeText",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.record_desc_overflow),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.record_menu_transcribe)) },
                        leadingIcon = { Icon(Icons.Default.Transcribe, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        onClick = { menuExpanded = false; onClick() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.record_menu_share)) },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        onClick = { menuExpanded = false; onShare() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.record_menu_rename)) },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        onClick = { menuExpanded = false; onRename() }
                    )
                }
            }
        }
        if (isPlaying) {
            WaveformVisualizer(filePath = recording.filePath, isPlaying = true, modifier = Modifier.padding(horizontal = 10.dp, vertical = AppSpacing.xs))
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = AppSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.record_speed_label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                listOf(0.5f, 1.0f, 1.5f, 2.0f).forEach { speed ->
                    Surface(
                        onClick = { onSpeedChange(speed) },
                        modifier = Modifier.minimumInteractiveComponentSize(),
                        color = if (playbackSpeed == speed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(AppRadius.small)
                    ) {
                        Text(
                            text = "${speed}x",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = if (playbackSpeed == speed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

private fun shareAudioFile(context: android.content.Context, recording: AudioRecording) {
    val file = File(recording.filePath)
    if (!file.exists()) return

    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "audio/wav"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.record_share_audio)))
}
