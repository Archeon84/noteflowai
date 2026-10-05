package com.noteflowai.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.noteflowai.app.ui.theme.AppSpacing
import com.noteflowai.app.data.memory.model.Commitment
import com.noteflowai.app.data.memory.model.CommitmentStatus
import com.noteflowai.app.data.memory.repository.CommitmentRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.noteDisplayTitle
import androidx.compose.ui.text.style.TextOverflow

/**
 * Phase 6: Commitment Dashboard screen.
 * Shows commitments grouped by status with progress indicators.
 * Sections: Overdue, Due Soon, Active, Completed.
 * Each commitment has action buttons (confirm, complete, cancel).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommitmentDashboardScreen(
    commitmentRepository: CommitmentRepository,
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var overdue by remember { mutableStateOf<List<Commitment>>(emptyList()) }
    var dueSoon by remember { mutableStateOf<List<Commitment>>(emptyList()) }
    var active by remember { mutableStateOf<List<Commitment>>(emptyList()) }
    var completed by remember { mutableStateOf<List<Commitment>>(emptyList()) }
    var needsReview by remember { mutableStateOf<List<Commitment>>(emptyList()) }
    var cancelled by remember { mutableStateOf<List<Commitment>>(emptyList()) }
    var sourceSegments by remember { mutableStateOf<Map<String, SourceSegment>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    suspend fun refreshData() {
        error = null
        try {
            val overdueList = commitmentRepository.getOverdue()
            val dueSoonList = commitmentRepository.getDueSoon(TimeUnit.DAYS.toMillis(7))
            // Active is the remainder: exclude items already shown under
            // Overdue / Due Soon so summary counts stay distinct.
            val datedIds = (overdueList + dueSoonList).map { it.id }.toSet()
            overdue = overdueList
            dueSoon = dueSoonList
            active = commitmentRepository.getActive().filter { it.id !in datedIds }
            completed = commitmentRepository.getCompleted()
            needsReview = commitmentRepository.getByStatus(CommitmentStatus.DETECTED)
            cancelled = commitmentRepository.getByStatus(CommitmentStatus.CANCELLED)

            val allCommitments = overdueList + dueSoonList + active + completed + needsReview + cancelled
            val segIds = allCommitments.map { it.sourceSegmentId }.distinct()
            sourceSegments = commitmentRepository.getSourceSegments(segIds)
        } catch (e: Exception) {
            error = e.message ?: "Failed to load commitments"
        }
    }

    LaunchedEffect(Unit) {
        refreshData()
    }

    fun launchRefresh(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (e: Exception) {
                error = e.message ?: "Action failed"
            }
            refreshData()
        }
    }

    com.noteflowai.app.ui.components.FreshScreen(
        title = stringResource(R.string.commitment_dashboard_title),
        onBack = onBack,
        modifier = modifier,
        backContentDescription = stringResource(R.string.common_back)
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // Summary cards
            SummaryRow(overdue.size, dueSoon.size, active.size, completed.size, needsReview.size)

            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // Tab row
            val tabs = listOf(
                stringResource(R.string.commitment_dashboard_tab_overdue),
                stringResource(R.string.commitment_dashboard_tab_due_soon),
                stringResource(R.string.commitment_dashboard_tab_active),
                stringResource(R.string.commitment_dashboard_tab_completed),
                stringResource(R.string.commitment_dashboard_tab_needs_review),
                stringResource(R.string.commitment_dashboard_tab_cancelled)
            )
            PrimaryScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = AppSpacing.lg
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(title, maxLines = 1)
                                val count = when (index) {
                                    0 -> overdue.size
                                    1 -> dueSoon.size
                                    2 -> active.size
                                    3 -> completed.size
                                    4 -> needsReview.size
                                    5 -> cancelled.size
                                    else -> 0
                                }
                                if (count > 0) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Badge(
                                        containerColor = when (index) {
                                            0 -> MaterialTheme.colorScheme.error
                                            1 -> MaterialTheme.colorScheme.tertiary
                                            2 -> MaterialTheme.colorScheme.primary
                                            3 -> MaterialTheme.colorScheme.outline
                                            5 -> MaterialTheme.colorScheme.error
                                            else -> MaterialTheme.colorScheme.secondary
                                        }
                                    ) {
                                        Text("$count")
                                    }
                                }
                            }
                        }
                    )
                }
            }

            // Content
            when (selectedTab) {
                0 -> CommitmentList(
                    commitments = overdue,
                    sourceSegments = sourceSegments,
                    onOpenNote = onOpenNote,
                    dateFormat = dateFormat,
                    emptyMessage = stringResource(R.string.commitment_dashboard_empty_overdue),
                    onComplete = { id -> launchRefresh { commitmentRepository.completeCommitment(id) } },
                    onCancel = { id -> launchRefresh { commitmentRepository.cancelCommitment(id) } }
                )
                1 -> CommitmentList(
                    commitments = dueSoon,
                    sourceSegments = sourceSegments,
                    onOpenNote = onOpenNote,
                    dateFormat = dateFormat,
                    emptyMessage = stringResource(R.string.commitment_dashboard_empty_due_soon),
                    onComplete = { id -> launchRefresh { commitmentRepository.completeCommitment(id) } },
                    onCancel = { id -> launchRefresh { commitmentRepository.cancelCommitment(id) } }
                )
                2 -> CommitmentList(
                    commitments = active,
                    sourceSegments = sourceSegments,
                    onOpenNote = onOpenNote,
                    dateFormat = dateFormat,
                    emptyMessage = stringResource(R.string.commitment_dashboard_empty_active),
                    onComplete = { id -> launchRefresh { commitmentRepository.completeCommitment(id) } },
                    onCancel = { id -> launchRefresh { commitmentRepository.cancelCommitment(id) } }
                )
                3 -> CommitmentList(
                    commitments = completed,
                    sourceSegments = sourceSegments,
                    onOpenNote = onOpenNote,
                    dateFormat = dateFormat,
                    emptyMessage = stringResource(R.string.commitment_dashboard_empty_completed),
                    onComplete = { },
                    onCancel = { }
                )
                4 -> CommitmentList(
                    commitments = needsReview,
                    sourceSegments = sourceSegments,
                    onOpenNote = onOpenNote,
                    dateFormat = dateFormat,
                    emptyMessage = stringResource(R.string.commitment_dashboard_empty_needs_review),
                    onConfirm = { id -> launchRefresh { commitmentRepository.confirmCommitment(id) } },
                    onComplete = { id -> launchRefresh { commitmentRepository.completeCommitment(id) } },
                    onCancel = { id -> launchRefresh { commitmentRepository.cancelCommitment(id) } }
                )
                5 -> CommitmentList(
                    commitments = cancelled,
                    sourceSegments = sourceSegments,
                    onOpenNote = onOpenNote,
                    dateFormat = dateFormat,
                    emptyMessage = stringResource(R.string.commitment_dashboard_empty_cancelled),
                    onComplete = { },
                    onCancel = { }
                )
            }
        }
    }
}

@Composable
private fun SummaryRow(
    overdueCount: Int,
    dueSoonCount: Int,
    activeCount: Int,
    completedCount: Int,
    needsReviewCount: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SummaryCard(
            count = overdueCount,
            label = stringResource(R.string.commitment_dashboard_summary_overdue),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f)
        )
        SummaryCard(
            count = dueSoonCount,
            label = stringResource(R.string.commitment_dashboard_summary_due_soon),
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.weight(1f)
        )
        SummaryCard(
            count = activeCount,
            label = stringResource(R.string.commitment_dashboard_summary_active),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        SummaryCard(
            count = completedCount,
            label = stringResource(R.string.commitment_dashboard_summary_done),
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(1f)
        )
        SummaryCard(
            count = needsReviewCount,
            label = stringResource(R.string.commitment_dashboard_summary_needs_review),
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SummaryCard(
    count: Int,
    label: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.1f)
        )
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "$count",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun CommitmentList(
    commitments: List<Commitment>,
    sourceSegments: Map<String, SourceSegment>,
    onOpenNote: (String) -> Unit,
    dateFormat: SimpleDateFormat,
    emptyMessage: String,
    onComplete: (String) -> Unit,
    onCancel: (String) -> Unit,
    onConfirm: ((String) -> Unit)? = null
) {
    if (commitments.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.AutoMirrored.Filled.Assignment,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = emptyMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(commitments, key = { it.id }) { commitment ->
                CommitmentDashboardCard(
                    commitment = commitment,
                    sourceSegment = sourceSegments[commitment.sourceSegmentId],
                    onOpenNote = onOpenNote,
                    dateFormat = dateFormat,
                    onComplete = { onComplete(commitment.id) },
                    onCancel = { onCancel(commitment.id) },
                    onConfirm = onConfirm?.let { confirm -> { confirm(commitment.id) } }
                )
            }
        }
    }
}

@Composable
private fun CommitmentDashboardCard(
    commitment: Commitment,
    sourceSegment: SourceSegment?,
    onOpenNote: (String) -> Unit,
    dateFormat: SimpleDateFormat,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (commitment.status) {
                CommitmentStatus.OVERDUE -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                CommitmentStatus.ACTIVE -> MaterialTheme.colorScheme.surfaceVariant
                CommitmentStatus.COMPLETED -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Status + due date row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text(commitment.status.name) },
                    leadingIcon = {
                        Icon(
                            when (commitment.status) {
                                CommitmentStatus.OVERDUE -> Icons.Default.Warning
                                CommitmentStatus.ACTIVE -> Icons.Default.Schedule
                                CommitmentStatus.COMPLETED -> Icons.Default.CheckCircle
                                CommitmentStatus.CANCELLED -> Icons.Default.Cancel
                                else -> Icons.Default.HelpOutline
                            },
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    modifier = Modifier.height(28.dp)
                )
                commitment.dueAt?.let { due ->
                    // Only actionable items can be overdue; completed/cancelled
                    // keep a neutral date even if their due date passed.
                    val isOverdue = due <= System.currentTimeMillis() &&
                        commitment.status in listOf(CommitmentStatus.ACTIVE, CommitmentStatus.CONFIRMED)
                    Text(
                        text = stringResource(R.string.commitment_dashboard_due_prefix, dateFormat.format(Date(due))),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isOverdue) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action text
            Text(
                text = commitment.action,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )

            // Owner (if present)
            commitment.ownerText?.let { owner ->
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = owner,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }

            // Confidence indicator
            if (commitment.confidence > 0f) {
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { commitment.confidence },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(MaterialTheme.shapes.small),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
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

            // Action buttons (only for actionable statuses).
            // DETECTED offers Confirm + Complete + Cancel; CONFIRMED/ACTIVE
            // offer Complete + Cancel. Each label calls its own callback.
            if (commitment.status in listOf(CommitmentStatus.DETECTED, CommitmentStatus.CONFIRMED, CommitmentStatus.ACTIVE)) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (commitment.status == CommitmentStatus.DETECTED && onConfirm != null) {
                        TextButton(onClick = onConfirm) {
                            Text(stringResource(R.string.commitment_dashboard_action_confirm))
                        }
                    }
                    TextButton(onClick = onComplete) {
                        Text(stringResource(R.string.commitment_dashboard_action_complete), color = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.commitment_dashboard_action_cancel), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
