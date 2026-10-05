package com.noteflowai.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.noteflowai.app.R
import com.noteflowai.app.data.noteDisplayTitle
import com.noteflowai.app.data.temporal.TemporalIndex
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Composition is single-threaded, so a shared non-thread-safe formatter is safe here.
private val EVOLUTION_DATE_FORMAT = SimpleDateFormat("MMM d, yyyy", Locale.US)

/**
 * Shows how a concept evolved across notes over time.
 * Displays as a timeline with timestamps and context snippets.
 */
@Composable
fun IdeaEvolutionPanel(
    evolution: TemporalIndex.ConceptEvolution,
    onNoteClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Timeline,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.idea_evolution_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                stringResource(R.string.idea_evolution_notes_span, evolution.noteCount, (evolution.lastSeen - evolution.firstSeen) / (1000 * 60 * 60 * 24)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Timeline
            LazyColumn(
                modifier = Modifier.heightIn(max = 200.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(evolution.appearances, key = { _, appearance -> "${appearance.noteFileName}@${appearance.timestamp}" }) { index, appearance ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNoteClick(appearance.noteFileName) },
                        verticalAlignment = Alignment.Top
                    ) {
                        // Timeline dot + line
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(24.dp)
                        ) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = if (index == evolution.appearances.lastIndex)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(8.dp)
                            ) {}
                            if (index < evolution.appearances.lastIndex) {
                                Surface(
                                    modifier = Modifier
                                        .width(2.dp)
                                        .height(16.dp),
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                ) {}
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                SimpleDateFormat("MMM d, yyyy", Locale.US)
                                    .format(Date(appearance.timestamp)),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                appearance.noteFileName.noteDisplayTitle(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { onNoteClick(appearance.noteFileName) }
                            )
                            Text(
                                appearance.context.take(100),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                maxLines = 2
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun formatTimeRange(first: Long, last: Long): String {
    val diffMs = last - first
    val days = diffMs / (1000 * 60 * 60 * 24)
    return when {
        days < 1 -> stringResource(R.string.idea_evolution_today)
        days < 7 -> stringResource(R.string.idea_evolution_days, days)
        days < 30 -> stringResource(R.string.idea_evolution_weeks, days / 7)
        days < 365 -> stringResource(R.string.idea_evolution_months, days / 30)
        else -> stringResource(R.string.idea_evolution_years, days / 365)
    }
}
