package com.noteflowai.app.ui.screens

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noteflowai.app.data.AudioRecording
import com.noteflowai.app.data.NoteFile
import com.noteflowai.app.ui.Screen
import com.noteflowai.app.ui.components.ErrorRetryCard
import com.noteflowai.app.ui.components.HomeDashboardSkeleton
import com.noteflowai.app.ui.components.ProcessingPill
import com.noteflowai.app.ui.theme.AiAccentDark
import com.noteflowai.app.ui.theme.AiAccentLight
import com.noteflowai.app.ui.theme.AiGradientDark
import com.noteflowai.app.ui.theme.AiGradientLight
import com.noteflowai.app.ui.theme.AppRadius
import com.noteflowai.app.ui.theme.AppSpacing
import com.noteflowai.app.ui.theme.Haptics
import com.noteflowai.app.ui.theme.isLightBg
import com.noteflowai.app.viewmodel.MainViewModel
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import com.noteflowai.app.R

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigate: (Screen) -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val allNotes by viewModel.savedNotes.collectAsStateWithLifecycle()
    val allRecordings by viewModel.savedRecordings.collectAsStateWithLifecycle()
    val recentNotes = remember(allNotes) { allNotes.sortedByDescending { it.lastModifiedEpoch }.take(4) }
    val recentRecordings = remember(allRecordings) { allRecordings.sortedByDescending { it.lastModifiedEpoch }.take(4) }

    val isOnlineMode by viewModel.isOnlineMode.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val processingStatusMap by viewModel.processingStatus.collectAsStateWithLifecycle()

    // Memory feature flags — collapsed to a single counted teaser badge.
    val sourceSegmentsEnabled by viewModel.sourceSegmentsEnabled.collectAsStateWithLifecycle()
    val memoryDatabaseEnabled by viewModel.memoryDatabaseEnabled.collectAsStateWithLifecycle()
    val memoryExtractionEnabled by viewModel.memoryExtractionEnabled.collectAsStateWithLifecycle()
    val segmentIndexingEnabled by viewModel.segmentIndexingEnabled.collectAsStateWithLifecycle()
    val decisionTimelineEnabled by viewModel.decisionTimelineEnabled.collectAsStateWithLifecycle()
    val commitmentDashboardEnabled by viewModel.commitmentDashboardEnabled.collectAsStateWithLifecycle()
    val changeAnalysisEnabled by viewModel.changeAnalysisEnabled.collectAsStateWithLifecycle()
    val conflictDetectionEnabled by viewModel.conflictDetectionEnabled.collectAsStateWithLifecycle()
    val weeklyReviewEnabled by viewModel.weeklyReviewEnabled.collectAsStateWithLifecycle()
    val evaluationEnabled by viewModel.evaluationEnabled.collectAsStateWithLifecycle()
    val hasMemoryFeatures = sourceSegmentsEnabled || memoryDatabaseEnabled || memoryExtractionEnabled ||
            segmentIndexingEnabled || decisionTimelineEnabled || commitmentDashboardEnabled ||
            changeAnalysisEnabled || conflictDetectionEnabled || weeklyReviewEnabled ||
            evaluationEnabled

    var isActivityLoading by remember { mutableStateOf(true) }
    var notesEmitted by remember { mutableStateOf(false) }
    var recEmitted by remember { mutableStateOf(false) }
    LaunchedEffect(allNotes) { notesEmitted = true }
    LaunchedEffect(allRecordings) { recEmitted = true }
    LaunchedEffect(notesEmitted, recEmitted, errorMessage) {
        if ((notesEmitted && recEmitted) || errorMessage.isNotEmpty()) {
            isActivityLoading = false
        }
    }

    val scrollState = rememberScrollState()

    var searchText by remember { mutableStateOf("") }
    val isDark = !isLightBg(MaterialTheme.colorScheme.background)
    val gradient = if (isDark) AiGradientDark else AiGradientLight
    val accent = if (isDark) AiAccentDark else AiAccentLight

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(scrollState)
            .padding(horizontal = AppSpacing.lg)
            .padding(top = AppSpacing.lg, bottom = AppSpacing.xxxl),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Top
    ) {
        // Quiet Intelligence: small greeting label + ink headline (gradient reserved for AI moments)
        Text(
            text = stringResource(R.string.home_greeting),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        )
        Spacer(modifier = Modifier.height(AppSpacing.xs))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.home_title),
                style = MaterialTheme.typography.displayMedium.copy(brush = Brush.horizontalGradient(gradient)),
                modifier = Modifier.weight(1f)
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(AppRadius.small)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = AppSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (isOnlineMode) accent else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(8.dp)
                    ) {}
                    Spacer(modifier = Modifier.width(AppSpacing.xs))
                    Text(
                        text = if (isOnlineMode) stringResource(R.string.home_online_badge) else stringResource(R.string.home_offline_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
            IconButton(
                onClick = { onNavigate(Screen.SETTINGS) },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = stringResource(R.string.nav_desc_settings),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
        Spacer(modifier = Modifier.height(AppSpacing.xl))

        // Hero command (Perplexity-inspired): large rounded search field with gradient underline
        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            placeholder = { Text(stringResource(R.string.home_hero_hint), style = MaterialTheme.typography.titleMedium) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.home_search_hint), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)) },
            singleLine = true,
            shape = RoundedCornerShape(AppRadius.medium),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                viewModel.setSearchQuery(searchText.trim())
                onNavigate(Screen.NOTES)
            }),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(AppSpacing.xs))
        Text(
            text = stringResource(R.string.home_hero_caption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(start = AppSpacing.xs)
        )
        Spacer(modifier = Modifier.height(AppSpacing.xl))

        // Quick actions — quieter, single-line row
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            QuickAction(
                icon = Icons.Default.Mic,
                label = stringResource(R.string.nav_label_record),
                onClick = { onNavigate(Screen.RECORD) },
                modifier = Modifier.weight(1f)
            )
            QuickAction(
                icon = Icons.Default.Add,
                label = stringResource(R.string.notes_new_note_dialog_title),
                onClick = {
                    onNavigate(Screen.NOTES)
                    viewModel.showNewNoteDialog()
                },
                modifier = Modifier.weight(1f)
            )
            QuickAction(
                icon = Icons.Default.DocumentScanner,
                label = stringResource(R.string.nav_label_scan),
                onClick = { onNavigate(Screen.SCAN) },
                modifier = Modifier.weight(1f)
            )
            QuickAction(
                icon = Icons.AutoMirrored.Filled.Chat,
                label = stringResource(R.string.nav_label_chat),
                onClick = { onNavigate(Screen.CHAT) },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(AppSpacing.xl))

        // Memory & insights teaser — restrained
        if (hasMemoryFeatures) {
            Surface(
                onClick = { onNavigate(Screen.MEMORY) },
                shape = RoundedCornerShape(AppRadius.large),
                color = Color.Transparent,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(vertical = AppSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(Icons.Default.Psychology, contentDescription = stringResource(R.string.home_memory_teaser), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(AppSpacing.md))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.home_memory_teaser), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.home_memory_teaser_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = stringResource(R.string.common_forward),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(AppSpacing.lg))
        }

        // Recent activity
        if (errorMessage.isNotEmpty()) {
            ErrorRetryCard(
                message = errorMessage,
                onRetry = {
                    isActivityLoading = true
                    viewModel.clearError()
                    viewModel.refreshData()
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(AppSpacing.xs * 2))
        } else if (isActivityLoading) {
            HomeDashboardSkeleton(modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(AppSpacing.xs * 2))
        } else if (recentNotes.isNotEmpty() || recentRecordings.isNotEmpty()) {
            Text(stringResource(R.string.home_recent), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(AppSpacing.md))

            if (recentNotes.isNotEmpty()) {
                Text(stringResource(R.string.home_recent_notes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(start = AppSpacing.xs))
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
                    contentPadding = PaddingValues(end = 4.dp)
                ) {
                    items(recentNotes, key = { it.fileName }) { note ->
                        RecentNoteCard(
                            note = note,
                            onClick = {
                                viewModel.selectNoteByFileName(note.fileName)
                                onNavigate(Screen.NOTE_DETAIL)
                            },
                            modifier = Modifier.widthIn(min = 220.dp, max = 280.dp),
                            status = processingStatusMap[note.fileName],
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                    }
                }
                Spacer(modifier = Modifier.height(AppSpacing.lg))
            }

            if (recentRecordings.isNotEmpty()) {
                Text(stringResource(R.string.home_recent_recordings), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(start = AppSpacing.xs))
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
                    contentPadding = PaddingValues(end = 4.dp)
                ) {
                    items(recentRecordings, key = { it.fileName }) { rec ->
                        RecentRecordingCard(
                            recording = rec,
                            onClick = {
                                onNavigate(Screen.RECORD)
                                viewModel.playRecording(rec)
                            },
                            modifier = Modifier.widthIn(min = 200.dp, max = 260.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(AppSpacing.xs * 2))
        } else if (!isActivityLoading) {
                com.noteflowai.app.ui.components.EmptyState(
                    icon = Icons.Default.History,
                    title = stringResource(R.string.home_recent_empty_title),
                    message = stringResource(R.string.home_recent_empty_message),
                    modifier = Modifier.fillMaxWidth().padding(vertical = AppSpacing.lg),
                    illustration = { com.noteflowai.app.ui.components.RecordingsIllustration() }
                ) {
                    Button(onClick = { onNavigate(Screen.RECORD) }) {
                        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(AppSpacing.sm))
                        Text(stringResource(R.string.home_recent_empty_action))
                    }
                }
                Spacer(modifier = Modifier.height(AppSpacing.xs * 2))
            }

        Spacer(modifier = Modifier.height(40.dp))
    }
}

@Composable
fun QuickAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Surface(
        onClick = {
            Haptics.tap(context)
            onClick()
        },
        shape = RoundedCornerShape(AppRadius.large),
        color = Color.Transparent,
        modifier = modifier.padding(vertical = AppSpacing.xs)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(AppSpacing.xs)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(modifier = Modifier.height(AppSpacing.sm))
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun RecentNoteCard(
    note: NoteFile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    status: com.noteflowai.app.data.memory.model.StatusUiModel? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    Card(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.large))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(AppSpacing.md)) {
            Text(
                text = note.preview.ifBlank { note.fileName },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.let { mod ->
                    if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                        with(sharedTransitionScope) {
                            mod.sharedElement(
                                sharedContentState = rememberSharedContentState(key = "note-title-${note.fileName}"),
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        }
                    } else mod
                }
            )
            Spacer(modifier = Modifier.height(AppSpacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(AppRadius.small)
                ) {
                    Text(
                        text = note.category,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = AppSpacing.sm, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.width(AppSpacing.sm))
                Text(
                    text = note.lastModified,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            status?.let {
                if (it.status != com.noteflowai.app.data.memory.model.UserFacingStatus.READY) {
                    Spacer(modifier = Modifier.height(AppSpacing.xs))
                    ProcessingPill(status = it)
                }
            }
        }
    }
}

@Composable
fun RecentRecordingCard(
    recording: AudioRecording,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.large))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(modifier = Modifier.padding(AppSpacing.md), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.home_recent_recordings), tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(modifier = Modifier.width(AppSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = recording.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(AppSpacing.sm))
                Text(
                    text = formatDuration(recording.durationSeconds),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}
