package com.noteflowai.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.noteflowai.app.data.memory.model.Decision
import com.noteflowai.app.data.memory.model.DecisionStatus
import com.noteflowai.app.data.memory.repository.DecisionRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.noteDisplayTitle
import androidx.compose.ui.text.style.TextOverflow

/**
 * Phase 6: Decision Timeline screen.
 * Shows decisions in chronological order with status, reason, and actions.
 * Filterable by status. Each decision shows on a timeline with date markers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DecisionTimelineScreen(
    decisionRepository: DecisionRepository,
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var decisions by remember { mutableStateOf<List<Decision>>(emptyList()) }
    var sourceSegments by remember { mutableStateOf<Map<String, SourceSegment>>(emptyMap()) }
    var selectedFilter by remember { mutableStateOf<DecisionStatus?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    suspend fun reload() {
        isLoading = true
        error = null
        try {
            val list = if (selectedFilter != null) {
                decisionRepository.getByStatus(selectedFilter!!)
            } else {
                decisionRepository.getTimeline()
            }
            decisions = list
            val segIds = list.map { it.sourceSegmentId }.distinct()
            sourceSegments = decisionRepository.getSourceSegments(segIds)
        } catch (e: Exception) {
            error = e.message ?: "Failed to load decisions"
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(selectedFilter) {
        reload()
    }

    fun runAction(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (e: Exception) {
                error = e.message ?: "Action failed"
            }
            reload()
        }
    }

    com.noteflowai.app.ui.components.FreshScreen(
        title = stringResource(R.string.decision_timeline_title),
        onBack = onBack,
        modifier = modifier,
        backContentDescription = stringResource(R.string.common_back)
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // Status filter chips
            FilterChipRow(
                selectedFilter = selectedFilter,
                onFilterSelected = { selectedFilter = it }
            )

            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (decisions.isEmpty()) {
                EmptyTimelineState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    items(decisions, key = { it.id }) { decision ->
                        TimelineItem(
                            decision = decision,
                            sourceSegment = sourceSegments[decision.sourceSegmentId],
                            onOpenNote = onOpenNote,
                            dateFormat = dateFormat,
                            onConfirm = {
                                runAction { decisionRepository.confirmDecision(decision.id) }
                            },
                            onReverse = {
                                runAction { decisionRepository.markReversed(decision.id) }
                            },
                            onSupersede = {
                                runAction { decisionRepository.markSuperseded(decision.id) }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChipRow(
    selectedFilter: DecisionStatus?,
    onFilterSelected: (DecisionStatus?) -> Unit
) {
    val allLabel = stringResource(R.string.decision_timeline_filter_all)
    val labels = mapOf<DecisionStatus?, Int>(
        DecisionStatus.DETECTED to R.string.decision_timeline_filter_detected,
        DecisionStatus.CONFIRMED to R.string.decision_timeline_filter_confirmed,
        DecisionStatus.ACTIVE to R.string.decision_timeline_filter_active,
        DecisionStatus.REVERSED to R.string.decision_timeline_filter_reversed,
        DecisionStatus.SUPERSEDED to R.string.decision_timeline_filter_superseded,
        DecisionStatus.COMPLETED to R.string.decision_timeline_filter_completed,
        DecisionStatus.UNCERTAIN to R.string.decision_timeline_filter_uncertain
    ).mapValues { stringResource(it.value) }
    val options = listOf(allLabel) + labels.values.toList()
    val selected = if (selectedFilter == null) allLabel else labels[selectedFilter] ?: allLabel
    com.noteflowai.app.ui.components.FreshFilterRow(
        options = options,
        selected = selected,
        onSelect = { label ->
            onFilterSelected(if (label == allLabel) null else labels.entries.find { it.value == label }?.key)
        }
    )
}

@Composable
private fun TimelineItem(
    decision: Decision,
    sourceSegment: SourceSegment?,
    onOpenNote: (String) -> Unit,
    dateFormat: SimpleDateFormat,
    onConfirm: () -> Unit,
    onReverse: () -> Unit,
    onSupersede: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        // Timeline line + dot
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(
                        when (decision.status) {
                            DecisionStatus.CONFIRMED -> MaterialTheme.colorScheme.primary
                            DecisionStatus.ACTIVE -> MaterialTheme.colorScheme.tertiary
                            DecisionStatus.REVERSED -> MaterialTheme.colorScheme.error
                            DecisionStatus.SUPERSEDED -> MaterialTheme.colorScheme.outline
                            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        }
                    )
            )
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(80.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Decision card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Status + date row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistChip(
                        onClick = {},
                        label = { Text(decision.status.name) },
                        leadingIcon = {
                            Icon(
                                when (decision.status) {
                                    DecisionStatus.CONFIRMED -> Icons.Default.CheckCircle
                                    DecisionStatus.ACTIVE -> Icons.Default.Schedule
                                    DecisionStatus.REVERSED -> Icons.Default.Undo
                                    DecisionStatus.SUPERSEDED -> Icons.Default.Update
                                    else -> Icons.Default.HelpOutline
                                },
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        modifier = Modifier.height(28.dp)
                    )
                    decision.decidedAt?.let { date ->
                        Text(
                            text = dateFormat.format(Date(date)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Decision statement
                Text(
                    text = decision.statement,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )

                // Reason (if present)
                decision.reason?.let { reason ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }

                // Clickable source note chip
                if (sourceSegment != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    AssistChip(
                        onClick = { onOpenNote(sourceSegment.sourceId) },
                        label = {
                            Text(
                                text = sourceSegment.sourceId.noteDisplayTitle(),
                                style = MaterialTheme.typography.labelSmall,
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
                        },
                        modifier = Modifier.height(26.dp)
                    )
                }

                // Confidence indicator
                if (decision.confidence > 0f) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { decision.confidence },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(MaterialTheme.shapes.small),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }

                // Action buttons (only for non-terminal statuses)
                if (decision.status in listOf(DecisionStatus.DETECTED, DecisionStatus.CONFIRMED, DecisionStatus.ACTIVE)) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        if (decision.status == DecisionStatus.DETECTED) {
                            TextButton(onClick = onConfirm) {
                                Text(stringResource(R.string.decision_timeline_action_confirm))
                            }
                        }
                        if (decision.status != DecisionStatus.REVERSED) {
                            TextButton(onClick = onReverse) {
                                Text(stringResource(R.string.decision_timeline_action_reverse), color = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (decision.status != DecisionStatus.SUPERSEDED) {
                            TextButton(onClick = onSupersede) {
                                Text(stringResource(R.string.decision_timeline_action_supersede))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyTimelineState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Gavel,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.decision_timeline_empty_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.decision_timeline_empty_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}
