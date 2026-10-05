package com.noteflowai.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.noteflowai.app.ui.theme.AppRadius
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.noteflowai.app.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SlideGenerateDialog(
    sourceTitle: String,
    isOnlineMode: Boolean,
    isProcessing: Boolean,
    processingStep: String,
    onDismiss: () -> Unit,
    onGenerate: (customPrompt: String, mode: String) -> Unit
) {
    var customPrompt by remember { mutableStateOf("") }
    var selectedMode by remember { mutableStateOf("simple") }

    val defaultPrompt = stringResource(R.string.slide_generate_default_prompt)

    AlertDialog(
        onDismissRequest = { if (!isProcessing) onDismiss() },
        icon = {
            Icon(
                Icons.Default.Slideshow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Column {
                Text(stringResource(R.string.slide_generate_title), fontWeight = FontWeight.Bold)
                Text(
                    text = if (isOnlineMode) stringResource(R.string.slide_generate_online) else stringResource(R.string.slide_generate_offline),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Source preview
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = stringResource(R.string.slide_generate_source, sourceTitle),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }

                // Custom prompt input
                Text(
                    text = stringResource(R.string.slide_generate_custom_label),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = customPrompt,
                    onValueChange = { customPrompt = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 150.dp),
                    placeholder = {
                        Text(
                            stringResource(R.string.slide_generate_custom_placeholder),
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    textStyle = MaterialTheme.typography.bodySmall,
                    shape = RoundedCornerShape(AppRadius.medium)
                )

                // Default prompt preview
                Text(
                    text = stringResource(R.string.slide_generate_default_prompt_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                Text(
                    text = defaultPrompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    maxLines = 3
                )

                // Mode selection
                Text(
                    text = stringResource(R.string.slide_generate_mode_label),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedMode == "simple",
                        onClick = { selectedMode = "simple" },
                        label = { Text(stringResource(R.string.slide_generate_mode_simple)) }
                    )
                    FilterChip(
                        selected = selectedMode == "rich",
                        onClick = { selectedMode = "rich" },
                        label = { Text(stringResource(R.string.slide_generate_mode_rich)) }
                    )
                }
                Text(
                    text = if (selectedMode == "simple") stringResource(R.string.slide_generate_mode_simple_desc) else stringResource(R.string.slide_generate_mode_rich_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val fullPrompt = if (customPrompt.isNotBlank()) {
                        "$customPrompt\n\n$defaultPrompt"
                    } else {
                        defaultPrompt
                    }
                    onGenerate(fullPrompt, selectedMode)
                },
                enabled = !isProcessing
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(processingStep.ifEmpty { stringResource(R.string.slide_generate_processing) })
                } else {
                    Icon(Icons.Default.Slideshow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.slide_generate_button))
                }
            }
        },
        dismissButton = {
            if (!isProcessing) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.slide_generate_cancel))
                }
            }
        }
    )
}
