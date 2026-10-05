package com.noteflowai.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.noteflowai.app.R
import com.noteflowai.app.data.search.SearchExplainer

/**
 * Shows why a search result was retrieved.
 * Displays reasoning chain and contributing factors.
 */
@Composable
fun SearchExplanationDialog(
    explanation: SearchExplainer.SearchExplanation,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Default.Lightbulb, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        title = {
            Text(stringResource(R.string.search_explanation_title), fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(
                    "\"${explanation.noteTitle}\"",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    stringResource(R.string.search_explanation_relevance, (explanation.overallScore * 100).toInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(stringResource(R.string.search_explanation_retrieved_because), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)

                Spacer(modifier = Modifier.height(4.dp))

                explanation.reasons.forEach { reason ->
                    Row(
                        modifier = Modifier.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                            modifier = Modifier.size(6.dp)
                        ) {}
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            reason.detail,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                if (explanation.concepts.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.search_explanation_shared_concepts), style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        explanation.concepts.take(5).forEach { concept ->
                            SuggestionChip(
                                onClick = {},
                                label = { Text(concept, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_ok))
            }
        }
    )
}
