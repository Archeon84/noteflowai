package com.noteflowai.app.ui.screens

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.noteflowai.app.ui.theme.AppRadius
import com.noteflowai.app.ui.theme.AppSpacing
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noteflowai.app.data.NoteFile
import com.noteflowai.app.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.compose.ui.res.stringResource
import com.noteflowai.app.R
import com.noteflowai.app.ui.components.EmptyState
import com.noteflowai.app.ui.components.NotesIllustration
import com.noteflowai.app.ui.components.LanguagePickerDialog
import com.noteflowai.app.ui.components.NoteListSkeleton
import com.noteflowai.app.ui.components.MarkdownRenderer
import com.noteflowai.app.ui.components.ProcessingPill
import java.util.Locale

// Dialogs default to the theme's 28dp corner, which clips the 24dp content
// padding on text-heavy dialogs. 16dp clears the padding without the heavy curve.
private val DIALOG_SHAPE = RoundedCornerShape(16.dp)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun NotesScreen(
    viewModel: MainViewModel,
    slideViewModel: com.noteflowai.app.viewmodel.SlideViewModel,
    lazyListState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    onNavigate: (com.noteflowai.app.ui.Screen) -> Unit = {},
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val notes by viewModel.filteredNotes.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchHistory by viewModel.searchHistory.collectAsStateWithLifecycle()
    val searchSuggestions by viewModel.searchSuggestions.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val sortOption by viewModel.sortOption.collectAsStateWithLifecycle()
    val selectedNoteIds by viewModel.selectedNoteIds.collectAsStateWithLifecycle()
    val isSelectionMode by viewModel.isSelectionMode.collectAsStateWithLifecycle()
    val isProcessingOffline by viewModel.isProcessingOffline.collectAsStateWithLifecycle()
    val isProcessingAi by viewModel.isProcessingAi.collectAsStateWithLifecycle()
    val aiProcessingStep by viewModel.aiProcessingStep.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val catAll = stringResource(R.string.notes_category_all)
    val catWork = stringResource(R.string.notes_category_work)
    val catPersonal = stringResource(R.string.notes_category_personal)
    val catIdeas = stringResource(R.string.notes_category_ideas)
    val catOther = stringResource(R.string.notes_category_other)
    val sortNewest = stringResource(R.string.notes_sort_newest)
    val sortOldest = stringResource(R.string.notes_sort_oldest)
    val sortAz = stringResource(R.string.notes_sort_name_az)
    val sortZa = stringResource(R.string.notes_sort_name_za)
    val categories = remember(catAll, catWork, catPersonal, catIdeas, catOther) {
        listOf(catAll, catWork, catPersonal, catIdeas, catOther)
    }
    val sortOptions = remember(sortNewest, sortOldest, sortAz, sortZa) {
        listOf(sortNewest, sortOldest, sortAz, sortZa)
    }

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current

    var isNotesLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    val refreshComplete by viewModel.refreshComplete.collectAsStateWithLifecycle()
    LaunchedEffect(notes) {
        if (notes.isNotEmpty()) isNotesLoading = false
    }
    LaunchedEffect(refreshComplete) {
        if (refreshComplete > 0) isRefreshing = false
    }
    // Safety timeout so the skeleton never spins forever when there are no notes.
    // Only clears if notes still haven't arrived after 800ms (e.g. empty list or
    // slow first load) — doesn't force a delay when notes are already in memory.
    LaunchedEffect(Unit) {
        delay(800)
        if (notes.isEmpty()) isNotesLoading = false
    }

    LaunchedEffect(errorMessage) {
        if (errorMessage.isNotEmpty()) {
            snackbarHostState.showSnackbar(errorMessage)
            viewModel.clearError()
        }
    }

    val deleteSnackbarMsg = stringResource(R.string.notes_delete_snackbar)
    val undoActionLabel = stringResource(R.string.record_undo_label)
    fun performDeleteWithUndo(note: NoteFile) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        viewModel.deleteNoteWithUndo(note)
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

    BackHandler(enabled = isSelectionMode) {
        viewModel.clearNoteSelection()
    }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var noteToDelete by remember { mutableStateOf<NoteFile?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                TopAppBar(
                    title = { 
                        if (isSelectionMode) {
                            Text(stringResource(R.string.notes_selected_count, selectedNoteIds.size), style = MaterialTheme.typography.titleLarge)
                        } else {
                            Text(stringResource(R.string.notes_title), style = MaterialTheme.typography.titleLarge)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    actions = {
                        if (isSelectionMode) {
                            IconButton(onClick = {
                                viewModel.deleteSelectedNotes()
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
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.notes_desc_delete_selected), tint = MaterialTheme.colorScheme.error)
                            }
                            IconButton(onClick = { viewModel.clearNoteSelection() }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.notes_desc_clear_selection))
                            }
                        } else {
                            var menuExpanded by remember { mutableStateOf(false) }
                            IconButton(onClick = { onNavigate(com.noteflowai.app.ui.Screen.SETTINGS) }) {
                                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.nav_desc_settings), tint = MaterialTheme.colorScheme.onSurface)
                            }
                            Box {
                                IconButton(onClick = { menuExpanded = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.notes_desc_menu), tint = MaterialTheme.colorScheme.onSurface)
                                }
                                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.notes_menu_new)) },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                        onClick = { menuExpanded = false; viewModel.showNewNoteDialog() }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.notes_menu_select)) },
                                        leadingIcon = { Icon(Icons.Default.CheckBox, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                        enabled = notes.isNotEmpty(),
                                        onClick = {
                                            menuExpanded = false
                                            if (notes.isNotEmpty()) viewModel.toggleNoteSelection(notes.first().fileName)
                                        }
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.notes_menu_sort_by), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) },
                                        enabled = false,
                                        onClick = { }
                                    )
                                sortOptions.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option) },
                                        trailingIcon = if (option == sortOption) { { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) } } else null,
                                        onClick = { viewModel.setSortOption(option); menuExpanded = false }
                                    )
                                }
                                }
                            }
                        }
                    }
                )
                
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.xs)
                ) {
                    com.noteflowai.app.ui.components.FreshSearchField(
                        value = searchQuery,
                        onValueChange = { viewModel.setSearchQuery(it) },
                        placeholder = stringResource(R.string.notes_search_placeholder)
                    )
                }

                // Recent searches & suggestions
                if (searchQuery.isBlank() && searchHistory.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AppSpacing.lg, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.notes_search_recent),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.clearSearchHistory() }) {
                            Text(stringResource(R.string.notes_search_clear_history), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AppSpacing.lg),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        searchHistory.take(6).forEach { query ->
                            InputChip(
                                selected = false,
                                onClick = { viewModel.setSearchQuery(query) },
                                label = { Text(query, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                                leadingIcon = { Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(14.dp)) },
                                trailingIcon = {
                                    IconButton(
                                        onClick = { viewModel.removeSearchHistory(query) },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.notes_desc_remove_tag), modifier = Modifier.size(14.dp))
                                    }
                                }
                            )
                        }
                    }
                }

                if (searchQuery.isNotBlank() && searchSuggestions.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AppSpacing.lg, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        searchSuggestions.forEach { result ->
                            SuggestionChip(
                                onClick = {
                                    viewModel.setSearchQuery(result.title)
                                    viewModel.recordSearchHistory(result.title)
                                },
                                label = { Text(result.title, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                                icon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(14.dp)) }
                            )
                        }
                    }
                }

                Text(
                    stringResource(R.string.notes_count, notes.size),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.lg, vertical = AppSpacing.xs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                PrimaryScrollableTabRow(
                    selectedTabIndex = categories.indexOf(selectedCategory),
                    edgePadding = AppSpacing.lg,
                    containerColor = Color.Transparent,
                    divider = {},
                    indicator = {}
                ) {
                    categories.forEach { category ->
                        val isSelected = selectedCategory == category
                        Tab(
                            selected = isSelected,
                            onClick = { viewModel.setNoteCategory(category) },
                            text = {
                                Surface(
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(AppRadius.small)
                                ) {
                                    Text(
                                        text = category,
                                        modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = 6.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { padding ->
        val pullToRefreshState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                isRefreshing = true
                viewModel.refreshData()
            },
            state = pullToRefreshState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isNotesLoading) {
                NoteListSkeleton(modifier = Modifier.fillMaxSize().padding(horizontal = AppSpacing.lg).padding(top = AppSpacing.md))
            } else if (notes.isEmpty()) {
                if (searchQuery.isEmpty()) {
                    com.noteflowai.app.ui.components.FreshEmptyState(
                        icon = Icons.AutoMirrored.Filled.NoteAdd,
                        title = stringResource(R.string.notes_empty_title),
                        message = stringResource(R.string.notes_empty_subtitle),
                        modifier = Modifier.fillMaxSize(),
                        illustration = { NotesIllustration() },
                        actionLabel = stringResource(R.string.notes_menu_new),
                        onAction = { viewModel.showNewNoteDialog() }
                    )
                } else {
                    EmptyState(
                        icon = Icons.Default.SearchOff,
                        title = stringResource(R.string.notes_nomatch_title),
                        message = stringResource(R.string.notes_nomatch_subtitle),
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = AppSpacing.lg)) {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = AppSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
                ) {
                    items(notes, key = { it.fileName }) { note ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            positionalThreshold = { distance -> distance * 0.75f }
                        )
                        // Haptic when swipe crosses threshold
                        var hapticFired by remember { mutableStateOf(false) }
                        LaunchedEffect(dismissState.progress) {
                            val pastThreshold = dismissState.progress >= 0.75f
                            if (pastThreshold && !hapticFired) {
                                hapticFired = true
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            } else if (!pastThreshold && hapticFired) {
                                hapticFired = false
                            }
                        }
                        LaunchedEffect(dismissState.currentValue) {
                            if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
                                performDeleteWithUndo(note)
                                try { dismissState.snapTo(SwipeToDismissBoxValue.Settled) } catch (_: Exception) {}
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
                            NoteCard(
                                note = note,
                                isSelected = selectedNoteIds.contains(note.fileName),
                                onClick = remember(note.fileName, isSelectionMode) {
                                    {
                                        if (isSelectionMode) {
                                            com.noteflowai.app.ui.theme.Haptics.tap(context)
                                            viewModel.toggleNoteSelection(note.fileName)
                                        } else {
                                            if (searchQuery.isNotBlank()) viewModel.recordSearchHistory(searchQuery)
                                            viewModel.loadNoteContent(note)
                                        }
                                    }
                                },
                                onLongClick = remember(note.fileName, isSelectionMode) {
                                    { showMenu ->
                                        if (isSelectionMode) {
                                            com.noteflowai.app.ui.theme.Haptics.tap(context)
                                            viewModel.toggleNoteSelection(note.fileName)
                                        } else {
                                            showMenu()
                                        }
                                    }
                                },
                                onShare = remember(note.fileName) {
                                    { viewModel.shareNote(context, note) }
                                },
                                onPin = remember(note.fileName) {
                                    { com.noteflowai.app.ui.theme.Haptics.tap(context); viewModel.toggleNotePin(note) }
                                },
                                onDelete = remember(note.fileName) {
                                    { performDeleteWithUndo(note) }
                                },
                                status = viewModel.processingStatusFor(note.fileName),
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        }
                    }
                }
            }
        }
    }

    // Note detail — navigate to dedicated screen when a note is selected
    val selectedNote by viewModel.selectedNote.collectAsStateWithLifecycle()
    var previousNoteSelected by remember { mutableStateOf(selectedNote != null) }

    LaunchedEffect(selectedNote) {
        val isNowSelected = selectedNote != null
        if (isNowSelected && !previousNoteSelected) {
            onNavigate(com.noteflowai.app.ui.Screen.NOTE_DETAIL)
        }
        previousNoteSelected = isNowSelected
    }

    if (showDeleteConfirm && noteToDelete != null) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.notes_delete_dialog_title),
            message = stringResource(R.string.notes_delete_dialog_message, noteToDelete!!.fileName.removeSuffix(".txt").removeSuffix(".json")),
            onConfirm = {
                performDeleteWithUndo(noteToDelete!!)
                showDeleteConfirm = false
                noteToDelete = null
            },
            onDismiss = {
                showDeleteConfirm = false
                noteToDelete = null
            }
        )
    }

    // New Note Dialog
    val showNewNoteDialog by viewModel.showNewNoteDialog.collectAsStateWithLifecycle()
    if (showNewNoteDialog) {
        var newNoteTitle by remember { mutableStateOf("") }
        var newNoteContent by remember { mutableStateOf("") }
        var selectedCategory by remember { mutableStateOf(catWork) }
        var selectedTemplate by remember { mutableIntStateOf(0) }
        var isPinned by remember { mutableStateOf(false) }
        var noteTags by remember { mutableStateOf(emptyList<String>()) }
        var tagInput by remember { mutableStateOf("") }
        val dialogScrollState = rememberScrollState()

        AlertDialog(
            onDismissRequest = { viewModel.dismissNewNoteDialog() },
            shape = DIALOG_SHAPE,
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.notes_new_note_dialog_title),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                    IconButton(onClick = { viewModel.dismissNewNoteDialog() }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.notes_desc_close), modifier = Modifier.size(18.dp))
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(dialogScrollState),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
                ) {
                    // Quick Starter Templates
                    val templates = listOf(
                        stringResource(R.string.notes_new_note_template_blank) to 0,
                        stringResource(R.string.notes_new_note_template_meeting) to 1,
                        stringResource(R.string.notes_new_note_template_tasks) to 2,
                        stringResource(R.string.notes_new_note_template_idea) to 3,
                        stringResource(R.string.notes_new_note_template_journal) to 4
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.notes_new_note_template),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(templates) { (tmplName, tmplIdx) ->
                                val isSelected = selectedTemplate == tmplIdx
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        selectedTemplate = tmplIdx
                                        when (tmplIdx) {
                                            0 -> {}
                                            1 -> {
                                                if (newNoteTitle.isBlank()) newNoteTitle = "Meeting Notes"
                                                newNoteContent = "## Meeting Notes\n\n### Agenda\n- \n\n### Discussion & Decisions\n- \n\n### Action Items\n- [ ] "
                                                selectedCategory = catWork
                                            }
                                            2 -> {
                                                if (newNoteTitle.isBlank()) newNoteTitle = "Action Checklist"
                                                newNoteContent = "## Priorities & Tasks\n\n- [ ] Urgent: \n- [ ] High priority: \n- [ ] Follow-up: "
                                                selectedCategory = catWork
                                            }
                                            3 -> {
                                                if (newNoteTitle.isBlank()) newNoteTitle = "Project Idea"
                                                newNoteContent = "## Concept & Opportunity\n\n### Problem Statement\n\n### Proposed Solution\n\n### Key Benefits\n- \n\n### Next Steps\n- [ ] "
                                                selectedCategory = catIdeas
                                            }
                                            4 -> {
                                                if (newNoteTitle.isBlank()) newNoteTitle = "Daily Reflection"
                                                newNoteContent = "## Daily Reflection\n\n### Wins & Gratitude\n- \n\n### Priorities Today\n- \n\n### Notes & Ideas\n"
                                                selectedCategory = catPersonal
                                            }
                                        }
                                    },
                                    label = { Text(tmplName, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }

                    // Title
                    OutlinedTextField(
                        value = newNoteTitle,
                        onValueChange = { newNoteTitle = it },
                        label = { Text(stringResource(R.string.notes_new_note_title_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(AppRadius.medium)
                    )

                    // Content
                    OutlinedTextField(
                        value = newNoteContent,
                        onValueChange = { newNoteContent = it },
                        label = { Text(stringResource(R.string.notes_new_note_content_label)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 220.dp),
                        shape = RoundedCornerShape(AppRadius.medium)
                    )

                    // Category selection
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.notes_new_note_category_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            categories.filter { it != catAll }.forEach { cat ->
                                val isSelected = selectedCategory == cat
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { selectedCategory = cat },
                                    label = { Text(cat, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }

                    // Tags row
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.notes_new_note_tags_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (noteTags.isNotEmpty()) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                noteTags.forEach { tag ->
                                    InputChip(
                                        selected = false,
                                        onClick = { noteTags = noteTags - tag },
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
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                        ) {
                            OutlinedTextField(
                                value = tagInput,
                                onValueChange = { tagInput = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text(stringResource(R.string.notes_add_tag_placeholder), style = MaterialTheme.typography.bodySmall) },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall,
                                shape = RoundedCornerShape(AppRadius.medium)
                            )
                            IconButton(
                                onClick = {
                                    val trimmed = tagInput.trim()
                                    if (trimmed.isNotBlank() && !noteTags.contains(trimmed)) {
                                        noteTags = noteTags + trimmed
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
                    }

                    // Pin toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isPinned = !isPinned }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.PushPin,
                                contentDescription = null,
                                tint = if (isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = stringResource(R.string.notes_new_note_pin_label),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Switch(
                            checked = isPinned,
                            onCheckedChange = { isPinned = it }
                        )
                    }
                }
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            viewModel.createNewNote(
                                title = newNoteTitle,
                                content = newNoteContent,
                                category = selectedCategory,
                                tags = noteTags,
                                pinned = isPinned,
                                openAfterCreate = false
                            )
                        }
                    ) {
                        Text(stringResource(R.string.notes_new_note_create))
                    }
                    Button(
                        onClick = {
                            viewModel.createNewNote(
                                title = newNoteTitle,
                                content = newNoteContent,
                                category = selectedCategory,
                                tags = noteTags,
                                pinned = isPinned,
                                openAfterCreate = true
                            )
                        }
                    ) {
                        Text(stringResource(R.string.notes_new_note_create_open))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissNewNoteDialog() }) {
                    Text(stringResource(R.string.notes_edit_cancel))
                }
            }
        )
    }

    // Slide Generation Dialogs
    val showSlideGenerateDialog by slideViewModel.showSlideGenerateDialog.collectAsStateWithLifecycle()
    val slideGenerateTitle by slideViewModel.slideGenerateTitle.collectAsStateWithLifecycle()
    val showSlidePreview by slideViewModel.showSlidePreview.collectAsStateWithLifecycle()
    val generatedSlides by slideViewModel.generatedSlides.collectAsStateWithLifecycle()
    val slideIsProcessing by slideViewModel.isProcessing.collectAsStateWithLifecycle()
    val slideProcessingStep by slideViewModel.processingStep.collectAsStateWithLifecycle()
    val isOnlineMode by viewModel.isOnlineMode.collectAsStateWithLifecycle()

    if (showSlideGenerateDialog) {
        SlideGenerateDialog(
            sourceTitle = slideGenerateTitle,
            isOnlineMode = isOnlineMode,
            isProcessing = slideIsProcessing,
            processingStep = slideProcessingStep,
            onDismiss = { slideViewModel.dismissSlideGenerateDialog() },
            onGenerate = { customPrompt, mode -> slideViewModel.generateSlides(customPrompt, mode) }
        )
    }

    if (showSlidePreview && generatedSlides.isNotEmpty()) {
        SlidePreviewDialog(
            slides = generatedSlides,
            title = slideGenerateTitle,
            onDismiss = { slideViewModel.dismissSlidePreview() },
            onShare = { slideViewModel.shareSlidePptx(context) }
        )
    }
}

}

@Composable
fun ActionChip(icon: androidx.compose.ui.graphics.vector.ImageVector?, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.minimumInteractiveComponentSize(),
        color = if (enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun NoteCard(
    note: NoteFile,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: ((showContextMenu: () -> Unit) -> Unit)? = null,
    onShare: () -> Unit,
    onPin: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    status: com.noteflowai.app.data.memory.model.StatusUiModel? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    var showContextMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .let { mod ->
                if (onLongClick != null) mod.then(Modifier.combinedClickable(
                    onClick = onClick,
                    onLongClick = { onLongClick { showContextMenu = true } }
                ))
                else mod.clickable(onClick = onClick)
            },
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = if (isSelected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)) else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(AppRadius.large)
    ) {
        Box {
        DropdownMenu(expanded = showContextMenu, onDismissRequest = { showContextMenu = false }) {
            if (onPin != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (note.pinned) R.string.notes_desc_unpin else R.string.notes_desc_pin)) },
                    leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    onClick = { showContextMenu = false; onPin() }
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.notes_desc_share)) },
                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp)) },
                onClick = { showContextMenu = false; onShare() }
            )
            if (onDelete != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.notes_desc_delete)) },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) },
                    onClick = { showContextMenu = false; onDelete() }
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.lg),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (note.pinned) {
                        Icon(Icons.Default.PushPin, contentDescription = stringResource(R.string.notes_desc_pinned), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(AppSpacing.xs))
                    }
                    Text(
                        text = note.fileName.removeSuffix(".json").removeSuffix(".txt"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, false)
                            .let { mod ->
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
                    if (note.category != stringResource(R.string.notes_category_all)) {
                        Spacer(modifier = Modifier.width(AppSpacing.sm))
                        Text(
                            text = note.category,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                Text(
                    text = note.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = note.lastModified,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    )
                    if (note.tags.isNotEmpty()) {
                        Text(
                            text = "  ·  ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                        Text(
                            text = note.tags.take(3).joinToString("  ·  "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                status?.let {
                    if (it.status != com.noteflowai.app.data.memory.model.UserFacingStatus.READY) {
                        Spacer(modifier = Modifier.height(AppSpacing.xs))
                        ProcessingPill(status = it)
                    }
                }
            }
            Row(verticalAlignment = Alignment.Top) {
                IconButton(onClick = onShare, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Share, contentDescription = stringResource(R.string.notes_desc_share), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), modifier = Modifier.size(20.dp))
                }
            }
        } // end Row
        } // end Box
    } // end Card
}
