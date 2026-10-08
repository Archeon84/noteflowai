package com.noteflowai.app.ui.screens.chat

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import com.noteflowai.app.data.noteDisplayTitle
import com.noteflowai.app.ui.theme.AppRadius
import com.noteflowai.app.ui.theme.AppSpacing
import com.noteflowai.app.ui.theme.AccentTeal
import com.noteflowai.app.ui.theme.AiAccentDark
import com.noteflowai.app.ui.theme.AiAccentLight
import com.noteflowai.app.ui.theme.AiGradientDark
import com.noteflowai.app.ui.theme.AiGradientLight
import com.noteflowai.app.ui.theme.SuccessColor
import com.noteflowai.app.ui.theme.isLightBg
import com.noteflowai.app.ui.theme.WarningColor
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noteflowai.app.data.chat.ChatMessage
import com.noteflowai.app.data.chat.ChatRecallMode
import com.noteflowai.app.ui.screens.ConfirmDeleteDialog
import com.noteflowai.app.ui.components.MarkdownRenderer
import com.noteflowai.app.ui.components.SearchExplanationDialog
import com.noteflowai.app.ui.components.CitationDrawer
import com.noteflowai.app.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import com.noteflowai.app.R
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.noteflowai.app.data.llm.AvailableModels
import com.noteflowai.app.data.llm.ModelId
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit = { fileName -> viewModel.selectNoteByFileName(fileName) },
    onOpenSettings: (() -> Unit)? = null
) {
    val messages by viewModel.chatMessages.collectAsStateWithLifecycle()
    val streaming by viewModel.streamingContent.collectAsStateWithLifecycle()
    val isTyping by viewModel.isAiTyping.collectAsStateWithLifecycle()
    val chatHistory by viewModel.chatHistory.collectAsStateWithLifecycle()
    val currentSession by viewModel.currentChatSession.collectAsStateWithLifecycle()
    val groundedFooters by viewModel.groundedFooters.collectAsStateWithLifecycle()

    val aiProvider by viewModel.aiProvider.collectAsStateWithLifecycle()
    val isLocalOnlyMode by viewModel.isLocalOnlyMode.collectAsStateWithLifecycle()
    val isLocalActive = aiProvider.equals("Local", ignoreCase = true) || isLocalOnlyMode
    val isLocalLoading by viewModel.isLlamaInferenceLoading.collectAsStateWithLifecycle()
    val isLocalReady by viewModel.isLlamaInferenceReady.collectAsStateWithLifecycle()
    val isLlamaDownloading by viewModel.isLlamaInferenceDownloading.collectAsStateWithLifecycle()
    val isLocalBusy = isLocalActive && (isLocalLoading || isLlamaDownloading)

    val aiModelName by viewModel.aiModelName.collectAsStateWithLifecycle()
    val aiWebSearchEnabled by viewModel.aiWebSearchEnabled.collectAsStateWithLifecycle()
    val isThinkingMode by viewModel.isThinkingMode.collectAsStateWithLifecycle()

    val chatWebSearchActive by viewModel.chatWebSearchActive.collectAsStateWithLifecycle()
    val isWebSearching by viewModel.isWebSearching.collectAsStateWithLifecycle()
    val lastRagContextUsed by viewModel.lastRagContextUsed.collectAsStateWithLifecycle()
    val chatRecallMode by viewModel.chatRecallMode.collectAsStateWithLifecycle()

    val isSpeaking by viewModel.isSpeaking.collectAsStateWithLifecycle()
    val pendingToolCall by viewModel.pendingToolCall.collectAsStateWithLifecycle()
    val longTaskStatus by viewModel.longTaskStatus.collectAsStateWithLifecycle()

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val visibleMessages by remember { derivedStateOf { messages.filter { it.role != "system" } } }
    // Pre-compute effective messages outside items{} to avoid recomposing ALL items on each streaming token.
    // Only the last assistant message needs the streaming content injected.
    val effectiveMessages by remember {
        derivedStateOf {
            val msgs = visibleMessages
            val lastIdx = msgs.lastIndex
            if (streaming != null && lastIdx >= 0 && msgs[lastIdx].role == "assistant" && msgs[lastIdx].content.isEmpty()) {
                msgs.toMutableList().also { it[lastIdx] = it[lastIdx].copy(content = streaming!!) }
            } else {
                msgs
            }
        }
    }
    var showConfigDialog by remember { mutableStateOf(false) }
    var showAttachmentMenu by remember { mutableStateOf(false) }
    
    var currentAttachmentUri by remember { mutableStateOf<String?>(null) }
    var currentAttachmentType by remember { mutableStateOf<String?>(null) }

    var showDeleteMsgConfirm by remember { mutableStateOf(false) }
    var msgToDelete by remember { mutableStateOf<ChatMessage?>(null) }
    var showDeleteSessionConfirm by remember { mutableStateOf(false) }
    var sessionToDelete by remember { mutableStateOf<com.noteflowai.app.data.chat.ChatSession?>(null) }

    // Search explanation dialog state
    var showSearchExplanation by remember { mutableStateOf(false) }
    var explanationNoteTitle by remember { mutableStateOf("") }
    var explanationScore by remember { mutableStateOf(0f) }
    var explanationReasons by remember { mutableStateOf<List<String>>(emptyList()) }
    var explanationConcepts by remember { mutableStateOf<List<String>>(emptyList()) }

    // Citation Drawer state
    var showCitationDrawer by remember { mutableStateOf(false) }
    var activeCitationIndex by remember { mutableStateOf(1) }
    var activeCitationFooter by remember { mutableStateOf<com.noteflowai.app.data.chat.GroundedCitationFooter?>(null) }
    var activeCitationRagSource by remember { mutableStateOf<com.noteflowai.app.data.chat.RagSource?>(null) }
    
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            currentAttachmentUri = uri.toString()
            // currentAttachmentType is already set by AttachmentMenu onSelect
        } else {
            currentAttachmentType = null
        }
        showAttachmentMenu = false
    }

    LaunchedEffect(messages.size, isTyping) {
        if (messages.isNotEmpty()) {
            val lastIndex = messages.size - 1
            val isNearBottom = listState.firstVisibleItemIndex >= lastIndex - 2
            if (isNearBottom) {
                listState.animateScrollToItem(lastIndex)
            }
        }
    }

    val dateFormatter = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    stringResource(R.string.chat_history_title),
                    modifier = Modifier.padding(AppSpacing.lg),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                HorizontalDivider()
                
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    label = { Text(stringResource(R.string.chat_new_chat)) },
                    selected = false,
                    onClick = {
                        viewModel.createNewChatSession()
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = AppSpacing.xs)
                )
                
                HorizontalDivider(modifier = Modifier.padding(vertical = AppSpacing.xs))
                
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    chatHistory.forEach { session ->
                        val dateStr = dateFormatter.format(Date(session.createdAt))
                        NavigationDrawerItem(
                            label = {
                                Column {
                                    Text(
                                        session.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        stringResource(R.string.chat_created_prefix, dateStr),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            },
                            selected = session.id == currentSession?.id,
                            onClick = {
                                viewModel.loadChatSession(session)
                                scope.launch { drawerState.close() }
                            },
                            icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
                            badge = {
                                IconButton(onClick = {
                                    sessionToDelete = session
                                    showDeleteSessionConfirm = true
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.chat_desc_delete), modifier = Modifier.size(18.dp))
                                }
                            },
                            modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { 
                        Column {
                            Text(stringResource(R.string.chat_title), style = MaterialTheme.typography.titleLarge)
                            Text(aiModelName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    },
                    navigationIcon = {
                        Row {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.chat_desc_back))
                            }
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.chat_desc_history))
                            }
                        }
                    },
                    actions = {
                        AssistChip(
                            onClick = {
                                if (isLocalActive) {
                                    viewModel.switchToCloudAi { restoredProvider ->
                                        Toast.makeText(context, context.getString(R.string.chat_switched_to_cloud, restoredProvider), Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    viewModel.switchToLocalAi { localModel ->
                                        Toast.makeText(context, context.getString(R.string.chat_switched_to_local, localModel), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            label = {
                                Text(
                                    text = if (isLocalActive) stringResource(R.string.chat_provider_local) else stringResource(R.string.chat_provider_cloud),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (isLocalActive) Icons.Default.Memory else Icons.Default.Cloud,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (isLocalActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                labelColor = if (isLocalActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                        IconButton(onClick = { showConfigDialog = true }) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.chat_desc_config))
                        }
                        if (onOpenSettings != null) {
                            IconButton(onClick = onOpenSettings) {
                                Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.nav_desc_settings))
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(top = padding.calculateTopPadding())
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .imePadding() // Sit directly on keyboard
            ) {
                // Recall Scope Selector: "Talk to notes only" vs "Talk to remote API only"
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AppSpacing.sm, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = chatRecallMode == ChatRecallMode.NOTES_ONLY,
                            onClick = {
                                viewModel.setChatRecallMode(ChatRecallMode.NOTES_ONLY)
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.MenuBook,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            },
                            label = {
                                Text(
                                    stringResource(R.string.chat_recall_mode_notes_only),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )

                        FilterChip(
                            selected = chatRecallMode == ChatRecallMode.REMOTE_API_ONLY,
                            onClick = {
                                viewModel.setChatRecallMode(ChatRecallMode.REMOTE_API_ONLY)
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Language,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            },
                            label = {
                                Text(
                                    stringResource(R.string.chat_recall_mode_remote_only),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    if (messages.isEmpty() && !isTyping) {
                        ChatEmptyHero(
                            onSuggestion = { suggestion ->
                                if (isLocalBusy) {
                                    Toast.makeText(context, context.getString(R.string.chat_local_model_loading_toast), Toast.LENGTH_SHORT).show()
                                    return@ChatEmptyHero
                                }
                                if (isLocalActive && !isLocalReady) {
                                    Toast.makeText(context, context.getString(R.string.chat_local_model_not_ready), Toast.LENGTH_LONG).show()
                                    return@ChatEmptyHero
                                }
                                viewModel.sendChatMessage(suggestion, currentAttachmentUri, currentAttachmentType)
                            },
                            enabled = !isLocalBusy && !isTyping
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(AppSpacing.lg),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
                    ) {
                        items(effectiveMessages, key = { it.id }, contentType = { it.role }) { msg ->
                            val isLast = effectiveMessages.lastOrNull() == msg
                            val onCopy = remember(msg) { { viewModel.copyToClipboard(msg.content) } }
                            val onDelete = remember(msg) { {
                                msgToDelete = msg
                                showDeleteMsgConfirm = true
                            } }
                            val onRegenerate = remember { { viewModel.regenerateLastMessage() } }
                            val onRetry = remember { { viewModel.regenerateLastMessage() } }
                            val footerExcerpts = msg.groundedAnswerId?.let { groundedFooters[it] }.orEmpty()
                            val hasCitations = !msg.ragSources.isNullOrEmpty() ||
                                !msg.groundedResponse?.citations.isNullOrEmpty() ||
                                footerExcerpts.isNotEmpty()
                            ChatBubble(
                                message = msg,
                                onCopy = onCopy,
                                onDelete = onDelete,
                                onRegenerate = onRegenerate,
                                onRetry = onRetry,
                                isLast = isLast,
                                onNoteClick = onOpenNote,
                                onCitationClick = if (hasCitations) { markerIndex ->
                                    val citation = msg.groundedResponse?.citations?.getOrNull(markerIndex - 1)
                                    val footer = if (citation != null) {
                                        footerExcerpts.firstOrNull { it.chunkId == citation.chunkId }
                                            ?: footerExcerpts.getOrNull(markerIndex - 1)
                                    } else {
                                        footerExcerpts.find { it.citationIndex == markerIndex }
                                            ?: footerExcerpts.firstOrNull { it.chunkId == msg.ragSources?.getOrNull(markerIndex - 1)?.noteFileName }
                                            ?: footerExcerpts.getOrNull(markerIndex - 1)
                                    }
                                    val ragSource = when {
                                        citation != null -> msg.ragSources?.firstOrNull { it.noteFileName == citation.sourceId }
                                            ?: msg.ragSources?.getOrNull(markerIndex - 1)
                                        footer != null -> msg.ragSources?.firstOrNull { it.noteFileName == footer.chunkId || it.noteFileName == footer.sourceId }
                                            ?: msg.ragSources?.getOrNull(markerIndex - 1)
                                        else -> msg.ragSources?.getOrNull(markerIndex - 1)
                                    }
                                    activeCitationIndex = markerIndex
                                    activeCitationFooter = footer
                                    activeCitationRagSource = ragSource
                                    showCitationDrawer = true
                                } else null,
                                footerExcerpts = footerExcerpts,
                                showSearchExplanation = showSearchExplanation,
                                explanationNoteTitle = explanationNoteTitle,
                                explanationScore = explanationScore,
                                explanationReasons = explanationReasons,
                                explanationConcepts = explanationConcepts,
                                onShowSearchExplanation = { title, score, reasons, concepts ->
                                    explanationNoteTitle = title
                                    explanationScore = score
                                    explanationReasons = reasons
                                    explanationConcepts = concepts
                                    showSearchExplanation = true
                                },
                                onSpeak = { text ->
                                    if (!viewModel.speakAiResponse(text)) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(context.getString(R.string.chat_tts_disabled_hint))
                                        }
                                    }
                                },
                                onStopSpeaking = { viewModel.stopSpeaking() },
                                isSpeaking = isSpeaking,
                                onConfirmToolCall = { viewModel.confirmToolCall() },
                                onRejectToolCall = { viewModel.rejectToolCall() },
                                pendingToolCallId = pendingToolCall?.toolCallId,
                                onSuggestedAction = { action -> viewModel.handleSuggestedAction(action) }
                            )
                        }
                        if (isTyping) {
                            item {
                                ChatLoadingBubble()
                            }
                        }
                        // Long-task status (offline long-note reading progress).
                        if (longTaskStatus != null) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = AppSpacing.lg, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(AppSpacing.xs))
                                    Text(
                                        text = longTaskStatus!!,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                        // RAG context indicator
                        if (lastRagContextUsed && !isTyping) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = AppSpacing.lg, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.MenuBook,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                    )
                                    Spacer(modifier = Modifier.width(AppSpacing.xs))
                                    Text(
                                        text = stringResource(R.string.chat_rag_context_indicator),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }
                    val showJumpButton by remember {
                        derivedStateOf {
                            messages.isNotEmpty() && listState.firstVisibleItemIndex < messages.size - 3
                        }
                    }
                    if (showJumpButton) {
                        Box(modifier = Modifier.align(Alignment.BottomEnd).padding(AppSpacing.sm)) {
                            FloatingActionButton(
                                onClick = {
                                    scope.launch { listState.animateScrollToItem(messages.size - 1) }
                                },
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    Icons.Filled.ArrowDownward,
                                    contentDescription = stringResource(R.string.chat_desc_jump_latest),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }

                // Input Area container
                Column {
                    // Attachment Preview
                    if (currentAttachmentUri != null) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.sm),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(topStart = AppRadius.large, topEnd = AppRadius.large)
                        ) {
                            Row(
                                modifier = Modifier.padding(AppSpacing.sm),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(AppSpacing.sm))
                                Text(
                                        text = stringResource(R.string.chat_attached_prefix, currentAttachmentUri!!.substringAfterLast("/")),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { currentAttachmentUri = null; currentAttachmentType = null }, modifier = Modifier.size(48.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.chat_desc_remove), modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }

                    // Attachment Menu
                    if (showAttachmentMenu) {
                        AttachmentMenu(
                            onSelect = { type ->
                                currentAttachmentType = type
                                when(type) {
                                    "image" -> filePicker.launch("image/*")
                                    "audio" -> filePicker.launch("audio/*")
                                    "video" -> filePicker.launch("video/*")
                                    "file" -> filePicker.launch("*/*")
                                }
                                showAttachmentMenu = false
                            }
                        )
                    }

                    // Input Row
                    Surface(
                        tonalElevation = 3.dp,
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Column {
                            AnimatedVisibility(visible = isLocalBusy) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = AppSpacing.md, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = stringResource(R.string.chat_local_model_loading),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.sm),
                                verticalAlignment = Alignment.Bottom
                            ) {
                                IconButton(onClick = { showAttachmentMenu = !showAttachmentMenu }, modifier = Modifier.padding(bottom = AppSpacing.xs)) {
                                    Icon(
                                        imageVector = if (showAttachmentMenu) Icons.Default.Close else Icons.Default.Add,
                                        contentDescription = stringResource(R.string.chat_desc_attach),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                OutlinedTextField(
                                    value = inputText,
                                    onValueChange = { inputText = it },
                                    modifier = Modifier.weight(1f),
                                    placeholder = { 
                                        Text(
                                            when {
                                                currentAttachmentUri != null -> stringResource(R.string.chat_placeholder_image)
                                                chatRecallMode == ChatRecallMode.REMOTE_API_ONLY -> stringResource(R.string.chat_placeholder_remote_only)
                                                else -> stringResource(R.string.chat_placeholder_notes_only)
                                            }, 
                                            style = MaterialTheme.typography.bodyMedium 
                                        ) 
                                    },
                                    maxLines = 4,
                                    shape = RoundedCornerShape(AppRadius.medium),
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                        unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                                    )
                                )
                                Spacer(modifier = Modifier.width(AppSpacing.xs))
                                if (aiWebSearchEnabled) {
                                    IconToggleButton(
                                        checked = chatWebSearchActive,
                                        onCheckedChange = { viewModel.toggleChatWebSearch() },
                                        modifier = Modifier.padding(bottom = AppSpacing.xs),
                                        enabled = !isWebSearching
                                    ) {
                                        Icon(
                                            Icons.Filled.Public,
                                            contentDescription = stringResource(R.string.chat_web_search_label),
                                            tint = if (chatWebSearchActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                    }
                                }
                                IconToggleButton(
                                    checked = isThinkingMode,
                                    onCheckedChange = { viewModel.toggleThinkingMode(it) },
                                    modifier = Modifier.padding(bottom = AppSpacing.xs)
                                ) {
                                    Icon(
                                        Icons.Filled.Psychology,
                                        contentDescription = stringResource(R.string.chat_think_label),
                                        tint = if (isThinkingMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                                if (isTyping) {
                                    IconButton(
                                        onClick = { viewModel.stopGeneration() },
                                        modifier = Modifier.padding(bottom = AppSpacing.xs)
                                    ) {
                                        Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.chat_desc_stop),
                                            tint = MaterialTheme.colorScheme.error)
                                    }
                                } else {
                                    IconButton(
                                        onClick = {
                                            if (isLocalBusy) {
                                                Toast.makeText(context, context.getString(R.string.chat_local_model_loading_toast), Toast.LENGTH_SHORT).show()
                                                return@IconButton
                                            }
                                            if (isLocalActive && !isLocalReady) {
                                                Toast.makeText(context, context.getString(R.string.chat_local_model_not_ready), Toast.LENGTH_LONG).show()
                                                return@IconButton
                                            }
                                            if (inputText.isNotBlank() || currentAttachmentUri != null) {
                                                com.noteflowai.app.ui.theme.Haptics.confirm(context)
                                                viewModel.sendChatMessage(inputText, currentAttachmentUri, currentAttachmentType)
                                                inputText = ""
                                                currentAttachmentUri = null
                                                currentAttachmentType = null
                                            }
                                        },
                                        enabled = (inputText.isNotBlank() || currentAttachmentUri != null) && !isLocalBusy,
                                        modifier = Modifier.padding(bottom = AppSpacing.xs)
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.Send,
                                            contentDescription = stringResource(R.string.chat_desc_send),
                                            tint = if ((inputText.isNotBlank() || currentAttachmentUri != null) && !isLocalBusy)
                                                MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showConfigDialog) {
        val aiProvider by viewModel.aiProvider.collectAsStateWithLifecycle()
        val aiApiKey by viewModel.aiApiKey.collectAsStateWithLifecycle()
        val aiBaseUrl by viewModel.aiBaseUrl.collectAsStateWithLifecycle()
        val aiSystemPrompt by viewModel.aiSystemPrompt.collectAsStateWithLifecycle()
        val temperature by viewModel.aiTemperature.collectAsStateWithLifecycle()
        val presencePenalty by viewModel.aiPresencePenalty.collectAsStateWithLifecycle()
        val topP by viewModel.aiTopP.collectAsStateWithLifecycle()
        val aiContextTokens by viewModel.aiContextTokens.collectAsStateWithLifecycle()
        val aiKvCache by viewModel.aiKvCache.collectAsStateWithLifecycle()
        val aiSearchApiUrl by viewModel.aiSearchApiUrl.collectAsStateWithLifecycle()
        val aiSearchApiKey by viewModel.aiSearchApiKey.collectAsStateWithLifecycle()
        val aiMaxRequestsPerMin by viewModel.aiMaxRequestsPerMin.collectAsStateWithLifecycle()
        AiConfigDialog(
            viewModel = viewModel,
            initialProvider = aiProvider,
            initialApiKey = aiApiKey,
            initialBaseUrl = aiBaseUrl,
            initialModel = aiModelName,
            initialPrompt = aiSystemPrompt,
            initialTemp = temperature,
            initialPenalty = presencePenalty,
            initialTopP = topP,
            initialContextTokens = aiContextTokens,
            initialKvCache = aiKvCache,
            initialWebSearchEnabled = aiWebSearchEnabled,
            initialSearchApiUrl = aiSearchApiUrl,
            initialSearchApiKey = aiSearchApiKey,
            initialMaxRequestsPerMin = aiMaxRequestsPerMin,
            onDismiss = { showConfigDialog = false }
        )
    }

    if (showDeleteMsgConfirm && msgToDelete != null) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.chat_delete_msg_title),
            message = stringResource(R.string.chat_delete_msg_message),
            onConfirm = {
                viewModel.deleteChatMessage(msgToDelete!!)
                showDeleteMsgConfirm = false
                msgToDelete = null
            },
            onDismiss = {
                showDeleteMsgConfirm = false
                msgToDelete = null
            }
        )
    }

    if (showDeleteSessionConfirm && sessionToDelete != null) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.chat_delete_session_title),
            message = stringResource(R.string.chat_delete_session_message),
            onConfirm = {
                viewModel.deleteChatSession(sessionToDelete!!)
                showDeleteSessionConfirm = false
                sessionToDelete = null
            },
            onDismiss = {
                showDeleteSessionConfirm = false
                sessionToDelete = null
            }
        )
    }

    // Search explanation dialog
    if (showSearchExplanation) {
        SearchExplanationDialog(
            explanation = com.noteflowai.app.data.search.SearchExplainer.SearchExplanation(
                noteTitle = explanationNoteTitle,
                overallScore = explanationScore,
                reasons = explanationReasons.map { com.noteflowai.app.data.search.SearchExplainer.RetrievalReason(
                    type = "attribute",
                    detail = it,
                    weight = 0f
                ) },
                concepts = explanationConcepts
            ),
            onDismiss = { showSearchExplanation = false }
        )
    }

    // Interactive Citation Verification Drawer
    if (showCitationDrawer) {
        CitationDrawer(
            citationIndex = activeCitationIndex,
            footer = activeCitationFooter,
            ragSource = activeCitationRagSource,
            onOpenSourceNote = { sourceFileName: String, quote: String? ->
                if (sourceFileName.startsWith("http://") || sourceFileName.startsWith("https://")) {
                    try {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(sourceFileName)).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        android.util.Log.w("ChatScreen", "Failed to open web URL: $sourceFileName", e)
                    }
                } else {
                    if (!quote.isNullOrBlank()) {
                        viewModel.setHighlightTargetText(quote)
                    }
                    onOpenNote(sourceFileName)
                }
            },
            onDismiss = { showCitationDrawer = false }
        )
    }
}

@Composable
fun AttachmentMenu(onSelect: (String) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(AppSpacing.lg),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            AttachmentItem(Icons.Default.Image, stringResource(R.string.chat_attachment_image)) { onSelect("image") }
            AttachmentItem(Icons.Default.AudioFile, stringResource(R.string.chat_attachment_audio)) { onSelect("audio") }
            AttachmentItem(Icons.Default.VideoFile, stringResource(R.string.chat_attachment_video)) { onSelect("video") }
            AttachmentItem(Icons.Default.Description, stringResource(R.string.chat_attachment_file)) { onSelect("file") }
        }
    }
}

@Composable
fun AttachmentItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm)
            .minimumInteractiveComponentSize()
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun AiConfigDialog(
    viewModel: MainViewModel,
    initialProvider: String,
    initialApiKey: String,
    initialBaseUrl: String,
    initialModel: String,
    initialPrompt: String,
    initialTemp: Float,
    initialPenalty: Float,
    initialTopP: Float,
    initialContextTokens: Int,
    initialKvCache: Boolean,
    initialWebSearchEnabled: Boolean = false,
    initialSearchApiUrl: String = "",
    initialSearchApiKey: String = "",
    initialMaxRequestsPerMin: Int = 0,
    onDismiss: () -> Unit
) {
    val isInitiallyLocal = initialProvider.equals("Local", ignoreCase = true) || initialProvider.contains("Local", ignoreCase = true)
    var selectedTab by remember { mutableIntStateOf(if (isInitiallyLocal) 0 else 1) }

    var tempProvider by remember { mutableStateOf(initialProvider) }
    var tempApiKey by remember { mutableStateOf(initialApiKey) }
    var tempBaseUrl by remember { mutableStateOf(initialBaseUrl) }
    var tempModel by remember { mutableStateOf(initialModel) }
    var tempPrompt by remember { mutableStateOf(initialPrompt) }
    var tempTemp by remember { mutableFloatStateOf(initialTemp) }
    var tempPenalty by remember { mutableFloatStateOf(initialPenalty) }
    var tempTopP by remember { mutableFloatStateOf(initialTopP) }
    var tempContextTokens by remember { mutableIntStateOf(initialContextTokens) }
    var tempKvCache by remember { mutableStateOf(initialKvCache) }
    var tempWebSearchEnabled by remember { mutableStateOf(initialWebSearchEnabled) }
    var tempSearchApiUrl by remember { mutableStateOf(initialSearchApiUrl) }
    var tempSearchApiKey by remember { mutableStateOf(initialSearchApiKey) }
    var tempMaxRequestsPerMin by remember { mutableIntStateOf(initialMaxRequestsPerMin) }

    var showApiKey by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var testingConnection by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var availableModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }

    val presets by viewModel.aiPresets.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Local LLM observation
    val downloadedModels by viewModel.downloadedLocalModels.collectAsStateWithLifecycle()
    val activeModelId by viewModel.activeLocalLlmModelId.collectAsStateWithLifecycle(initialValue = "gemma4_e2b")
    val isLlamaDownloading by viewModel.isLlamaInferenceDownloading.collectAsStateWithLifecycle()
    val llamaProgress by viewModel.llamaInferenceDownloadProgress.collectAsStateWithLifecycle()
    val downloadingModelId by viewModel.downloadingLocalModelId.collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(AppRadius.large),
        title = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.config_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (presets.isNotEmpty()) {
                        var presetMenuExpanded by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { presetMenuExpanded = true }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.BookmarkBorder, contentDescription = "Presets", tint = MaterialTheme.colorScheme.primary)
                            }
                            DropdownMenu(expanded = presetMenuExpanded, onDismissRequest = { presetMenuExpanded = false }) {
                                Text(
                                    stringResource(R.string.config_saved_presets),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                                HorizontalDivider()
                                presets.forEach { p ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(p.name, fontWeight = FontWeight.SemiBold)
                                                Text("${p.provider} · ${p.modelName}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                            }
                                        },
                                        trailingIcon = {
                                            IconButton(onClick = { viewModel.deleteAiPreset(p.id) }, modifier = Modifier.size(28.dp)) {
                                                Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                            }
                                        },
                                        onClick = {
                                            tempProvider = p.provider
                                            tempApiKey = p.apiKey ?: ""
                                            tempBaseUrl = p.baseUrl
                                            tempModel = p.modelName
                                            tempPrompt = p.systemPrompt
                                            tempTemp = p.temperature
                                            tempPenalty = p.presencePenalty
                                            tempTopP = p.topP ?: 1.0f
                                            tempContextTokens = p.contextTokens ?: 2048
                                            tempKvCache = p.useKvCache ?: true
                                            selectedTab = if (p.provider == "Local" || p.provider == "Local (On-Device)") 0 else 1
                                            presetMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    contentColor = MaterialTheme.colorScheme.primary,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = {
                            selectedTab = 0
                            tempProvider = "Local"
                            tempBaseUrl = "local"
                            val activeModel = AvailableModels.ALL.firstOrNull { it.id.id == activeModelId } ?: AvailableModels.GEMMA_4_E2B
                            tempModel = activeModel.name
                        },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.config_tab_on_device), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = {
                            selectedTab = 1
                            if (tempProvider == "Local" || tempProvider == "Local (On-Device)") {
                                tempProvider = "Gemini"
                                tempBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/"
                                tempModel = "gemini-1.5-flash"
                            }
                        },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.config_tab_cloud), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth()
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                // Tab 0: On-Device (Private)
                if (selectedTab == 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.config_privacy_banner),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Local Model Cards
                    AvailableModels.ALL.forEach { modelInfo ->
                        val isDownloaded = downloadedModels.contains(modelInfo.id.id)
                        val isActive = (activeModelId == modelInfo.id.id) && (tempModel == modelInfo.name || tempModel.contains(modelInfo.name))
                        val isCurrentlyDownloading = isLlamaDownloading && downloadingModelId == modelInfo.id.id

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            border = if (isActive) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable(enabled = isDownloaded) {
                                    viewModel.setActiveLocalLlmModel(modelInfo.id)
                                    tempModel = modelInfo.name
                                    tempProvider = "Local"
                                    tempBaseUrl = "local"
                                }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = modelInfo.displayName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            if (isActive) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    color = MaterialTheme.colorScheme.primary,
                                                    shape = RoundedCornerShape(6.dp)
                                                ) {
                                                    Text(
                                                        text = stringResource(R.string.settings_badge_active),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onPrimary,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                        maxLines = 1,
                                                        softWrap = false
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = stringResource(R.string.settings_model_size_format, modelInfo.approximateSizeMb / 1000.0),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                    }

                                    if (isDownloaded) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (!isActive) {
                                                FilledTonalButton(
                                                    onClick = {
                                                        viewModel.setActiveLocalLlmModel(modelInfo.id)
                                                        tempModel = modelInfo.name
                                                        tempProvider = "Local"
                                                        tempBaseUrl = "local"
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                    modifier = Modifier.height(32.dp)
                                                ) {
                                                    Text(stringResource(R.string.settings_use_model), style = MaterialTheme.typography.labelSmall)
                                                }
                                                Spacer(modifier = Modifier.width(4.dp))
                                            }
                                            IconButton(
                                                onClick = { viewModel.deleteLlamaInferenceModel(modelInfo.id) },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.DeleteOutline,
                                                    contentDescription = "Delete",
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    } else if (isCurrentlyDownloading) {
                                        Text(
                                            "${(llamaProgress * 100).toInt()}%",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    } else {
                                        Button(
                                            onClick = { viewModel.downloadLlamaInferenceModel(modelInfo.id) },
                                            enabled = !isLlamaDownloading,
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(stringResource(R.string.settings_button_download_model), style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }

                                if (isCurrentlyDownloading) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { llamaProgress },
                                        modifier = Modifier.fillMaxWidth().height(4.dp)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Tab 1: Cloud AI
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CloudQueue,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.config_cloud_banner),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Provider Chips
                    val cloudProviders = remember { listOf("Gemini", "Claude", "OpenAI", "DeepSeek", "Ollama", "Custom") }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        cloudProviders.forEach { p ->
                            FilterChip(
                                selected = tempProvider == p,
                                onClick = {
                                    tempProvider = p
                                    when (p) {
                                        "Gemini" -> {
                                            tempBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/"
                                            tempModel = "gemini-1.5-flash"
                                        }
                                        "Claude" -> {
                                            tempBaseUrl = "https://api.anthropic.com/v1/"
                                            tempModel = "claude-3-5-sonnet-20241022"
                                        }
                                        "OpenAI" -> {
                                            tempBaseUrl = "https://api.openai.com/v1/"
                                            tempModel = "gpt-4o-mini"
                                        }
                                        "DeepSeek" -> {
                                            tempBaseUrl = "https://api.deepseek.com/v1/"
                                            tempModel = "deepseek-chat"
                                        }
                                        "Ollama" -> {
                                            tempBaseUrl = "http://10.0.2.2:11434/"
                                            tempModel = "llama3.2"
                                        }
                                    }
                                },
                                label = { Text(p, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // API Key Field (if not Ollama)
                    if (tempProvider != "Ollama") {
                        OutlinedTextField(
                            value = tempApiKey,
                            onValueChange = { tempApiKey = it },
                            label = { Text(stringResource(R.string.config_api_key_label)) },
                            visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showApiKey = !showApiKey }) {
                                    Icon(
                                        if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Model Name & Test Connection
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = tempModel,
                            onValueChange = { tempModel = it },
                            label = { Text(stringResource(R.string.config_model_name_label)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    testingConnection = true
                                    testResult = context.getString(R.string.config_status_connecting)
                                    try {
                                        val models = viewModel.testAiConnection(tempBaseUrl, tempProvider, tempApiKey)
                                        availableModels = models
                                        testResult = context.getString(R.string.config_status_success)
                                    } catch (e: Exception) {
                                        testResult = context.getString(R.string.config_status_error)
                                    } finally {
                                        testingConnection = false
                                    }
                                }
                            },
                            enabled = !testingConnection,
                            modifier = Modifier.height(54.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) {
                            if (testingConnection) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.config_desc_test), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    if (testResult != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = testResult!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (testResult == stringResource(R.string.config_status_success)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }

                    if (availableModels.isNotEmpty()) {
                        var mExpanded by remember { mutableStateOf(false) }
                        Box(modifier = Modifier.padding(top = 4.dp)) {
                            TextButton(onClick = { mExpanded = true }) {
                                Text(stringResource(R.string.config_detected_models), style = MaterialTheme.typography.labelSmall)
                            }
                            DropdownMenu(expanded = mExpanded, onDismissRequest = { mExpanded = false }) {
                                availableModels.forEach { m ->
                                    DropdownMenuItem(text = { Text(m, style = MaterialTheme.typography.bodyMedium) }, onClick = {
                                        tempModel = m
                                        mExpanded = false
                                    })
                                }
                            }
                        }
                    }

                    // Base URL (shown for Custom, Ollama, or LM Studio)
                    if (tempProvider == "Custom" || tempProvider == "Ollama" || tempProvider == "LM Studio") {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = tempBaseUrl,
                            onValueChange = { tempBaseUrl = it },
                            label = { Text(stringResource(R.string.config_base_url_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(12.dp))

                // Response Style (Visual & Intuitive)
                Text(
                    text = stringResource(R.string.config_response_style),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))

                val isPrecise = tempTemp <= 0.35f
                val isBalanced = tempTemp > 0.35f && tempTemp <= 0.95f
                val isCreative = tempTemp > 0.95f

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        Triple(stringResource(R.string.config_style_precise), stringResource(R.string.config_style_precise_desc), isPrecise),
                        Triple(stringResource(R.string.config_style_balanced), stringResource(R.string.config_style_balanced_desc), isBalanced),
                        Triple(stringResource(R.string.config_style_creative), stringResource(R.string.config_style_creative_desc), isCreative)
                    ).forEachIndexed { idx, (title, desc, selected) ->
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    when (idx) {
                                        0 -> { tempTemp = 0.2f; tempTopP = 0.8f }
                                        1 -> { tempTemp = 0.7f; tempTopP = 0.95f }
                                        2 -> { tempTemp = 1.2f; tempTopP = 1.0f }
                                    }
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = desc,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Collapsible Advanced Tuning Accordion
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showAdvanced = !showAdvanced },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Tune,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        stringResource(R.string.config_advanced_accordion),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        stringResource(R.string.config_advanced_accordion_desc),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }
                            Icon(
                                if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        AnimatedVisibility(visible = showAdvanced) {
                            Column(modifier = Modifier.padding(top = 10.dp)) {
                                OutlinedTextField(
                                    value = tempPrompt,
                                    onValueChange = { tempPrompt = it },
                                    label = { Text(stringResource(R.string.config_system_prompt_label)) },
                                    placeholder = { Text("Custom system instructions...") },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 2,
                                    maxLines = 4,
                                    textStyle = MaterialTheme.typography.bodySmall
                                )

                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    stringResource(R.string.config_temperature_label, String.format(Locale.US, "%.2f", tempTemp)),
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Slider(value = tempTemp, onValueChange = { tempTemp = it }, valueRange = 0f..2f, steps = 40)

                                Text(
                                    stringResource(R.string.config_top_p_label, String.format(Locale.US, "%.2f", tempTopP)),
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Slider(value = tempTopP, onValueChange = { tempTopP = it }, valueRange = 0.1f..1.0f, steps = 9)

                                Text(
                                    stringResource(R.string.config_presence_penalty_label, String.format(Locale.US, "%.1f", tempPenalty)),
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Slider(value = tempPenalty, onValueChange = { tempPenalty = it }, valueRange = -2f..2f, steps = 40)

                                Text(
                                    stringResource(R.string.config_context_tokens_label, tempContextTokens),
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Slider(
                                    value = tempContextTokens.toFloat(),
                                    onValueChange = { tempContextTokens = it.toInt() },
                                    valueRange = 512f..32768f,
                                    steps = 62
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(stringResource(R.string.config_kv_cache_label), style = MaterialTheme.typography.labelMedium)
                                        Text(stringResource(R.string.config_kv_cache_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                    }
                                    Switch(checked = tempKvCache, onCheckedChange = { tempKvCache = it })
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(stringResource(R.string.config_web_search_enable_label), style = MaterialTheme.typography.labelMedium)
                                        Text(stringResource(R.string.config_web_search_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                    }
                                    Switch(checked = tempWebSearchEnabled, onCheckedChange = { tempWebSearchEnabled = it })
                                }

                                if (tempWebSearchEnabled) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    OutlinedTextField(
                                        value = tempSearchApiUrl,
                                        onValueChange = { tempSearchApiUrl = it },
                                        label = { Text(stringResource(R.string.config_search_api_url_label)) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        textStyle = MaterialTheme.typography.bodySmall
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    OutlinedTextField(
                                        value = tempSearchApiKey,
                                        onValueChange = { tempSearchApiKey = it },
                                        label = { Text(stringResource(R.string.config_search_api_key_label)) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        textStyle = MaterialTheme.typography.bodySmall,
                                        visualTransformation = PasswordVisualTransformation()
                                    )
                                }

                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    stringResource(R.string.config_max_requests_label, if (tempMaxRequestsPerMin <= 0) stringResource(R.string.config_unlimited) else tempMaxRequestsPerMin.toString()),
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Slider(
                                    value = tempMaxRequestsPerMin.toFloat(),
                                    onValueChange = { tempMaxRequestsPerMin = it.toInt() },
                                    valueRange = 0f..60f,
                                    steps = 60
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(
                        onClick = { showSavePresetDialog = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(stringResource(R.string.config_save_preset_button), style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(
                        onClick = { showClearConfirm = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(stringResource(R.string.config_clear_button), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.config_cancel_button), style = MaterialTheme.typography.labelMedium)
                    }
                    Button(
                        onClick = {
                            viewModel.updateAiConfig(
                                tempProvider, tempApiKey, tempBaseUrl, tempModel, tempPrompt,
                                tempTemp, tempPenalty, tempTopP, tempContextTokens, tempKvCache
                            )
                            viewModel.saveWebSearchConfig(tempWebSearchEnabled, tempSearchApiUrl, tempSearchApiKey)
                            viewModel.saveMaxRequestsPerMin(tempMaxRequestsPerMin)
                            onDismiss()
                        }
                    ) {
                        Text(stringResource(R.string.config_save_button), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        },
        dismissButton = null
    )

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            shape = RoundedCornerShape(16.dp),
            title = { Text(stringResource(R.string.config_clear_title)) },
            text = { Text(stringResource(R.string.config_clear_message)) },
            confirmButton = {
                TextButton(onClick = {
                    tempProvider = "Local"
                    tempApiKey = ""
                    tempBaseUrl = "local"
                    val activeModel = AvailableModels.ALL.firstOrNull { it.id.id == activeModelId } ?: AvailableModels.GEMMA_4_E2B
                    tempModel = activeModel.name
                    tempPrompt = ""
                    tempTemp = 0.7f
                    tempPenalty = 0.0f
                    tempTopP = 0.95f
                    tempContextTokens = 4096
                    tempKvCache = true
                    selectedTab = 0
                    showClearConfirm = false
                }) { Text(stringResource(R.string.config_clear_button), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text(stringResource(R.string.config_cancel_button)) }
            }
        )
    }

    if (showSavePresetDialog) {
        AlertDialog(
            onDismissRequest = { showSavePresetDialog = false },
            shape = RoundedCornerShape(16.dp),
            title = { Text(stringResource(R.string.config_save_preset_title)) },
            text = {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = { Text(stringResource(R.string.config_preset_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (presetName.isNotBlank()) {
                        viewModel.saveAiPreset(
                            name = presetName,
                            provider = tempProvider,
                            apiKey = tempApiKey,
                            baseUrl = tempBaseUrl,
                            modelName = tempModel,
                            systemPrompt = tempPrompt,
                            temperature = tempTemp,
                            presencePenalty = tempPenalty,
                            topP = tempTopP,
                            contextTokens = tempContextTokens,
                            useKvCache = tempKvCache
                        )
                        presetName = ""
                        showSavePresetDialog = false
                    }
                }) { Text(stringResource(R.string.config_save_button)) }
            },
            dismissButton = {
                TextButton(onClick = { showSavePresetDialog = false }) { Text(stringResource(R.string.config_cancel_button)) }
            }
        )
    }
}

@Composable
fun ChatBubble(
    message: ChatMessage,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onRegenerate: () -> Unit,
    isLast: Boolean,
    onRetry: () -> Unit = {},
    onNoteClick: (String) -> Unit = {},
    onCitationClick: ((Int) -> Unit)? = null,
    footerExcerpts: List<com.noteflowai.app.data.chat.GroundedCitationFooter> = emptyList(),
    showSearchExplanation: Boolean = false,
    explanationNoteTitle: String = "",
    explanationScore: Float = 0f,
    explanationReasons: List<String> = emptyList(),
    explanationConcepts: List<String> = emptyList(),
    onShowSearchExplanation: (String, Float, List<String>, List<String>) -> Unit = { _, _, _, _ -> },
    onSpeak: (String) -> Unit = {},
    onStopSpeaking: () -> Unit = {},
    isSpeaking: Boolean = false,
    onConfirmToolCall: () -> Unit = {},
    onRejectToolCall: () -> Unit = {},
    pendingToolCallId: String? = null,
    onSuggestedAction: (com.noteflowai.app.data.chat.SuggestedAction) -> Unit = {}
) {
    val isUser = message.role == "user"
    if (message.role == "system") return

    // Tool call confirmation bubble
    if (!isUser && message.toolCalls?.isNotEmpty() == true) {
        val createNoteCall = message.toolCalls.find { it.function.name == "create_note" }
        if (createNoteCall != null) {
            val args = com.google.gson.Gson().fromJson(
                createNoteCall.function.arguments, com.google.gson.JsonObject::class.java
            )
            val title = args?.get("title")?.asString ?: "Note"
            val category = args?.get("category")?.asString ?: "All"
            val contentPreview = args?.get("content")?.asString?.take(200) ?: ""
            val isPending = pendingToolCallId == createNoteCall.id

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                Card(
                    shape = RoundedCornerShape(AppRadius.large),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    modifier = Modifier.widthIn(max = 400.dp)
                ) {
                    Column(modifier = Modifier.padding(AppSpacing.md)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.NoteAdd,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(AppSpacing.sm))
                            Text(
                                text = stringResource(R.string.chat_create_note),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.height(AppSpacing.sm))
                        Text(
                            text = stringResource(R.string.chat_note_title_label, title),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.chat_note_category_label, category),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (contentPreview.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(AppSpacing.xs))
                            Text(
                                text = contentPreview + if (args?.get("content")?.asString?.length ?: 0 > 200) "..." else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (isPending) {
                            Spacer(modifier = Modifier.height(AppSpacing.sm))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = onRejectToolCall) {
                                    Text(stringResource(R.string.common_cancel))
                                }
                                Spacer(modifier = Modifier.width(AppSpacing.sm))
                                Button(onClick = onConfirmToolCall) {
                                    Text(stringResource(R.string.chat_create))
                                }
                            }
                        }
                    }
                }
            }
            return
        }
    }

    val content = message.content
    val parsed = remember(content) {
        if (content.contains("</think>")) {
            val t = content.substringAfter("<think>").substringBefore("</think>").trim()
            val m = content.substringAfter("</think>").trim()
            Pair(if (t.isNotBlank()) t else null, m)
        } else if (content.startsWith("<think>")) {
            Pair(content.removePrefix("<think>").trim(), "")
        } else {
            Pair(null, content)
        }
    }
    val thinking = parsed.first
    val mainContent = parsed.second
    val isThinkingInProgress = thinking != null && mainContent.isEmpty()

    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val timeStr = timeFormatter.format(Date(message.timestamp))

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
            // Attachment Preview
            if (message.attachmentUri != null) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(AppRadius.medium),
                    modifier = Modifier.padding(bottom = AppSpacing.xs).widthIn(max = 200.dp)
                ) {
                    Row(modifier = Modifier.padding(AppSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        val icon = when(message.attachmentType) {
                            "image" -> Icons.Default.Image
                            "audio" -> Icons.Default.AudioFile
                            "video" -> Icons.Default.VideoFile
                            else -> Icons.Default.Description
                        }
                        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(AppSpacing.sm))
                        Text(
                            text = message.attachmentUri.substringAfterLast("/"),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (isUser) {
                // User: right-aligned message bubble matching assistant radius
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(AppRadius.large),
                    modifier = Modifier.widthIn(max = 360.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md)) {
                        val displayText = when {
                            mainContent.isNotEmpty() -> mainContent
                            else -> content
                        }
                        if (displayText.isNotEmpty()) {
                            Text(
                                text = displayText,
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            } else {
                // AI: full-width 28dp answer with left gradient accent bar (evidence style)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(AppRadius.large),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(IntrinsicSize.Min)
                                .background(
                                    Brush.verticalGradient(listOf(AiAccentLight, AccentTeal)),
                                    RoundedCornerShape(AppRadius.large)
                                )
                        )
                        Column(modifier = Modifier.padding(AppSpacing.lg)) {
                            if (thinking != null && !isThinkingInProgress) {
                        var expanded by remember { mutableStateOf(false) }
                        Column {
                            Row(
                                modifier = Modifier.clickable { expanded = !expanded }.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = (if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary).copy(alpha = 0.7f)
                                )
                                Text(
                                    stringResource(R.string.chat_thinking_process),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontStyle = FontStyle.Italic,
                                    color = (if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary).copy(alpha = 0.7f)
                                )
                            }
                            AnimatedVisibility(visible = expanded) {
                                Text(
                                    text = thinking,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = (if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.7f),
                                    modifier = Modifier.padding(top = AppSpacing.xs)
                                )
                            }
                            if (mainContent.isNotEmpty()) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = AppSpacing.sm),
                                    color = (if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.2f)
                                )
                            }
                        }
                    }
                    val displayText = when {
                        mainContent.isNotEmpty() -> mainContent
                        isThinkingInProgress && thinking != null -> thinking
                        else -> content
                    }
                    if (displayText.isNotEmpty()) {
                        if (isUser) {
                            Text(
                                text = displayText,
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        } else {
                            MarkdownRenderer(
                                text = displayText,
                                textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth(),
                                onCitationClick = onCitationClick
                            )
                        }
                    }

                    // RAG source chips (per-message attribution)
                    val sources = message.ragSources
                    if (!isUser && !sources.isNullOrEmpty()) {
                        var sourcesExpanded by remember { mutableStateOf(true) }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.MenuBook,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.width(AppSpacing.xs))
                            Text(
                                text = if (sourcesExpanded) stringResource(R.string.chat_sources_label) else stringResource(R.string.chat_from_notes, sources.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                modifier = Modifier.clickable { sourcesExpanded = !sourcesExpanded }
                            )
                        }
                        if (sourcesExpanded) {
                            FlowRow(
                                modifier = Modifier.padding(top = AppSpacing.xs),
                                horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                                verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                            ) {
                                sources.forEach { source ->
                                    val scoreColor = when {
                                        source.relevanceScore >= 0.7f -> SuccessColor
                                        source.relevanceScore >= 0.4f -> WarningColor
                                        else -> MaterialTheme.colorScheme.outline
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                        modifier = Modifier.clickable {
                                            onNoteClick(source.noteFileName)
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .background(scoreColor, CircleShape)
                                            )
                                            Spacer(modifier = Modifier.width(AppSpacing.xs))
                                            Text(
                                                text = source.noteTitle,
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.widthIn(max = 100.dp)
                                            )
                                            val sourceLabel = when (source.source) {
                                                "graph" -> " [graph]"
                                                "embedding" -> " [embed]"
                                                else -> " [search]"
                                            }
                                            if (sourceLabel.isNotEmpty()) {
                                                Text(
                                                    text = sourceLabel,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.outline
                                                )
                                            }
                                            if (source.retrievalReasons.isNotEmpty() || source.concepts.isNotEmpty()) {
                                                Spacer(modifier = Modifier.width(2.dp))
                                                Icon(
                                                    Icons.Default.Info,
                                                    contentDescription = stringResource(R.string.chat_source_info),
                                                    modifier = Modifier
                                                        .size(14.dp)
                                                        .clickable {
                                                            onShowSearchExplanation(
                                                                source.noteTitle,
                                                                source.relevanceScore,
                                                                source.retrievalReasons,
                                                                source.concepts
                                                            )
                                                        },
                                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Phase 5: Grounded response UI
                    val grounded = message.groundedResponse
                    if (!isUser && grounded != null) {
                        // Abstention banner
                        if (grounded.abstained) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(AppSpacing.sm),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(modifier = Modifier.width(AppSpacing.xs))
                                    Text(
                                        text = grounded.abstention_reason ?: "Insufficient evidence to answer",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }

                        // Unverified claims badge
                        if (!grounded.abstained && grounded.claims.isNotEmpty()) {
                            val hasUnsupported = message.groundedDisposition == "UNVERIFIED" || grounded.claims.any { it.citationIds.orEmpty().isEmpty() && it.memory_object_ids.orEmpty().isEmpty() }
                            if (hasUnsupported) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(AppSpacing.sm),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Info,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.tertiary
                                        )
                                        Spacer(modifier = Modifier.width(AppSpacing.xs))
                                        Text(
                                            text = stringResource(R.string.grounding_unverified_badge),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.tertiary
                                        )
                                    }
                                }
                            }
                        }

                        // Citation footer chips
                        if (!grounded.abstained && grounded.citations.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                                verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                            ) {
                                val uniqueCitations = grounded.citations.distinctBy { it.chunkId }
                                uniqueCitations.forEachIndexed { idx, citation ->
                                    val excerpt = footerExcerpts.firstOrNull { it.chunkId == citation.chunkId }?.quoteText
                                    val label = buildString {
                                        append("cite-${idx + 1} · ${citation.sourceId}")
                                        if (!excerpt.isNullOrBlank()) {
                                            append(" · \"${excerpt.take(40)}${if (excerpt.length > 40) "..." else ""}\"")
                                        }
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                                        modifier = Modifier.clickable {
                                            onCitationClick?.invoke(idx + 1)
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.MenuBook,
                                                contentDescription = null,
                                                modifier = Modifier.size(12.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.widthIn(max = 240.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Suggested actions
                        val actions = grounded.suggested_actions.filter {
                            it.type != com.noteflowai.app.data.chat.ActionType.NONE
                        }
                        if (actions.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                                verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                            ) {
                                actions.forEach { action ->
                                    var confirmed by remember { mutableStateOf(false) }
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = if (confirmed)
                                            MaterialTheme.colorScheme.primaryContainer
                                        else
                                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                                        modifier = Modifier.clickable {
                                            if (!confirmed) {
                                                confirmed = true
                                                onSuggestedAction(action)
                                            }
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                when (action.type) {
                                                    com.noteflowai.app.data.chat.ActionType.CREATE_NOTE -> Icons.Default.NoteAdd
                                                    com.noteflowai.app.data.chat.ActionType.CREATE_TASK -> Icons.Default.AddTask
                                                    com.noteflowai.app.data.chat.ActionType.CONFIRM_DECISION -> Icons.Default.CheckCircle
                                                    com.noteflowai.app.data.chat.ActionType.OPEN_SOURCE -> Icons.Default.OpenInNew
                                                    else -> Icons.Default.TouchApp
                                                },
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = if (confirmed) "Confirmed" else action.label,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Standard & Local AI citation chips (when groundedResponse is null but footerExcerpts is populated)
                    if (!isUser && grounded == null && footerExcerpts.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                        ) {
                            val uniqueFooters = footerExcerpts
                                .distinctBy { it.citationIndex.takeIf { c -> c > 0 } ?: it.chunkId }
                                .sortedBy { it.citationIndex }
                            uniqueFooters.forEach { footer ->
                                val citeNum = footer.citationIndex.takeIf { it > 0 } ?: (footerExcerpts.indexOf(footer) + 1)
                                val matchedRagSource = sources?.getOrNull(citeNum - 1)
                                    ?: sources?.find { it.noteFileName == footer.chunkId || it.noteFileName == footer.sourceId }
                                val noteTitle = matchedRagSource?.noteTitle?.ifBlank { null }
                                    ?: (if (footer.sourceId.isNotBlank() && footer.sourceId != "NOTE") footer.sourceId.removePrefix("note_").noteDisplayTitle() else null)
                                    ?: footer.chunkId.removePrefix("note_").noteDisplayTitle()
                                val label = buildString {
                                    append("cite-$citeNum · $noteTitle")
                                    if (!footer.quoteText.isNullOrBlank()) {
                                        append(" · \"${footer.quoteText.take(40)}${if (footer.quoteText.length > 40) "..." else ""}\"")
                                    }
                                }
                                val isWeb = matchedRagSource?.source == "web" ||
                                    footer.chunkId.startsWith("http://") ||
                                    footer.chunkId.startsWith("https://")
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                                    modifier = Modifier.clickable {
                                        onCitationClick?.invoke(citeNum)
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            if (isWeb) Icons.Default.Language else Icons.AutoMirrored.Filled.MenuBook,
                                            contentDescription = null,
                                            modifier = Modifier.size(12.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(max = 240.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Inline retry button for error messages
                    if (!isUser && isLast && content.startsWith("Error:")) {
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = onRetry,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.common_retry), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    
                    // Metadata
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.xs),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.chat_word_token_count, message.wordCount, message.estimatedTokenCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = (if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.7f)
                        )
                        Text(
                            text = timeStr,
                            style = MaterialTheme.typography.labelSmall,
                            color = (if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.7f)
                        )
                    }

                    // Speaker button for AI messages
                    if (!isUser) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = AppSpacing.xs),
                            horizontalArrangement = Arrangement.End
                        ) {
                            IconButton(
                                onClick = {
                                    if (isSpeaking) {
                                        onStopSpeaking()
                                    } else {
                                        onSpeak(message.content)
                                    }
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = if (isSpeaking) Icons.Default.Stop else Icons.Default.VolumeUp,
                                    contentDescription = if (isSpeaking) {
                                        stringResource(R.string.notes_desc_stop_speaking)
                                    } else {
                                        stringResource(R.string.notes_desc_read_aloud)
                                    },
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Action menu
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.xs),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        var menuExpanded by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(48.dp)) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.chat_desc_more),
                                modifier = Modifier.size(16.dp),
                                tint = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_desc_copy)) },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                onClick = { menuExpanded = false; onCopy() }
                            )
                            if (!isUser && isLast) {
                                if (content.startsWith("Error:")) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.common_retry)) },
                                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) },
                                        onClick = { menuExpanded = false; onRetry() }
                                    )
                                } else {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.chat_desc_regenerate)) },
                                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                        onClick = { menuExpanded = false; onRegenerate() }
                                    )
                                }
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_desc_delete)) },
                                leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) },
                                onClick = { menuExpanded = false; onDelete() }
                            )
                        }
                    }
                    } // end AI answer Column
                } // end Row (accent bar + content)
                } // end AI answer Surface
            } // end else (AI branch)
            } // end outer Column
        } // end Box
    }

@Composable
fun ChatLoadingBubble() {
    val isDark = !isLightBg(MaterialTheme.colorScheme.background)
    val dotColor = (if (isDark) AiAccentDark else AiAccentLight).copy(alpha = 0.8f)
    val infiniteTransition = rememberInfiniteTransition(label = "typingDots")
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), shape = RoundedCornerShape(AppRadius.large)) {
            Row(modifier = Modifier.padding(AppSpacing.md), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in 0 until 3) {
                    val pulse by infiniteTransition.animateFloat(
                        initialValue = 0.35f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = androidx.compose.animation.core.tween(
                                durationMillis = 600,
                                delayMillis = i * 150,
                                easing = androidx.compose.animation.core.EaseInOut
                            ),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "typingDot$i"
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(dotColor.copy(alpha = pulse), CircleShape)
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatEmptyHero(
    onSuggestion: (String) -> Unit,
    enabled: Boolean = true
) {
    val isDark = !isLightBg(MaterialTheme.colorScheme.background)
    val gradient = if (isDark) AiGradientDark else AiGradientLight
    val accent = if (isDark) AiAccentDark else AiAccentLight
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = AppSpacing.xl)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(96.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Chat,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(44.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(AppSpacing.lg))
        Text(
            text = stringResource(R.string.chat_title),
            style = MaterialTheme.typography.headlineSmall.copy(brush = Brush.horizontalGradient(gradient)),
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(AppSpacing.xs))
        Text(
            text = stringResource(R.string.chat_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(AppSpacing.xl))
        val suggestions = listOf(
            stringResource(R.string.chat_suggestion_summarize),
            stringResource(R.string.chat_suggestion_memory),
            stringResource(R.string.chat_suggestion_commitments)
        )
        suggestions.forEach { s ->
            Surface(
                onClick = { if (enabled) onSuggestion(s) },
                enabled = enabled,
                shape = RoundedCornerShape(AppRadius.large),
                color = if (enabled) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = s,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Chat,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(AppSpacing.sm))
        }
    }
}
