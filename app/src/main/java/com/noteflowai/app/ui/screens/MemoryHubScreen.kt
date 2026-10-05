package com.noteflowai.app.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Reviews
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noteflowai.app.R
import com.noteflowai.app.ui.Screen
import com.noteflowai.app.ui.components.FreshScreen
import com.noteflowai.app.ui.theme.AppRadius
import com.noteflowai.app.ui.theme.AppSpacing
import com.noteflowai.app.viewmodel.MainViewModel
import com.noteflowai.app.viewmodel.MemoryRebuildState

/**
 * Memory hub — a modernized, categorized, human-friendly entry point for every insight
 * the personal memory layer produces.
 *
 * Organized into goal-oriented sections:
 * - Today's Highlights (Daily Briefing hero card)
 * - Routines & Recaps (Weekly Review, Note Timeline)
 * - Action & Intelligence (Review Inbox, Action Items, Decisions, Contradictions, Evolution)
 * - Explore & Connect (Topic Map / Knowledge Graph)
 * - Privacy & System (Privacy, AI Accuracy, Rebuild Memory)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryHubScreen(
    viewModel: MainViewModel,
    onNavigate: (Screen) -> Unit,
    onBack: () -> Unit
) {
    val decisionTimelineEnabled by viewModel.decisionTimelineEnabled.collectAsStateWithLifecycle()
    val commitmentDashboardEnabled by viewModel.commitmentDashboardEnabled.collectAsStateWithLifecycle()
    val changeAnalysisEnabled by viewModel.changeAnalysisEnabled.collectAsStateWithLifecycle()
    val conflictDetectionEnabled by viewModel.conflictDetectionEnabled.collectAsStateWithLifecycle()
    val weeklyReviewEnabled by viewModel.weeklyReviewEnabled.collectAsStateWithLifecycle()
    val evaluationEnabled by viewModel.evaluationEnabled.collectAsStateWithLifecycle()
    val dailyDigestEnabled by viewModel.dailyDigestEnabled.collectAsStateWithLifecycle()
    val memoryRebuild by viewModel.memoryRebuild.collectAsStateWithLifecycle()
    val pendingTimeline by viewModel.pendingTimelineCount.collectAsStateWithLifecycle()
    val pendingReview by viewModel.pendingReviewCount.collectAsStateWithLifecycle()
    val pendingConflicts by viewModel.pendingConflictCount.collectAsStateWithLifecycle()
    val activeCommitments by viewModel.activeCommitmentCount.collectAsStateWithLifecycle()

    FreshScreen(
        title = stringResource(R.string.memory_hub_title),
        onBack = onBack,
        actions = {
            IconButton(onClick = { onNavigate(Screen.SETTINGS) }) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.nav_desc_settings))
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AppSpacing.lg)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Text(
                stringResource(R.string.memory_hub_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = AppSpacing.xs)
            )

            // 1. TODAY'S HIGHLIGHTS
            if (dailyDigestEnabled) {
                SectionHeader(stringResource(R.string.memory_hub_section_highlights))
                DailyBriefingHeroCard(
                    onClick = { onNavigate(Screen.DIGEST) }
                )
            }

            // 2. ROUTINES & RECAPS
            SectionHeader(stringResource(R.string.memory_hub_section_recaps))

            if (weeklyReviewEnabled) {
                MemoryHubCard(
                    icon = Icons.Default.Reviews,
                    title = stringResource(R.string.memory_hub_weekly_review_title),
                    description = stringResource(R.string.memory_hub_weekly_review_desc),
                    iconColor = Color(0xFF6750A4),
                    onClick = { onNavigate(Screen.WEEKLY_REVIEW) }
                )
            }

            MemoryHubCard(
                icon = Icons.Default.DateRange,
                title = stringResource(R.string.memory_hub_timeline_title),
                description = stringResource(R.string.memory_hub_timeline_desc),
                iconColor = Color(0xFF00897B),
                badgeText = if (pendingTimeline > 0) stringResource(R.string.memory_hub_badge_new, pendingTimeline) else null,
                onClick = { onNavigate(Screen.TIMELINE) }
            )

            // 3. ACTION & INTELLIGENCE
            SectionHeader(stringResource(R.string.memory_hub_section_actions))

            MemoryHubCard(
                icon = Icons.Default.Inventory2,
                title = stringResource(R.string.memory_hub_inbox_friendly_title),
                description = stringResource(R.string.memory_hub_inbox_friendly_desc),
                iconColor = Color(0xFFE65100),
                badgeText = if (pendingReview > 0) stringResource(R.string.memory_hub_badge_pending, pendingReview) else null,
                badgeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                badgeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                onClick = { onNavigate(Screen.MEMORY_INBOX) }
            )

            if (commitmentDashboardEnabled) {
                MemoryHubCard(
                    icon = Icons.AutoMirrored.Filled.Assignment,
                    title = stringResource(R.string.memory_hub_commitments_friendly_title),
                    description = stringResource(R.string.memory_hub_commitments_friendly_desc),
                    iconColor = Color(0xFF2E7D32),
                    badgeText = if (activeCommitments > 0) stringResource(R.string.memory_hub_badge_active, activeCommitments) else null,
                    badgeContainerColor = Color(0xFFE8F5E9),
                    badgeContentColor = Color(0xFF1B5E20),
                    onClick = { onNavigate(Screen.COMMITMENT_DASHBOARD) }
                )
            }

            if (decisionTimelineEnabled) {
                MemoryHubCard(
                    icon = Icons.Default.Gavel,
                    title = stringResource(R.string.memory_hub_decisions_friendly_title),
                    description = stringResource(R.string.memory_hub_decisions_friendly_desc),
                    iconColor = Color(0xFF1565C0),
                    onClick = { onNavigate(Screen.DECISION_TIMELINE) }
                )
            }

            if (conflictDetectionEnabled) {
                MemoryHubCard(
                    icon = Icons.Default.Warning,
                    title = stringResource(R.string.memory_hub_conflicts_friendly_title),
                    description = stringResource(R.string.memory_hub_conflicts_friendly_desc),
                    iconColor = Color(0xFFD32F2F),
                    badgeText = if (pendingConflicts > 0) stringResource(R.string.memory_hub_badge_conflicts, pendingConflicts) else null,
                    badgeContainerColor = MaterialTheme.colorScheme.errorContainer,
                    badgeContentColor = MaterialTheme.colorScheme.onErrorContainer,
                    onClick = { onNavigate(Screen.CONFLICT_DETECTION) }
                )
            }

            if (changeAnalysisEnabled) {
                MemoryHubCard(
                    icon = Icons.Default.Timeline,
                    title = stringResource(R.string.memory_hub_evolution_friendly_title),
                    description = stringResource(R.string.memory_hub_evolution_friendly_desc),
                    iconColor = Color(0xFF8E24AA),
                    onClick = { onNavigate(Screen.CHANGE_ANALYSIS) }
                )
            }

            // 4. EXPLORE & CONNECT
            SectionHeader(stringResource(R.string.memory_hub_section_explore))

            MemoryHubCard(
                icon = Icons.Default.AccountTree,
                title = stringResource(R.string.memory_hub_graph_friendly_title),
                description = stringResource(R.string.memory_hub_graph_friendly_desc),
                iconColor = Color(0xFF0288D1),
                onClick = { onNavigate(Screen.GRAPH) }
            )

            // 5. PRIVACY & SYSTEM
            SectionHeader(stringResource(R.string.memory_hub_section_system))

            MemoryHubCard(
                icon = Icons.Default.Security,
                title = stringResource(R.string.memory_hub_privacy_friendly_title),
                description = stringResource(R.string.memory_hub_privacy_friendly_desc),
                iconColor = Color(0xFF455A64),
                onClick = { onNavigate(Screen.PRIVACY_DASHBOARD) }
            )

            if (evaluationEnabled) {
                MemoryHubCard(
                    icon = Icons.Default.Insights,
                    title = stringResource(R.string.memory_hub_evaluation_friendly_title),
                    description = stringResource(R.string.memory_hub_evaluation_friendly_desc),
                    iconColor = Color(0xFFF57C00),
                    onClick = { onNavigate(Screen.EVALUATION) }
                )
            }

            Spacer(modifier = Modifier.height(AppSpacing.xs))
            MemoryRebuildCard(
                rebuildState = memoryRebuild,
                onClick = { viewModel.rebuildMemoryFromNotes() },
                onCancel = { viewModel.cancelMemoryRebuild() },
                pendingTimeline = pendingTimeline
            )
            Spacer(modifier = Modifier.height(AppSpacing.lg))
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = AppSpacing.sm, bottom = 2.dp)
    )
}

@Composable
private fun DailyBriefingHeroCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.medium),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.lg)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = RoundedCornerShape(AppRadius.medium),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Assignment,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(AppSpacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.memory_hub_digest_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.memory_hub_digest_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(AppSpacing.md))
            Button(
                onClick = onClick,
                modifier = Modifier.align(Alignment.End),
                shape = RoundedCornerShape(AppRadius.medium)
            ) {
                Text(stringResource(R.string.memory_hub_digest_action))
                Spacer(modifier = Modifier.width(AppSpacing.xs))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun MemoryHubCard(
    icon: ImageVector,
    title: String,
    description: String,
    iconColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badgeText: String? = null,
    badgeContainerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    badgeContentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.medium),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(AppRadius.medium),
                color = iconColor.copy(alpha = 0.14f),
                modifier = Modifier.size(44.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(AppSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (badgeText != null) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = badgeContainerColor
                        ) {
                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = badgeContentColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            Spacer(modifier = Modifier.width(AppSpacing.sm))
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun MemoryRebuildCard(
    rebuildState: MemoryRebuildState,
    onClick: () -> Unit,
    onCancel: () -> Unit,
    pendingTimeline: Int,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.medium),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(AppRadius.medium),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            Icons.Default.RestartAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(AppSpacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.memory_hub_rebuild_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.memory_hub_rebuild_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(AppSpacing.sm))
            val rebuildMessage = rebuildState.message
            when {
                rebuildState.isRunning -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(AppSpacing.lg),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.md))
                        Text(
                            stringResource(R.string.rebuild_progress, rebuildState.done, rebuildState.total),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onCancel) {
                            Text(stringResource(R.string.rebuild_cancel))
                        }
                    }
                }
                rebuildMessage != null -> {
                    Text(
                        rebuildMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    if (rebuildState.notesProcessed > 0) {
                        Spacer(modifier = Modifier.height(AppSpacing.xs))
                        Text(
                            stringResource(R.string.rebuild_report_timeline) + ": ${rebuildState.timelineTotal} · " +
                                stringResource(R.string.rebuild_report_rejected_entities) + ": ${rebuildState.rejectedEntities} · " +
                                stringResource(R.string.rebuild_report_rejected_links) + ": ${rebuildState.rejectedMentions} · " +
                                stringResource(R.string.rebuild_report_backfilled) + ": ${rebuildState.backfilledSources} · " +
                                stringResource(R.string.rebuild_report_errors) + ": ${rebuildState.errorSources}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
                else -> {
                    Button(
                        onClick = onClick,
                        enabled = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(AppRadius.medium)
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(AppSpacing.sm))
                        Text(stringResource(R.string.memory_hub_rebuild_title))
                    }
                }
            }
        }
    }
}
