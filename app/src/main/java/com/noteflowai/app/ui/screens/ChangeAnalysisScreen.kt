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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noteflowai.app.data.memory.analysis.ChangeAnalysisService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.res.stringResource
import com.noteflowai.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangeAnalysisScreen(
    context: android.content.Context,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val service = remember {
        ChangeAnalysisService(
            context = context,
            liteRtInferenceManager = com.noteflowai.app.data.LiteRtInferenceManager(context)
        )
    }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    var topic by remember { mutableStateOf("") }
    var isAnalyzing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ChangeAnalysisService.ChangeAnalysisResult?>(null) }
    var selectedDays by remember { mutableStateOf(30) }

    com.noteflowai.app.ui.components.FreshScreen(
        title = stringResource(R.string.change_analysis_title),
        onBack = onBack,
        modifier = modifier,
        backContentDescription = stringResource(R.string.common_back)
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Topic input
            OutlinedTextField(
                value = topic,
                onValueChange = { topic = it },
                label = { Text(stringResource(R.string.change_analysis_topic_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Date range selector
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7, 30, 90).days { days ->
                    FilterChip(
                        selected = selectedDays == days,
                        onClick = { selectedDays = days },
                        label = { Text(stringResource(R.string.change_analysis_days_label, days)) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Analyze button
            Button(
                onClick = {
                    isAnalyzing = true
                    scope.launch {
                        val now = System.currentTimeMillis()
                        val startMs = now - selectedDays * 24L * 60 * 60 * 1000
                        result = service.analyze(
                            topic = topic.ifBlank { null },
                            dateRange = Pair(startMs, now)
                        )
                        isAnalyzing = false
                    }
                },
                enabled = !isAnalyzing,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isAnalyzing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.change_analysis_analyze))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Results
            if (isAnalyzing) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.change_analysis_analyzing), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else if (result != null) {
                val analysisResult = result!!
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Interpretation
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    stringResource(R.string.change_analysis_interpretation),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    analysisResult.currentInterpretation,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }

                    // Timeline header
                    if (analysisResult.timeline.isNotEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.change_analysis_timeline_header, analysisResult.timeline.size),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }

                    // Timeline entries
                    itemsIndexed(
                        analysisResult.timeline,
                        // Stable composite key (hash collisions between same-date
                        // statements are disambiguated by statement text).
                        key = { _, item -> "${item.date}-${item.statement}" }
                    ) { _, entry ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        entry.date,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                    AssistChip(
                                        onClick = {},
                                        label = { Text(entry.changeType.replace("_", " "), style = MaterialTheme.typography.labelSmall) },
                                        colors = AssistChipDefaults.assistChipColors(
                                            containerColor = getChangeTypeColor(entry.changeType).copy(alpha = 0.15f)
                                        )
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    entry.statement,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (entry.confidence > 0) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        stringResource(R.string.change_analysis_confidence, (entry.confidence * 100).toInt()),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }
                    }

                    // Empty state
                    if (analysisResult.timeline.isEmpty() && !isAnalyzing) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Default.Timeline,
                                        contentDescription = null,
                                        modifier = Modifier.size(64.dp),
                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        stringResource(R.string.change_analysis_empty_title),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                    Text(
                                        stringResource(R.string.change_analysis_empty_subtitle),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // Initial empty state
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Analytics,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.change_analysis_initial_title),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            stringResource(R.string.change_analysis_initial_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun getChangeTypeColor(changeType: String): androidx.compose.ui.graphics.Color {
    return when (changeType) {
        "INITIAL_VIEW" -> MaterialTheme.colorScheme.primary
        "CONCERN" -> MaterialTheme.colorScheme.error
        "CLARIFICATION" -> MaterialTheme.colorScheme.tertiary
        "NEW_DECISION" -> MaterialTheme.colorScheme.primary
        "POSSIBLE_CHANGE" -> MaterialTheme.colorScheme.secondary
        "DIRECT_REVERSAL" -> MaterialTheme.colorScheme.error
        "DEADLINE_CHANGE" -> MaterialTheme.colorScheme.tertiary
        "STATUS_CHANGE" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outline
    }
}

private inline fun <T> Array<T>.days(block: (Int) -> T): List<T> {
    return this.map { block(it as Int) }
}

// Helper extension for the date range chips
@Composable
private fun List<Int>.days(block: @Composable (Int) -> Unit) {
    this.forEach { block(it) }
}
