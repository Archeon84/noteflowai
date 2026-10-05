package com.noteflowai.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Commit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.noteflowai.app.R
import com.noteflowai.app.data.memory.analysis.WeeklyReviewService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeeklyReviewScreen(
    context: android.content.Context,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val service = remember { WeeklyReviewService(context) }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    var review by remember { mutableStateOf<WeeklyReviewService.WeeklyReview?>(null) }
    var expandedSections by remember { mutableStateOf(setOf("decisions", "reviews")) }
    var isRefreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        error = null
        try {
            review = service.generateReview()
        } catch (e: Exception) {
            error = e.message ?: "Failed to generate review"
        }
    }

    LaunchedEffect(Unit) {
        load()
    }

    com.noteflowai.app.ui.components.FreshScreen(
        title = stringResource(R.string.weekly_review_title),
        onBack = onBack,
        modifier = modifier,
        backContentDescription = stringResource(R.string.common_back),
        actions = {
            if (isRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            } else {
                IconButton(onClick = {
                    scope.launch {
                        isRefreshing = true
                        try {
                            load()
                        } finally {
                            isRefreshing = false
                        }
                    }
                }) {
                    Icon(Icons.AutoMirrored.Filled.Assignment, contentDescription = stringResource(R.string.weekly_review_refresh))
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            if (review == null) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                if (error == null) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            }
        } else {
            val r = review!!
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Summary header
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.weekly_review_summary_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                dateFormat.format(Date(r.date)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                SummaryBadge(stringResource(R.string.weekly_review_badge_decisions), r.confirmedDecisions.size, Icons.Default.CheckCircle)
                                SummaryBadge(stringResource(R.string.weekly_review_badge_commitments), r.newCommitments.size, Icons.Default.Commit)
                                SummaryBadge(stringResource(R.string.weekly_review_badge_overdue), r.overdueCommitments.size, Icons.Default.Warning)
                            }
                        }
                    }
                }

                // Confirmed Decisions
                if (r.confirmedDecisions.isNotEmpty()) {
                    item {
                        CollapsibleSection(
                            title = stringResource(R.string.weekly_review_section_confirmed_decisions),
                            count = r.confirmedDecisions.size,
                            expanded = expandedSections.contains("decisions"),
                            onToggle = {
                                expandedSections = if (expandedSections.contains("decisions")) {
                                    expandedSections - "decisions"
                                } else {
                                    expandedSections + "decisions"
                                }
                            }
                        )
                    }
                    if (expandedSections.contains("decisions")) {
                        items(r.confirmedDecisions, key = { it.id }) { decision ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        decision.statement,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    decision.reason?.let { reason ->
                                        Text(
                                            reason,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                    }
                                    decision.decidedAt?.let { date ->
                                        Text(
                                            stringResource(R.string.weekly_review_decided_prefix, dateFormat.format(Date(date))),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // New Commitments
                if (r.newCommitments.isNotEmpty()) {
                    item {
                        CollapsibleSection(
                            title = stringResource(R.string.weekly_review_section_new_commitments),
                            count = r.newCommitments.size,
                            expanded = expandedSections.contains("commitments"),
                            onToggle = {
                                expandedSections = if (expandedSections.contains("commitments")) {
                                    expandedSections - "commitments"
                                } else {
                                    expandedSections + "commitments"
                                }
                            }
                        )
                    }
                    if (expandedSections.contains("commitments")) {
                        items(r.newCommitments, key = { it.id }) { commitment ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        commitment.action,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    commitment.dueAt?.let { due ->
                                        Text(
                                            stringResource(R.string.weekly_review_due_prefix, dateFormat.format(Date(due))),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (due < System.currentTimeMillis()) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Overdue Commitments
                if (r.overdueCommitments.isNotEmpty()) {
                    item {
                        CollapsibleSection(
                            title = stringResource(R.string.weekly_review_section_overdue),
                            count = r.overdueCommitments.size,
                            expanded = expandedSections.contains("overdue"),
                            onToggle = {
                                expandedSections = if (expandedSections.contains("overdue")) {
                                    expandedSections - "overdue"
                                } else {
                                    expandedSections + "overdue"
                                }
                            },
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (expandedSections.contains("overdue")) {
                        items(r.overdueCommitments, key = { it.id }) { commitment ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        commitment.action,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    commitment.dueAt?.let { due ->
                                        Text(
                                            stringResource(R.string.weekly_review_due_prefix, dateFormat.format(Date(due))),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Pending Reviews
                if (r.pendingReviews.isNotEmpty()) {
                    item {
                        CollapsibleSection(
                            title = stringResource(R.string.weekly_review_section_pending_reviews),
                            count = r.pendingReviews.size,
                            expanded = expandedSections.contains("reviews"),
                            onToggle = {
                                expandedSections = if (expandedSections.contains("reviews")) {
                                    expandedSections - "reviews"
                                } else {
                                    expandedSections + "reviews"
                                }
                            }
                        )
                    }
                    if (expandedSections.contains("reviews")) {
                        items(r.pendingReviews, key = { it.id }) { reviewItem ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        "${reviewItem.type.name}: ${reviewItem.reason}",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }

                // Open Questions
                if (r.openQuestions.isNotEmpty()) {
                    item {
                        CollapsibleSection(
                            title = stringResource(R.string.weekly_review_section_open_questions),
                            count = r.openQuestions.size,
                            expanded = expandedSections.contains("questions"),
                            onToggle = {
                                expandedSections = if (expandedSections.contains("questions")) {
                                    expandedSections - "questions"
                                } else {
                                    expandedSections + "questions"
                                }
                            }
                        )
                    }
                    if (expandedSections.contains("questions")) {
                        items(r.openQuestions, key = { it.id }) { question ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        question.statement,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }

                // Pending Conflicts
                if (r.pendingConflicts.isNotEmpty()) {
                    item {
                        CollapsibleSection(
                            title = stringResource(R.string.weekly_review_section_possible_conflicts),
                            count = r.pendingConflicts.size,
                            expanded = expandedSections.contains("conflicts"),
                            onToggle = {
                                expandedSections = if (expandedSections.contains("conflicts")) {
                                    expandedSections - "conflicts"
                                } else {
                                    expandedSections + "conflicts"
                                }
                            },
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (expandedSections.contains("conflicts")) {
                        items(r.pendingConflicts, key = { it.id }) { conflict ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        conflict.conflictType.replace("_", " "),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }

                // Unconfirmed entities count
                if (r.unconfirmedEntities > 0) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AssistChip(
                                    onClick = {},
                                    label = { Text(stringResource(R.string.weekly_review_unconfirmed_chip, r.unconfirmedEntities)) },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.weekly_review_unconfirmed_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }

                // Empty state
                if (r.confirmedDecisions.isEmpty() && r.newCommitments.isEmpty() &&
                    r.overdueCommitments.isEmpty() && r.pendingReviews.isEmpty() &&
                    r.openQuestions.isEmpty() && r.pendingConflicts.isEmpty()
                ) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    stringResource(R.string.weekly_review_empty_title),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                                Text(
                                    stringResource(R.string.weekly_review_empty_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
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


@Composable
private fun SummaryBadge(label: String, count: Int, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            "$count $label",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun CollapsibleSection(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(8.dp))
            AssistChip(
                onClick = {},
                label = { Text("$count", style = MaterialTheme.typography.labelSmall) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = color.copy(alpha = 0.15f)
                )
            )
        }
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand)
        )
    }
}
