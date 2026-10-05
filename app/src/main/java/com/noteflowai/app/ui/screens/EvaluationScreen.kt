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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noteflowai.app.R
import com.noteflowai.app.data.memory.eval.EvaluationStatsService
import com.noteflowai.app.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Phase 8d: Evaluation dashboard for the Personal Memory Layer.
 *
 * Surfaces metrics aggregated from persisted data by [EvaluationStatsService]:
 * pipeline health, citation validity, and an extraction-precision proxy,
 * plus an explicit limitations card for metrics that require an external
 * evaluation dataset. Mirrors the [WeeklyReviewScreen] layout conventions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EvaluationScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val service = remember { EvaluationStatsService(viewModel.getApplication()) }
    val memoryRebuild by viewModel.memoryRebuild.collectAsStateWithLifecycle()

    var snapshot by remember { mutableStateOf<EvaluationStatsService.EvaluationSnapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        error = null
        try {
            snapshot = service.buildSnapshot()
        } catch (e: Exception) {
            error = e.message ?: "Failed to load evaluation stats"
        }
    }

    LaunchedEffect(Unit) {
        load()
    }

    var prevRebuildRunning by remember { mutableStateOf(memoryRebuild.isRunning) }
    LaunchedEffect(memoryRebuild.isRunning) {
        if (prevRebuildRunning && !memoryRebuild.isRunning) {
            load()
        }
        prevRebuildRunning = memoryRebuild.isRunning
    }

    com.noteflowai.app.ui.components.FreshScreen(
        title = stringResource(R.string.evaluation_title),
        onBack = onBack,
        modifier = modifier,
        backContentDescription = stringResource(R.string.common_back),
        actions = {
            IconButton(onClick = {
                scope.launch { load() }
            }) {
                Icon(Icons.Default.Info, contentDescription = stringResource(R.string.evaluation_refresh))
            }
        }
    ) { padding ->
        val s = snapshot
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
        when {
            s == null -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            s.isEmpty -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
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
                            stringResource(R.string.evaluation_empty_title),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                        stringResource(R.string.evaluation_empty_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { viewModel.rebuildMemoryFromNotes() },
                            enabled = !memoryRebuild.isRunning
                        ) {
                            Text(stringResource(R.string.evaluation_empty_rebuild))
                        }
                        val rebuildMessage = memoryRebuild.message
                        if (memoryRebuild.isRunning) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.rebuild_progress, memoryRebuild.done, memoryRebuild.total),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        } else if (rebuildMessage != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                rebuildMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item { PipelineHealthCard(s) }
                    item { CitationValidityCard(s) }
                    item { ExtractionPrecisionCard(s) }
                    item { SystemVolumeCard(s) }
                    item { LimitationsCard() }
                }
            }
        }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String, accent: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (accent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun PipelineHealthCard(s: EvaluationStatsService.EvaluationSnapshot) {
    SectionCard(stringResource(R.string.evaluation_section_pipeline)) {
        MetricRow(
            stringResource(R.string.evaluation_pipeline_total),
            s.pipelineTotalSources.toString()
        )
        MetricRow(
            stringResource(R.string.evaluation_pipeline_completion_rate),
            String.format(Locale.getDefault(), "%.0f%%", s.pipelineCompletionRate * 100f)
        )
        MetricRow(stringResource(R.string.evaluation_pipeline_completed), s.pipelineCompleted.toString())
        MetricRow(
            stringResource(R.string.evaluation_pipeline_failed_permanent),
            s.pipelineFailedPermanent.toString(),
            accent = s.pipelineFailedPermanent > 0
        )
        MetricRow(
            stringResource(R.string.evaluation_pipeline_failed_retryable),
            s.pipelineFailedRetryable.toString(),
            accent = s.pipelineFailedRetryable > 0
        )
        MetricRow(stringResource(R.string.evaluation_pipeline_pending), s.pipelinePendingRunning.toString())
        MetricRow(stringResource(R.string.evaluation_pipeline_cancelled), s.pipelineCancelled.toString())
        MetricRow(
            stringResource(R.string.evaluation_pipeline_avg_attempts),
            String.format(Locale.getDefault(), "%.2f", s.pipelineAvgAttempts)
        )
        MetricRow(
            stringResource(R.string.evaluation_pipeline_retried),
            s.pipelineRetriedCount.toString()
        )
        MetricRow(
            stringResource(R.string.evaluation_pipeline_stuck),
            s.pipelineStuckCount.toString(),
            accent = s.pipelineStuckCount > 0
        )
    }
}

@Composable
private fun CitationValidityCard(s: EvaluationStatsService.EvaluationSnapshot) {
    SectionCard(stringResource(R.string.evaluation_section_citations)) {
        MetricRow(
            stringResource(R.string.evaluation_citation_validity_rate),
            String.format(Locale.getDefault(), "%.0f%%", s.citationValidityRate * 100f)
        )
        MetricRow(stringResource(R.string.evaluation_citation_total), s.citationTotal.toString())
        MetricRow(stringResource(R.string.evaluation_citation_validated), s.citationValidated.toString())
        MetricRow(
            stringResource(R.string.evaluation_citation_invalid),
            s.citationInvalid.toString(),
            accent = s.citationInvalid > 0
        )
        MetricRow(stringResource(R.string.evaluation_citation_answers), s.citationDistinctAnswers.toString())
        if (s.citationByLocationType.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.evaluation_citation_by_source),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            // Sorted once per stats emission, not per recomposition.
            val sortedCitations = remember(s.citationByLocationType) {
                s.citationByLocationType.entries.sortedByDescending { it.value }
            }
            sortedCitations.forEach { (location, count) ->
                MetricRow(location.replace("_", " ").lowercase(Locale.getDefault()), count.toString())
            }
        }
    }
}

@Composable
private fun ExtractionPrecisionCard(s: EvaluationStatsService.EvaluationSnapshot) {
    SectionCard(stringResource(R.string.evaluation_section_extraction)) {
        MetricRow(
            stringResource(R.string.evaluation_review_confirmation_rate),
            String.format(Locale.getDefault(), "%.0f%%", s.reviewConfirmationRate * 100f)
        )
        MetricRow(stringResource(R.string.evaluation_review_pending), s.reviewPending.toString())
        MetricRow(stringResource(R.string.evaluation_review_accepted), s.reviewAccepted.toString())
        MetricRow(stringResource(R.string.evaluation_review_edited), s.reviewEdited.toString())
        MetricRow(
            stringResource(R.string.evaluation_review_ignored),
            s.reviewIgnored.toString()
        )
        MetricRow(stringResource(R.string.evaluation_memory_total), s.memoryObjectTotal.toString())
        MetricRow(stringResource(R.string.evaluation_memory_detected), s.memoryObjectDetected.toString())
        MetricRow(stringResource(R.string.evaluation_memory_confirmed), s.memoryObjectConfirmedActive.toString())
        MetricRow(
            stringResource(R.string.evaluation_pending_conflicts),
            s.pendingConflicts.toString(),
            accent = s.pendingConflicts > 0
        )
    }
}

@Composable
private fun SystemVolumeCard(s: EvaluationStatsService.EvaluationSnapshot) {
    SectionCard(stringResource(R.string.evaluation_section_volume)) {
        MetricRow(stringResource(R.string.evaluation_volume_segments), s.segmentCount.toString())
        MetricRow(stringResource(R.string.evaluation_volume_entities), s.entityCount.toString())
        MetricRow(stringResource(R.string.evaluation_volume_relations), s.relationCount.toString())
    }
}

@Composable
private fun LimitationsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.evaluation_limitations_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.evaluation_limitations_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
