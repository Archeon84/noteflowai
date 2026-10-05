package com.noteflowai.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noteflowai.app.R
import com.noteflowai.app.data.NoteFile
import com.noteflowai.app.data.noteDisplayTitle
import com.noteflowai.app.data.suggestions.NoteSuggestion
import kotlinx.coroutines.launch
import com.noteflowai.app.ui.components.LanguagePickerDialog
import com.noteflowai.app.ui.components.MarkdownFormattingToolbar
import com.noteflowai.app.ui.components.MarkdownRenderer
import com.noteflowai.app.ui.components.ProcessingStatusCard
import com.noteflowai.app.ui.theme.AppRadius
import com.noteflowai.app.ui.theme.AppSpacing
import com.noteflowai.app.viewmodel.MainViewModel

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun NoteDetailContent(
    viewModel: MainViewModel,
    slideViewModel: com.noteflowai.app.viewmodel.SlideViewModel,
    onBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val selectedNote by viewModel.selectedNote.collectAsStateWithLifecycle()
    val isSpeaking by viewModel.isSpeaking.collectAsStateWithLifecycle()
    val isProcessingAi by viewModel.isProcessingAi.collectAsStateWithLifecycle()
    val aiProcessingStep by viewModel.aiProcessingStep.collectAsStateWithLifecycle()
    val suggestions by viewModel.noteSuggestions.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    // Haptic when AI processing completes
    var wasProcessingAi by remember { mutableStateOf(false) }
    LaunchedEffect(isProcessingAi) {
        if (wasProcessingAi && !isProcessingAi) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        wasProcessingAi = isProcessingAi
    }

    val catAll = stringResource(R.string.notes_category_all)
    val catWork = stringResource(R.string.notes_category_work)
    val catPersonal = stringResource(R.string.notes_category_personal)
    val catIdeas = stringResource(R.string.notes_category_ideas)
    val catOther = stringResource(R.string.notes_category_other)
    val categories = remember(catAll, catWork, catPersonal, catIdeas, catOther) {
        listOf(catAll, catWork, catPersonal, catIdeas, catOther)
    }

    val note = selectedNote
    if (note == null) return

    val scrollState = rememberScrollState()
    val highlightTarget by viewModel.highlightTargetText.collectAsStateWithLifecycle()
    var showJumpNotice by remember { mutableStateOf(false) }

    LaunchedEffect(note.fileName, highlightTarget) {
        val target = highlightTarget
        if (!target.isNullOrBlank()) {
            val query = target.take(50).trim()
            val index = note.content.indexOf(query, ignoreCase = true)
            if (index >= 0) {
                showJumpNotice = true
                val progress = index.toFloat() / note.content.length.coerceAtLeast(1)
                val targetScroll = (scrollState.maxValue * progress).toInt()
                scrollState.animateScrollTo(targetScroll)
            }
        }
    }

    var isEditingContent by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTitleText by remember(note.fileName) {
        mutableStateOf(note.fileName.removeSuffix(".json").removeSuffix(".txt"))
    }
    var showDetails by remember { mutableStateOf(false) }
    var editedContent by remember { mutableStateOf(note.content) }
    var textFieldValue by remember(note.content) {
        mutableStateOf(TextFieldValue(text = note.content, selection = TextRange(0)))
    }
    val wordCount = remember(note.content) {
        val trimmed = note.content.trim()
        if (trimmed.isEmpty()) 0 else trimmed.split(Regex("\\s+")).size
    }

    // Keep editedContent in sync with textFieldValue for suggestion analysis
    LaunchedEffect(textFieldValue.text) {
        editedContent = textFieldValue.text
    }

    // Trigger suggestion analysis on content changes
    LaunchedEffect(editedContent) {
        viewModel.analyzeCurrentNote(
            noteFileName = note.fileName,
            content = editedContent,
            tags = note.tags
        )
    }

    LaunchedEffect(note.fileName) {
        textFieldValue = TextFieldValue(text = note.content, selection = TextRange(0))
        renameTitleText = note.fileName.removeSuffix(".json").removeSuffix(".txt")
        isEditingContent = false
    }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var noteToDelete by remember { mutableStateOf<NoteFile?>(null) }

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val deleteSnackbarMsg = stringResource(R.string.notes_delete_snackbar)
    val undoActionLabel = stringResource(R.string.record_undo_label)
    fun performDeleteWithUndo(n: NoteFile) {
        viewModel.deleteNoteWithUndo(n)
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = deleteSnackbarMsg,
                actionLabel = undoActionLabel,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoDeleteNote()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column(
                        modifier = Modifier
                            .clickable {
                                renameTitleText = note.fileName.removeSuffix(".json").removeSuffix(".txt")
                                showRenameDialog = true
                            }
                            .padding(vertical = 2.dp)
                    ) {
                        Text(
                            text = note.fileName.removeSuffix(".json").removeSuffix(".txt"),
                            maxLines = 1,
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
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (note.category.isNotBlank() && note.category != catAll) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(AppRadius.small)
                                ) {
                                    Text(
                                        text = note.category,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                    )
                                }
                            }
                            Text(
                                text = stringResource(R.string.notes_word_count, wordCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            )
                            if (note.pinned) {
                                Icon(
                                    Icons.Default.PushPin,
                                    contentDescription = stringResource(R.string.notes_desc_pinned),
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.clearSelection()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.notes_desc_close))
                    }
                },
                actions = {
                    if (isEditingContent) {
                        IconButton(
                            onClick = {
                                val restored = viewModel.undoNoteEdit(textFieldValue.text)
                                if (restored != null) textFieldValue = textFieldValue.copy(text = restored)
                            },
                            enabled = viewModel.canUndoNoteEdit
                        ) {
                            @Suppress("DEPRECATION")
                            Icon(Icons.Default.Undo, contentDescription = stringResource(R.string.notes_desc_undo), modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = {
                                val redone = viewModel.redoNoteEdit(textFieldValue.text)
                                if (redone != null) textFieldValue = textFieldValue.copy(text = redone)
                            },
                            enabled = viewModel.canRedoNoteEdit
                        ) {
                            @Suppress("DEPRECATION")
                            Icon(Icons.Default.Redo, contentDescription = stringResource(R.string.notes_desc_redo), modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = {
                                viewModel.updateNoteContent(note, textFieldValue.text)
                                isEditingContent = false
                            }
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = stringResource(R.string.notes_edit_save),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    } else {
                        // AI Studio & Tools Toggle Button
                        IconButton(
                            onClick = {
                                com.noteflowai.app.ui.theme.Haptics.tap(context)
                                showDetails = !showDetails
                            }
                        ) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = stringResource(R.string.notes_tools_title),
                                tint = if (showDetails) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Pin Note
                        IconButton(
                            onClick = {
                                com.noteflowai.app.ui.theme.Haptics.tap(context)
                                viewModel.toggleNotePin(note)
                            }
                        ) {
                            Icon(
                                Icons.Default.PushPin,
                                contentDescription = if (note.pinned) stringResource(R.string.notes_desc_unpin) else stringResource(R.string.notes_desc_pin),
                                tint = if (note.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // More overflow menu
                        var moreExpanded by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { moreExpanded = true }) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.notes_desc_more),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = moreExpanded,
                                onDismissRequest = { moreExpanded = false },
                                shape = RoundedCornerShape(AppRadius.medium)
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(if (isSpeaking) stringResource(R.string.notes_desc_stop_speaking) else stringResource(R.string.notes_desc_read_aloud))
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (isSpeaking) Icons.Default.Stop else Icons.Default.VolumeUp,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = if (isSpeaking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    onClick = {
                                        moreExpanded = false
                                        com.noteflowai.app.ui.theme.Haptics.tap(context)
                                        if (isSpeaking) viewModel.stopSpeaking()
                                        else viewModel.speakAiResponse(editedContent)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.notes_menu_rename)) },
                                    leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        moreExpanded = false
                                        renameTitleText = note.fileName.removeSuffix(".json").removeSuffix(".txt")
                                        showRenameDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.notes_menu_generate_slides)) },
                                    leadingIcon = { Icon(Icons.Default.Slideshow, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        moreExpanded = false
                                        slideViewModel.showSlideGenerateDialog(note)
                                    }
                                )
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.notes_menu_export_pdf)) },
                                    leadingIcon = { Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    onClick = { moreExpanded = false; viewModel.exportNoteAsPdf(context, note) }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.notes_menu_export_doc)) },
                                    leadingIcon = { Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    onClick = { moreExpanded = false; viewModel.exportNoteAsDoc(context, note) }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.notes_menu_export_html)) },
                                    leadingIcon = { Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    onClick = { moreExpanded = false; viewModel.exportNoteAsHtml(context, note) }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.notes_menu_export_txt)) },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.TextSnippet, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    onClick = { moreExpanded = false; viewModel.exportNoteAsTxt(context, note) }
                                )
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.notes_menu_delete), color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) },
                                    onClick = { moreExpanded = false; noteToDelete = note; showDeleteConfirm = true }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
        ) {
            // Main Content Scrollable Area
            Box(modifier = Modifier.weight(1f)) {
                if (isEditingContent) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Formatting toolbar
                        MarkdownFormattingToolbar(
                            text = textFieldValue.text,
                            selection = textFieldValue.selection,
                            onFormat = { newText, newSelection ->
                                textFieldValue = textFieldValue.copy(
                                    text = newText,
                                    selection = newSelection
                                )
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // Text editor
                        OutlinedTextField(
                            value = textFieldValue,
                            onValueChange = { newValue ->
                                textFieldValue = newValue
                            },
                            modifier = Modifier.fillMaxSize(),
                            textStyle = MaterialTheme.typography.bodyLarge,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent
                            )
                        )
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = AppSpacing.lg)) {

                        AnimatedVisibility(
                            visible = showDetails,
                            enter = expandVertically(expandFrom = Alignment.Top),
                            exit = shrinkVertically(shrinkTowards = Alignment.Top)
                        ) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = AppSpacing.sm),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                ),
                                shape = RoundedCornerShape(AppRadius.large)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(AppSpacing.md)
                                ) {
                                    // Header
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                                        ) {
                                            Icon(
                                                Icons.Default.AutoAwesome,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = stringResource(R.string.notes_tools_title),
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        IconButton(
                                            onClick = { showDetails = false },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = stringResource(R.string.notes_desc_close),
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(AppSpacing.sm))

                                    // AI Studio Actions
                                    Text(
                                        text = stringResource(R.string.notes_section_tools),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(AppSpacing.xs))
                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                                        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                                    ) {
                                        ActionChip(
                                            icon = Icons.AutoMirrored.Filled.Notes,
                                            label = stringResource(R.string.notes_action_summarize),
                                            enabled = !isProcessingAi
                                        ) {
                                            com.noteflowai.app.ui.theme.Haptics.tap(context)
                                            viewModel.summarizeNote(note)
                                        }
                                        ActionChip(
                                            icon = Icons.Default.Spellcheck,
                                            label = stringResource(R.string.notes_action_proofread),
                                            enabled = !isProcessingAi
                                        ) {
                                            com.noteflowai.app.ui.theme.Haptics.tap(context)
                                            viewModel.proofreadNote(note)
                                        }
                                        ActionChip(
                                            icon = Icons.Default.Edit,
                                            label = stringResource(R.string.notes_action_rewrite),
                                            enabled = !isProcessingAi
                                        ) {
                                            com.noteflowai.app.ui.theme.Haptics.tap(context)
                                            viewModel.rewriteNote(note)
                                        }

                                        var showLangPicker by remember { mutableStateOf(false) }
                                        ActionChip(
                                            icon = Icons.Default.Translate,
                                            label = stringResource(R.string.notes_translate_button),
                                            enabled = !isProcessingAi
                                        ) {
                                            com.noteflowai.app.ui.theme.Haptics.tap(context)
                                            showLangPicker = true
                                        }
                                        if (showLangPicker) {
                                            LanguagePickerDialog(
                                                selectedCode = "",
                                                excludeCodes = emptyList(),
                                                onSelect = {
                                                    viewModel.translateExistingNote(note, it)
                                                    viewModel.clearSelection()
                                                    onBack()
                                                },
                                                onDismiss = { showLangPicker = false }
                                            )
                                        }
                                    }

                                    if (isProcessingAi) {
                                        Spacer(modifier = Modifier.height(AppSpacing.sm))
                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                            shape = RoundedCornerShape(AppRadius.medium),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs)
                                            ) {
                                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                                Spacer(modifier = Modifier.width(AppSpacing.sm))
                                                Text(
                                                    aiProcessingStep.ifEmpty { stringResource(R.string.notes_ai_processing) },
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                    Spacer(modifier = Modifier.height(AppSpacing.sm))

                                    // Category Section
                                    Text(
                                        text = stringResource(R.string.notes_section_category),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(AppSpacing.xs))
                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                                        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                                    ) {
                                        categories.filter { it != catAll }.forEach { cat ->
                                            val isSelected = note.category == cat
                                            FilterChip(
                                                selected = isSelected,
                                                onClick = {
                                                    com.noteflowai.app.ui.theme.Haptics.tap(context)
                                                    viewModel.updateNoteCategory(note, cat)
                                                },
                                                label = { Text(cat, style = MaterialTheme.typography.labelSmall) }
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                    Spacer(modifier = Modifier.height(AppSpacing.sm))

                                    // Tags Section
                                    Text(
                                        text = stringResource(R.string.notes_section_tags),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(AppSpacing.xs))
                                    var tagInput by remember { mutableStateOf("") }
                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                                        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                                    ) {
                                        note.tags.forEach { tag ->
                                            InputChip(
                                                selected = false,
                                                onClick = { viewModel.updateNoteTags(note, note.tags - tag) },
                                                label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                                                trailingIcon = {
                                                    Icon(
                                                        Icons.Default.Close,
                                                        contentDescription = stringResource(R.string.notes_desc_remove_tag),
                                                        modifier = Modifier.size(12.dp)
                                                    )
                                                }
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(AppSpacing.xs))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(
                                            value = tagInput,
                                            onValueChange = { tagInput = it },
                                            modifier = Modifier.weight(1f),
                                            placeholder = { Text(stringResource(R.string.notes_add_tag_placeholder), style = MaterialTheme.typography.bodySmall) },
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.bodySmall,
                                            shape = RoundedCornerShape(AppRadius.medium)
                                        )
                                        Spacer(modifier = Modifier.width(AppSpacing.sm))
                                        IconButton(
                                            onClick = {
                                                if (tagInput.isNotBlank()) {
                                                    viewModel.updateNoteTags(note, note.tags + tagInput.trim())
                                                    tagInput = ""
                                                }
                                            }
                                        ) {
                                            Icon(
                                                Icons.Default.Add,
                                                contentDescription = stringResource(R.string.notes_desc_add_tag),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }

                                    // Connected Notes Section
                                    val connectedNotes = remember(note.fileName) { viewModel.getConnectedNotes(note.fileName) }
                                    if (connectedNotes.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(AppSpacing.sm))
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                        Spacer(modifier = Modifier.height(AppSpacing.sm))
                                        Text(
                                            stringResource(R.string.notes_section_connections),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(modifier = Modifier.height(AppSpacing.xs))
                                        FlowRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            connectedNotes.forEach { (connFileName, strength) ->
                                                val connNote = viewModel.savedNotes.value.find { it.fileName == connFileName }
                                                val displayTitle = connNote?.fileName?.noteDisplayTitle() ?: connFileName.noteDisplayTitle()
                                                val strengthLabel = when {
                                                    strength >= 0.8f -> stringResource(R.string.note_connection_strong)
                                                    strength >= 0.4f -> stringResource(R.string.note_connection_medium)
                                                    else -> stringResource(R.string.note_connection_weak)
                                                }
                                                InputChip(
                                                    selected = false,
                                                    onClick = { viewModel.selectNoteByFileName(connFileName) },
                                                    label = {
                                                        Text(
                                                            displayTitle,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    },
                                                    leadingIcon = {
                                                        Icon(
                                                            Icons.Default.Link,
                                                            contentDescription = strengthLabel,
                                                            modifier = Modifier.size(14.dp),
                                                            tint = when {
                                                                strength >= 0.8f -> MaterialTheme.colorScheme.primary
                                                                strength >= 0.4f -> MaterialTheme.colorScheme.tertiary
                                                                else -> MaterialTheme.colorScheme.outline
                                                            }
                                                        )
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(AppSpacing.sm))
                        }

                        // ── Processing Status ───────────────────────────
                        viewModel.processingStatusFor(note.fileName)?.let { status ->
                            Spacer(modifier = Modifier.height(AppSpacing.md))
                            ProcessingStatusCard(
                                status = status,
                                onRetry = { status.stage?.let { stage -> viewModel.retrySource(note.fileName, stage) } },
                                onCancel = { viewModel.cancelSource(note.fileName) }
                            )
                            Spacer(modifier = Modifier.height(AppSpacing.lg))
                        }

                        if (showJumpNotice && !highlightTarget.isNullOrBlank()) {
                            Surface(
                                shape = RoundedCornerShape(AppRadius.medium),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = 720.dp)
                                    .padding(bottom = AppSpacing.sm)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = AppSpacing.xs),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.MenuBook,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(AppSpacing.xs))
                                        Text(
                                            text = stringResource(R.string.citation_verified) + " — Jumped to cited section",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            showJumpNotice = false
                                            viewModel.clearHighlightTargetText()
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = stringResource(R.string.notes_desc_close),
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            }
                        }

                        // Quiet Intelligence: wider reading column (720dp), centered on large screens
                        MarkdownRenderer(
                            text = note.content,
                            textColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 720.dp),
                            onChecklistToggle = { lineNumber, _ -> viewModel.toggleChecklistItem(note, lineNumber) }
                        )
                        Spacer(modifier = Modifier.height(40.dp))
                    }
                }
            }

            // Floating Bottom Edit Button
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomEnd) {
                if (isEditingContent) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                        modifier = Modifier.padding(AppSpacing.sm)
                    ) {
                        Button(onClick = {
                            viewModel.updateNoteContent(note, textFieldValue.text)
                            isEditingContent = false
                        }) {
                            Text(stringResource(R.string.notes_edit_save))
                        }
                        TextButton(onClick = {
                            isEditingContent = false
                            textFieldValue = TextFieldValue(text = note.content, selection = TextRange(0))
                        }) {
                            Text(stringResource(R.string.notes_edit_cancel))
                        }
                    }
                } else {
                    FloatingActionButton(
                        onClick = { isEditingContent = true },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(AppSpacing.sm)
                    ) {
                        Icon(Icons.Default.EditNote, contentDescription = stringResource(R.string.notes_desc_edit))
                    }
                }
            }
        }
    }

    if (showDeleteConfirm && noteToDelete != null) {
        val deletingNote = noteToDelete!!
        ConfirmDeleteDialog(
            title = stringResource(R.string.notes_delete_dialog_title),
            message = stringResource(R.string.notes_delete_dialog_message, deletingNote.fileName.removeSuffix(".txt").removeSuffix(".json")),
            onConfirm = {
                performDeleteWithUndo(deletingNote)
                showDeleteConfirm = false
                noteToDelete = null
                viewModel.clearSelection()
                onBack()
            },
            onDismiss = {
                showDeleteConfirm = false
                noteToDelete = null
            }
        )
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.notes_rename_dialog_title), fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameTitleText,
                    onValueChange = { renameTitleText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(AppRadius.medium),
                    label = { Text(stringResource(R.string.notes_new_note_title_label)) }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = renameTitleText.trim()
                        if (trimmed.isNotBlank()) {
                            viewModel.renameNoteTitle(note, trimmed)
                        }
                        showRenameDialog = false
                    }
                ) {
                    Text(stringResource(R.string.notes_rename_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.notes_edit_cancel))
                }
            },
            shape = RoundedCornerShape(AppRadius.large)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartSuggestionBar(
    suggestions: List<NoteSuggestion>,
    onLinkClick: (NoteSuggestion.LinkSuggestion) -> Unit,
    onMergeClick: (NoteSuggestion.MergeSuggestion) -> Unit,
    onTitleClick: (NoteSuggestion.TitleSuggestion) -> Unit,
    onTagClick: (NoteSuggestion.TagSuggestion) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(AppRadius.large)
    ) {
        Column(modifier = Modifier.padding(AppSpacing.lg)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.notes_suggestions_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                TextButton(onClick = onDismiss, contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(R.string.notes_suggestions_dismiss), style = MaterialTheme.typography.labelSmall)
                }
            }

            // Memoize the type filters — recomputing 4x per recomposition is a needless cost.
            val titleSuggestions = remember(suggestions) { suggestions.filterIsInstance<NoteSuggestion.TitleSuggestion>() }
            val tagSuggestions = remember(suggestions) { suggestions.filterIsInstance<NoteSuggestion.TagSuggestion>() }
            val linkSuggestions = remember(suggestions) { suggestions.filterIsInstance<NoteSuggestion.LinkSuggestion>() }
            val mergeSuggestions = remember(suggestions) { suggestions.filterIsInstance<NoteSuggestion.MergeSuggestion>() }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Title suggestions
                items(titleSuggestions, key = { it.suggestedTitle }) { suggestion ->
                    SuggestionChip(
                        onClick = { onTitleClick(suggestion) },
                        label = {
                            Column {
                                Text(stringResource(R.string.notes_suggestion_title_label), style = MaterialTheme.typography.labelSmall)
                                Text(
                                    suggestion.suggestedTitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1
                                )
                            }
                        },
                        icon = {
                            Icon(Icons.Default.Title, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    )
                }

                // Tag suggestions
                items(tagSuggestions, key = { it.tag }) { suggestion ->
                    SuggestionChip(
                        onClick = { onTagClick(suggestion) },
                        label = {
                            Text("+${suggestion.tag}", style = MaterialTheme.typography.bodySmall)
                        },
                        icon = {
                            Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    )
                }

                // Link suggestions
                items(linkSuggestions, key = { it.targetTitle }) { suggestion ->
                    SuggestionChip(
                        onClick = { onLinkClick(suggestion) },
                        label = {
                            Column {
                                Text(stringResource(R.string.notes_suggestion_link_label), style = MaterialTheme.typography.labelSmall)
                                Text(
                                    suggestion.targetTitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1
                                )
                            }
                        },
                        icon = {
                            Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    )
                }

                // Merge suggestions
                items(mergeSuggestions, key = { it.targetTitle }) { suggestion ->
                    SuggestionChip(
                        onClick = { onMergeClick(suggestion) },
                        label = {
                            Column {
                                Text(stringResource(R.string.notes_suggestion_merge_label), style = MaterialTheme.typography.labelSmall)
                                Text(
                                    suggestion.targetTitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1
                                )
                            }
                        },
                        icon = {
                            Icon(Icons.Default.Merge, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        )
                    )
                }
            }
        }
    }
}
