package com.noteflowai.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.noteflowai.app.R
import com.noteflowai.app.data.memory.model.*
import com.noteflowai.app.data.memory.repository.CommitmentRepository
import com.noteflowai.app.data.memory.repository.DecisionRepository
import com.noteflowai.app.data.memory.repository.EntityRepository
import com.noteflowai.app.data.memory.repository.MemoryRepository
import com.noteflowai.app.data.memory.repository.MemoryReviewRepository
import com.noteflowai.app.data.noteDisplayTitle
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch

/**
 * Memory Inbox screen with tabs for reviewing extracted memories.
 * Shows pending items that need user confirmation, plus entity management
 * (confirm, ignore, rename, aliases, merge, split).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryInboxScreen(
    reviewRepository: MemoryReviewRepository,
    decisionRepository: DecisionRepository,
    commitmentRepository: CommitmentRepository,
    memoryRepository: MemoryRepository,
    entityRepository: EntityRepository,
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.inbox_tab_all),
        stringResource(R.string.inbox_tab_decisions),
        stringResource(R.string.inbox_tab_commitments),
        stringResource(R.string.inbox_tab_questions),
        stringResource(R.string.inbox_tab_entities)
    )

    com.noteflowai.app.ui.components.FreshScreen(
        title = stringResource(R.string.nav_desc_memory_inbox),
        onBack = onBack,
        modifier = modifier
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            PrimaryScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = com.noteflowai.app.ui.theme.AppSpacing.lg
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, maxLines = 1) }
                    )
                }
            }

            when (selectedTab) {
                0 -> AllReviewItemsTab(reviewRepository)
                1 -> DecisionsTab(decisionRepository, onOpenNote)
                2 -> CommitmentsTab(commitmentRepository, onOpenNote)
                3 -> QuestionsTab(memoryRepository, onOpenNote)
                4 -> EntitiesTab(entityRepository, onOpenNote)
            }
        }
    }
}

@Composable
private fun AllReviewItemsTab(reviewRepository: MemoryReviewRepository) {
    var pendingItems by remember { mutableStateOf<List<MemoryReviewItem>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        pendingItems = reviewRepository.getPending()
    }

    if (pendingItems.isEmpty()) {
        EmptyInboxState("No pending items to review")
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(pendingItems, key = { it.id }) { item ->
                ReviewItemCard(
                    item = item,
                    onAccept = {
                        scope.launch {
                            reviewRepository.accept(item.id)
                            pendingItems = reviewRepository.getPending()
                        }
                    },
                    onIgnore = {
                        scope.launch {
                            reviewRepository.ignore(item.id)
                            pendingItems = reviewRepository.getPending()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun DecisionsTab(
    decisionRepository: DecisionRepository,
    onOpenNote: (String) -> Unit
) {
    var decisions by remember { mutableStateOf<List<Decision>>(emptyList()) }
    var sourceSegments by remember { mutableStateOf<Map<String, SourceSegment>>(emptyMap()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val loaded = decisionRepository.getActive()
        decisions = loaded
        val segmentIds = loaded.map { it.sourceSegmentId }
        sourceSegments = decisionRepository.getSourceSegments(segmentIds)
    }

    if (decisions.isEmpty()) {
        EmptyInboxState("No decisions yet")
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(decisions, key = { it.id }) { decision ->
                DecisionCard(
                    decision = decision,
                    sourceSegment = sourceSegments[decision.sourceSegmentId],
                    onOpenNote = onOpenNote
                )
            }
        }
    }
}

@Composable
private fun CommitmentsTab(
    commitmentRepository: CommitmentRepository,
    onOpenNote: (String) -> Unit
) {
    var commitments by remember { mutableStateOf<List<Commitment>>(emptyList()) }
    var sourceSegments by remember { mutableStateOf<Map<String, SourceSegment>>(emptyMap()) }

    LaunchedEffect(Unit) {
        val loaded = commitmentRepository.getActive()
        commitments = loaded
        val segmentIds = loaded.map { it.sourceSegmentId }
        sourceSegments = commitmentRepository.getSourceSegments(segmentIds)
    }

    if (commitments.isEmpty()) {
        EmptyInboxState("No commitments yet")
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(commitments, key = { it.id }) { commitment ->
                CommitmentCard(
                    commitment = commitment,
                    sourceSegment = sourceSegments[commitment.sourceSegmentId],
                    onOpenNote = onOpenNote
                )
            }
        }
    }
}

@Composable
private fun QuestionsTab(memoryRepository: MemoryRepository, onOpenNote: (String) -> Unit) {
    var questions by remember { mutableStateOf<List<MemoryObject>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            error = null
            try {
                questions = memoryRepository.getByTypeAndStatus(MemoryType.QUESTION, MemoryObjectStatus.DETECTED)
            } catch (e: Exception) {
                error = e.message ?: "Failed to load questions"
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    error?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
    if (questions.isEmpty() && error == null) {
        EmptyInboxState(stringResource(R.string.inbox_empty_questions))
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(questions, key = { it.id }) { question ->
                MemoryObjectCard(
                    memoryObject = question,
                    onOpenNote = onOpenNote,
                    onConfirm = {
                        scope.launch {
                            try {
                                memoryRepository.confirm(question.id)
                                questions = memoryRepository.getByTypeAndStatus(
                                    MemoryType.QUESTION,
                                    MemoryObjectStatus.DETECTED
                                )
                            } catch (e: Exception) {
                                error = e.message ?: "Failed to confirm question"
                            }
                        }
                    },
                    onCancel = {
                        scope.launch {
                            try {
                                memoryRepository.cancel(question.id)
                                questions = memoryRepository.getByTypeAndStatus(
                                    MemoryType.QUESTION,
                                    MemoryObjectStatus.DETECTED
                                )
                            } catch (e: Exception) {
                                error = e.message ?: "Failed to dismiss question"
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun EntitiesTab(entityRepository: EntityRepository, onOpenNote: (String) -> Unit) {
    var entities by remember { mutableStateOf<List<Entity>>(emptyList()) }
    var selectedEntityId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val reload = {
        scope.launch {
            entities = entityRepository.getAll()
        }
    }

    LaunchedEffect(Unit) {
        reload()
    }

    val selected = selectedEntityId?.let { id -> entities.firstOrNull { it.id == id } }
    if (selected != null) {
        EntityDetailView(
            entityRepository = entityRepository,
            entity = selected,
            onBack = { selectedEntityId = null },
            onEntityChanged = { reload() },
            onOpenNote = onOpenNote
        )
    } else {
        if (entities.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.entity_empty_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.entity_empty_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EntityType.entries.forEach { type ->
                    val typeEntities = entities.filter { it.type == type }
                    if (typeEntities.isNotEmpty()) {
                        item(key = "header_${type.name}") {
                            EntityTypeHeader(type = type, count = typeEntities.size)
                        }
                        items(typeEntities, key = { it.id }) { entity ->
                            EntityRow(
                                entity = entity,
                                onClick = { selectedEntityId = entity.id },
                                onConfirm = {
                                    scope.launch {
                                        entityRepository.confirm(entity.id)
                                        reload()
                                    }
                                },
                                onReject = {
                                    scope.launch {
                                        entityRepository.reject(entity.id)
                                        reload()
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

@Composable
private fun EntityTypeHeader(type: EntityType, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = type.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun EntityRow(
    entity: Entity,
    onClick: () -> Unit,
    onConfirm: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text(entity.type.name) },
                    leadingIcon = {
                        Icon(
                            EntityTypeIcon(entity.type),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    modifier = Modifier.height(28.dp)
                )
                ConfirmationBadge(entity.confirmation)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = entity.canonicalName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )

            if (entity.confidence > 0f) {
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { entity.confidence },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(MaterialTheme.shapes.small),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            if (entity.confirmation != ConfirmationState.CONFIRMED) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (entity.confirmation != ConfirmationState.REJECTED) {
                        TextButton(onClick = onReject) {
                            Text(stringResource(R.string.entity_action_reject), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onConfirm) {
                        Text(stringResource(R.string.entity_action_confirm))
                    }
                }
            }
        }
    }
}

@Composable
private fun EntityTypeIcon(type: EntityType): androidx.compose.ui.graphics.vector.ImageVector {
    return when (type) {
        EntityType.PERSON -> Icons.Default.Person
        EntityType.PROJECT -> Icons.Default.Work
        EntityType.COMPANY -> Icons.Default.Business
        EntityType.PLACE -> Icons.Default.Place
        EntityType.TOPIC -> Icons.Default.Label
        EntityType.PRODUCT -> Icons.Default.ShoppingCart
        EntityType.ORGANIZATION -> Icons.Default.AccountBalance
        EntityType.UNKNOWN -> Icons.Default.HelpOutline
    }
}

@Composable
private fun ConfirmationBadge(confirmation: ConfirmationState) {
    val resId = when (confirmation) {
        ConfirmationState.CONFIRMED -> R.string.entity_confirmed
        ConfirmationState.REJECTED -> R.string.entity_rejected
        ConfirmationState.MERGED -> R.string.entity_confirmed
        ConfirmationState.SUGGESTED -> R.string.entity_suggested
    }
    val color = when (confirmation) {
        ConfirmationState.CONFIRMED -> MaterialTheme.colorScheme.primary
        ConfirmationState.REJECTED -> MaterialTheme.colorScheme.error
        ConfirmationState.MERGED -> MaterialTheme.colorScheme.tertiary
        ConfirmationState.SUGGESTED -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    }
    Text(
        text = stringResource(resId),
        style = MaterialTheme.typography.labelSmall,
        color = color
    )
}

@Composable
private fun EntityDetailView(
    entityRepository: EntityRepository,
    entity: Entity,
    onBack: () -> Unit,
    onEntityChanged: () -> Unit,
    onOpenNote: (String) -> Unit = {}
) {
    var mentions by remember { mutableStateOf<List<Pair<EntityMention, SourceSegment?>>>(emptyList()) }
    var aliases by remember { mutableStateOf<List<String>>(emptyList()) }
    var showMerge by remember { mutableStateOf(false) }
    var showSplit by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showAliasField by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val reload = {
        scope.launch {
            mentions = entityRepository.getMentionsWithSegments(entity.id)
            aliases = entityRepository.getById(entity.id)?.let { parseAliasesForDisplay(it) } ?: emptyList()
        }
    }

    LaunchedEffect(entity.id) {
        reload()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.entity_back))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.entity_back))
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Identity card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = entity.canonicalName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            ConfirmationBadge(entity.confirmation)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = entity.type.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        if (entity.confidence > 0f) {
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { entity.confidence },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(MaterialTheme.shapes.small),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            if (entity.confirmation != ConfirmationState.CONFIRMED) {
                                TextButton(onClick = {
                                    scope.launch {
                                        entityRepository.confirm(entity.id)
                                        onEntityChanged()
                                    }
                                }) {
                                    Text(stringResource(R.string.entity_action_confirm))
                                }
                            }
                            if (entity.confirmation != ConfirmationState.REJECTED) {
                                TextButton(onClick = {
                                    scope.launch {
                                        entityRepository.reject(entity.id)
                                        onEntityChanged()
                                        onBack()
                                    }
                                }) {
                                    Text(stringResource(R.string.entity_action_reject), color = MaterialTheme.colorScheme.error)
                                }
                            }
                            TextButton(onClick = { showMerge = true }) {
                                Text(stringResource(R.string.entity_action_merge))
                            }
                            TextButton(onClick = { showSplit = true }) {
                                Text(stringResource(R.string.entity_action_split))
                            }
                            TextButton(onClick = { showDelete = true }) {
                                Text(stringResource(R.string.entity_action_delete), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            // Rename
            item {
                RenameAliasCard(
                    entity = entity,
                    onRename = { newName ->
                        scope.launch {
                            entityRepository.rename(entity.id, newName)
                            onEntityChanged()
                        }
                    }
                )
            }

            // Aliases
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.entity_aliases_label),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (aliases.isEmpty()) {
                            Text(
                                text = stringResource(R.string.entity_aliases_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        } else {
                            aliases.forEach { alias ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = alias,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = {
                                        scope.launch {
                                            entityRepository.removeAlias(entity.id, alias)
                                            aliases = entityRepository.getById(entity.id)?.let { parseAliasesForDisplay(it) } ?: emptyList()
                                        }
                                    }) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                        if (showAliasField) {
                            var aliasInput by remember { mutableStateOf("") }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = aliasInput,
                                    onValueChange = { aliasInput = it },
                                    label = { Text(stringResource(R.string.entity_alias_hint)) },
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(onClick = {
                                    if (aliasInput.isNotBlank()) {
                                        scope.launch {
                                            entityRepository.addAlias(entity.id, aliasInput)
                                            aliasInput = ""
                                            aliases = entityRepository.getById(entity.id)?.let { parseAliasesForDisplay(it) } ?: emptyList()
                                        }
                                    }
                                }) {
                                    Text(stringResource(R.string.entity_action_add_alias))
                                }
                            }
                        } else {
                            Spacer(modifier = Modifier.height(4.dp))
                            TextButton(onClick = { showAliasField = true }) {
                                Text(stringResource(R.string.entity_action_add_alias))
                            }
                        }
                    }
                }
            }

            // Evidence mentions
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.entity_mentions_label),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (mentions.isEmpty()) {
                            Text(
                                text = stringResource(R.string.entity_mentions_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        } else {
                            mentions.forEach { (mention, segment) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Link,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = if (mention.confirmation == ConfirmationState.REJECTED)
                                            MaterialTheme.colorScheme.outline
                                        else
                                            MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "“${mention.mentionText}”",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (mention.confirmation == ConfirmationState.REJECTED) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = stringResource(R.string.entity_rejected),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                } else {
                                    TextButton(onClick = {
                                        scope.launch {
                                            entityRepository.removeLink(entity.id, mention.sourceSegmentId)
                                            reload()
                                        }
                                    }) {
                                        Text(
                                            stringResource(R.string.entity_action_remove_link),
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                                if (segment != null) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = segment.text.take(180) + if (segment.text.length > 180) "…" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    AssistChip(
                                        onClick = { onOpenNote(segment.sourceId) },
                                        label = {
                                            Text(
                                                text = "${segment.sourceId.noteDisplayTitle()} • ${SegmentLocation(segment)}",
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Description,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    )
                                }
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showMerge) {
        MergeEntityDialog(
            entityRepository = entityRepository,
            entity = entity,
            onDismiss = { showMerge = false },
            onMerged = {
                showMerge = false
                onEntityChanged()
                onBack()
            }
        )
    }
    if (showSplit) {
        SplitEntityDialog(
            entityRepository = entityRepository,
            entity = entity,
            mentions = mentions,
            onDismiss = { showSplit = false },
            onSplit = {
                showSplit = false
                onEntityChanged()
                onBack()
            }
        )
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(R.string.entity_delete_title)) },
            text = { Text(stringResource(R.string.entity_delete_message, entity.canonicalName)) },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    scope.launch {
                        entityRepository.deleteById(entity.id)
                        onEntityChanged()
                        onBack()
                    }
                }) {
                    Text(stringResource(R.string.entity_action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) {
                    Text(stringResource(R.string.entity_back))
                }
            }
        )
    }
}

@Composable
private fun RenameAliasCard(
    entity: Entity,
    onRename: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        var renameInput by remember(entity.id) { mutableStateOf("") }
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.entity_action_rename),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text(stringResource(R.string.entity_rename_hint)) },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (renameInput.isNotBlank()) {
                            onRename(renameInput)
                            renameInput = ""
                        }
                    }
                ) {
                    Text(stringResource(R.string.entity_action_rename))
                }
            }
        }
    }
}

@Composable
private fun MergeEntityDialog(
    entityRepository: EntityRepository,
    entity: Entity,
    onDismiss: () -> Unit,
    onMerged: () -> Unit
) {
    var others by remember { mutableStateOf<List<Entity>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        others = entityRepository.getAll().filter { it.id != entity.id }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.entity_merge_title)) },
        text = {
            if (others.isEmpty()) {
                Text(stringResource(R.string.entity_merge_empty))
            } else {
                Column {
                    Text(
                        text = stringResource(R.string.entity_merge_message, entity.canonicalName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                    ) {
                        items(others, key = { it.id }) { other ->
                            ListItem(
                                headlineContent = { Text(other.canonicalName) },
                                supportingContent = { Text(other.type.name) },
                                modifier = Modifier.clickable {
                                    scope.launch {
                                        entityRepository.merge(targetId = other.id, sourceId = entity.id)
                                        onMerged()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.entity_back))
            }
        }
    )
}

@Composable
private fun SplitEntityDialog(
    entityRepository: EntityRepository,
    entity: Entity,
    mentions: List<Pair<EntityMention, SourceSegment?>>,
    onDismiss: () -> Unit,
    onSplit: () -> Unit
) {
    var nameInput by remember { mutableStateOf("") }
    var typeInput by remember { mutableStateOf(entity.type) }
    val selected = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.entity_split_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text(stringResource(R.string.entity_split_name_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                // Type picker
                var typeMenuOpen by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { typeMenuOpen = true }) {
                        Text(typeInput.name)
                    }
                    DropdownMenu(expanded = typeMenuOpen, onDismissRequest = { typeMenuOpen = false }) {
                        EntityType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.name) },
                                onClick = {
                                    typeInput = type
                                    typeMenuOpen = false
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.entity_split_mentions),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                ) {
                    itemsIndexed(
                        mentions,
                        // Stable id: segment is unique per row; index in the key made
                        // checkbox state jump when a row was removed.
                        key = { _, item -> item.first.sourceSegmentId }
                    ) { _, (mention, segment) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = selected.contains(mention.sourceSegmentId),
                                onCheckedChange = { checked ->
                                    if (checked) selected.add(mention.sourceSegmentId)
                                    else selected.remove(mention.sourceSegmentId)
                                }
                            )
                            Text(
                                text = mention.mentionText,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (nameInput.isNotBlank() && selected.isNotEmpty()) {
                    scope.launch {
                        entityRepository.split(
                            sourceId = entity.id,
                            newName = nameInput,
                            newType = typeInput,
                            mentionSegmentIds = selected.toList()
                        )
                        onSplit()
                    }
                }
            }) {
                Text(stringResource(R.string.entity_split_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.entity_back))
            }
        }
    )
}

private fun parseAliasesForDisplay(entity: Entity): List<String> {
    val json = entity.aliasesJson ?: return emptyList()
    if (json.isBlank()) return emptyList()
    return try {
        com.google.gson.Gson().fromJson<List<String>>(
            json,
            object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
        ) ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }
}

private fun SegmentLocation(segment: SourceSegment): String {
    val parts = mutableListOf<String>()
    segment.sourceType?.let { parts.add(it.name) }
    segment.startMs?.let { parts.add("${it / 1000}s") }
    segment.pageNumber?.let { parts.add("p${it}") }
    return parts.joinToString(" · ")
}

@Composable
private fun EmptyInboxState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.CheckCircleOutline,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun ReviewItemCard(
    item: MemoryReviewItem,
    onAccept: () -> Unit,
    onIgnore: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text(item.type.name) },
                    leadingIcon = {
                        Icon(
                            when (item.type) {
                                ReviewItemType.DECISION -> Icons.Default.Gavel
                                ReviewItemType.COMMITMENT -> Icons.AutoMirrored.Filled.Assignment
                                ReviewItemType.QUESTION -> Icons.Default.HelpOutline
                                ReviewItemType.ENTITY -> Icons.Default.Person
                                ReviewItemType.CONFLICT -> Icons.Default.Warning
                            },
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                Text(
                    text = stringResource(R.string.inbox_pending),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = item.reason,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onIgnore) {
                    Text(stringResource(R.string.inbox_ignore))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onAccept) {
                    Text(stringResource(R.string.inbox_confirm))
                }
            }
        }
    }
}

@Composable
private fun DecisionCard(
    decision: Decision,
    sourceSegment: SourceSegment? = null,
    onOpenNote: ((String) -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text(stringResource(R.string.inbox_type_decision)) },
                    leadingIcon = { Icon(Icons.Default.Gavel, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                Text(
                    text = decision.status.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (decision.status) {
                        DecisionStatus.ACTIVE -> MaterialTheme.colorScheme.primary
                        DecisionStatus.CONFIRMED -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = decision.statement,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )

            decision.reason?.let { reason ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.inbox_reason_prefix, reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            if (sourceSegment != null && onOpenNote != null) {
                Spacer(modifier = Modifier.height(8.dp))
                AssistChip(
                    onClick = { onOpenNote(sourceSegment.sourceId) },
                    label = {
                        Text(
                            sourceSegment.sourceId.noteDisplayTitle(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Description,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun CommitmentCard(
    commitment: Commitment,
    sourceSegment: SourceSegment? = null,
    onOpenNote: ((String) -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text(stringResource(R.string.inbox_type_commitment)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Assignment, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                Text(
                    text = commitment.status.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (commitment.status) {
                        CommitmentStatus.ACTIVE -> MaterialTheme.colorScheme.primary
                        CommitmentStatus.OVERDUE -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = commitment.action,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )

            commitment.ownerText?.let { owner ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.inbox_owner_prefix, owner),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            if (sourceSegment != null && onOpenNote != null) {
                Spacer(modifier = Modifier.height(8.dp))
                AssistChip(
                    onClick = { onOpenNote(sourceSegment.sourceId) },
                    label = {
                        Text(
                            sourceSegment.sourceId.noteDisplayTitle(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Description,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun MemoryObjectCard(
    memoryObject: MemoryObject,
    onOpenNote: ((String) -> Unit)? = null,
    onConfirm: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            AssistChip(
                onClick = {},
                label = { Text(memoryObject.type.name) },
                leadingIcon = {
                    Icon(
                        when (memoryObject.type) {
                            MemoryType.QUESTION -> Icons.Default.HelpOutline
                            MemoryType.IDEA -> Icons.Default.Lightbulb
                            MemoryType.FACT -> Icons.Default.FactCheck
                            MemoryType.OPINION -> Icons.Default.Info
                            else -> Icons.Default.Memory
                        },
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = memoryObject.statement,
                style = MaterialTheme.typography.bodyMedium
            )

            if (memoryObject.sourceId.isNotBlank() && onOpenNote != null) {
                Spacer(modifier = Modifier.height(8.dp))
                AssistChip(
                    onClick = { onOpenNote(memoryObject.sourceId) },
                    label = {
                        Text(
                            memoryObject.sourceId.noteDisplayTitle(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Description,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                )
            }

            if (onConfirm != null || onCancel != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onCancel != null) {
                        TextButton(onClick = onCancel) {
                            Text(stringResource(R.string.inbox_ignore))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    if (onConfirm != null) {
                        Button(onClick = onConfirm) {
                            Text(stringResource(R.string.inbox_confirm))
                        }
                    }
                }
            }
        }
    }
}
