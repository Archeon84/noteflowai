package com.noteflowai.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noteflowai.app.R
import com.noteflowai.app.data.noteDisplayTitle
import com.noteflowai.app.data.memory.model.ConfirmationState
import com.noteflowai.app.data.memory.model.TemporalPrecision
import com.noteflowai.app.data.memory.model.TimelineEntry
import com.noteflowai.app.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Timeline review screen (guide §Phase 4).
 *
 * Lists [TimelineEntry] from the memory timeline grouped with per-row precision, confidence,
 * source note and confirm/reject actions, plus title/date editing that recomputes precision.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val entries by viewModel.timelineEntries.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(TimelineFilter.ALL) }
    var editingId by remember { mutableStateOf<String?>(null) }

    com.noteflowai.app.ui.components.FreshScreen(
        title = stringResource(R.string.timeline_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.entity_back),
        modifier = modifier
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            if (errorMessage.isNotEmpty()) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                LaunchedEffect(errorMessage) {
                    kotlinx.coroutines.delay(4000)
                    viewModel.clearError()
                }
            }
            FilterRow(filter = filter, onFilterChange = { filter = it })

            val filtered = remember(entries, filter) {
                when (filter) {
                    TimelineFilter.ALL -> entries
                    TimelineFilter.SUGGESTED -> entries.filter { it.confirmation == ConfirmationState.SUGGESTED }
                    TimelineFilter.CONFIRMED -> entries.filter { it.confirmation == ConfirmationState.CONFIRMED }
                    TimelineFilter.REJECTED -> entries.filter { it.confirmation == ConfirmationState.REJECTED }
                }
            }

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.DateRange,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.timeline_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.timeline_empty_subtitle),
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
                    items(filtered, key = { it.id }) { entry ->
                        // Stable callbacks (keyed on entry.id) so TimelineEntryCard stays skippable.
                        val onConfirm = remember(entry.id) { { viewModel.confirmTimelineEntry(entry.id) } }
                        val onReject = remember(entry.id) { { viewModel.rejectTimelineEntry(entry.id) } }
                        val onEdit = remember(entry.id) { { editingId = entry.id } }
                        if (editingId == entry.id) {
                            TimelineEntryEditor(
                                entry = entry,
                                onSave = { title, startMs, endMs ->
                                    viewModel.editTimelineEntry(entry.id, title, startMs, endMs)
                                    editingId = null
                                },
                                onDismiss = { editingId = null }
                            )
                        } else {
                            TimelineEntryCard(
                                entry = entry,
                                onConfirm = onConfirm,
                                onReject = onReject,
                                onEdit = onEdit,
                                onOpenNote = onOpenNote
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class TimelineFilter { ALL, SUGGESTED, CONFIRMED, REJECTED }

@Composable
private fun FilterRow(filter: TimelineFilter, onFilterChange: (TimelineFilter) -> Unit) {
    val labels = TimelineFilter.entries.associateWith { f ->
        stringResource(
            when (f) {
                TimelineFilter.ALL -> R.string.timeline_filter_all
                TimelineFilter.SUGGESTED -> R.string.timeline_filter_suggested
                TimelineFilter.CONFIRMED -> R.string.timeline_filter_confirmed
                TimelineFilter.REJECTED -> R.string.timeline_filter_rejected
            }
        )
    }
    com.noteflowai.app.ui.components.FreshFilterRow(
        options = labels.values.toList(),
        selected = labels[filter] ?: "",
        onSelect = { label -> labels.entries.find { it.value == label }?.let { onFilterChange(it.key) } }
    )
}

@Composable
private fun TimelineEntryCard(
    entry: TimelineEntry,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    onEdit: () -> Unit,
    onOpenNote: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = onEdit
            ),
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
                    text = entry.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                PrecisionChip(entry.precision)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = entry.startMs?.let(::formatDate)
                            ?: stringResource(R.string.timeline_edit_no_date),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    AssistChip(
                        onClick = { onOpenNote(entry.sourceNoteId) },
                        label = {
                            Text(
                                text = entry.sourceNoteId.noteDisplayTitle(),
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
                Text(
                    text = stringResource(
                        R.string.timeline_confidence,
                        (entry.confidence * 100).toInt()
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StateBadge(entry.confirmation)
                Spacer(modifier = Modifier.weight(1f))
                if (entry.confirmation != ConfirmationState.REJECTED) {
                    TextButton(onClick = onReject) {
                        Text(
                            stringResource(R.string.timeline_reject),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                if (entry.confirmation != ConfirmationState.CONFIRMED) {
                    TextButton(onClick = onConfirm) {
                        Text(stringResource(R.string.timeline_confirm))
                    }
                }
                TextButton(onClick = onEdit) {
                    Text(stringResource(R.string.timeline_edit))
                }
            }
        }
    }
}

@Composable
private fun PrecisionChip(precision: TemporalPrecision) {
    val label = stringResource(
        when (precision) {
            TemporalPrecision.EXACT -> R.string.timeline_precision_exact
            TemporalPrecision.DAY_RANGE -> R.string.timeline_precision_day_range
            TemporalPrecision.MONTH -> R.string.timeline_precision_month
            TemporalPrecision.YEAR -> R.string.timeline_precision_year
            TemporalPrecision.RELATIVE -> R.string.timeline_precision_relative
            TemporalPrecision.UNKNOWN -> R.string.timeline_precision_unknown
        }
    )
    AssistChip(onClick = {}, label = { Text(label) }, modifier = Modifier.height(28.dp))
}

@Composable
private fun StateBadge(confirmation: ConfirmationState) {
    val (text, color) = when (confirmation) {
        ConfirmationState.CONFIRMED -> stringResource(R.string.entity_confirmed) to MaterialTheme.colorScheme.primary
        ConfirmationState.REJECTED -> stringResource(R.string.entity_rejected) to MaterialTheme.colorScheme.error
        ConfirmationState.MERGED -> stringResource(R.string.entity_merged) to MaterialTheme.colorScheme.tertiary
        ConfirmationState.SUGGESTED -> stringResource(R.string.entity_suggested) to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimelineEntryEditor(
    entry: TimelineEntry,
    onSave: (String, Long?, Long?) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember(entry.id) { mutableStateOf(entry.title) }
    var startStr by remember(entry.id) {
        mutableStateOf(entry.startMs?.let { formatIsoDate(it) } ?: "")
    }
    var endStr by remember(entry.id) {
        mutableStateOf(entry.endMs?.let { formatIsoDate(it) } ?: "")
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.timeline_edit_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.timeline_edit_name_hint)) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            val startMs = parseIsoDate(startStr)
            OutlinedTextField(
                value = startStr,
                onValueChange = { startStr = it },
                label = { Text(stringResource(R.string.timeline_edit_start_hint)) },
                placeholder = { Text(stringResource(R.string.timeline_edit_no_date)) },
                isError = startStr.isNotBlank() && startMs == null,
                supportingText = {
                    if (startStr.isNotBlank() && startMs == null) {
                        Text(stringResource(R.string.timeline_edit_invalid_date))
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            val endMs = parseIsoDate(endStr)
            OutlinedTextField(
                value = endStr,
                onValueChange = { endStr = it },
                label = { Text(stringResource(R.string.timeline_edit_end_hint)) },
                placeholder = { Text(stringResource(R.string.timeline_edit_no_date)) },
                isError = endStr.isNotBlank() && endMs == null,
                supportingText = {
                    if (endStr.isNotBlank() && endMs == null) {
                        Text(stringResource(R.string.timeline_edit_invalid_date))
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.entity_back))
                }
                Spacer(modifier = Modifier.width(8.dp))
                val hasInvalidDate = (startStr.isNotBlank() && startMs == null) ||
                        (endStr.isNotBlank() && endMs == null)
                Button(onClick = {
                    if (!hasInvalidDate) {
                        onSave(title, startMs, endMs)
                    }
                }, enabled = !hasInvalidDate) {
                    Text(stringResource(R.string.timeline_edit_save))
                }
            }
        }
    }
}

// Reused formatters/zone — built once, not per-card (formatter allocation was a scroll cost).
private val TIMELINE_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val TIMELINE_ZONE = ZoneId.systemDefault()

private fun formatDate(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(TIMELINE_ZONE).format(TIMELINE_DATE_FORMATTER)

private fun formatIsoDate(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(TIMELINE_ZONE).format(DateTimeFormatter.ISO_LOCAL_DATE)

private fun parseIsoDate(str: String): Long? {
    val trimmed = str.trim()
    if (trimmed.isEmpty()) return null
    return try {
        LocalDate.parse(trimmed).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (_: Exception) {
        null
    }
}