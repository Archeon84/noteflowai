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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Surface
import androidx.compose.ui.text.style.TextOverflow
import com.noteflowai.app.data.memory.analysis.ConflictDetectionService
import com.noteflowai.app.data.memory.model.Conflict
import com.noteflowai.app.data.memory.model.ConflictStatus
import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.repository.ConflictRepository
import com.noteflowai.app.data.noteDisplayTitle
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.res.stringResource
import com.noteflowai.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictDetectionScreen(
    conflictRepository: ConflictRepository,
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val detectionService = remember { ConflictDetectionService(context) }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    var conflicts by remember { mutableStateOf<List<Conflict>>(emptyList()) }
    var sourceSegments by remember { mutableStateOf<Map<String, SourceSegment>>(emptyMap()) }
    var isDetecting by remember { mutableStateOf(false) }
    var showResolveDialog by remember { mutableStateOf<Conflict?>(null) }
    var resolutionText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun parseSegmentIds(json: String): List<String> {
        return try {
            val array = org.json.JSONArray(json)
            val list = mutableListOf<String>()
            for (i in 0 until array.length()) {
                list.add(array.getString(i))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun reload() {
        scope.launch {
            try {
                val loaded = conflictRepository.getAll()
                conflicts = loaded
                val segIds = loaded.flatMap { parseSegmentIds(it.sourceSegmentIds) }.distinct()
                sourceSegments = conflictRepository.getSourceSegments(segIds)
            } catch (e: Exception) {
                error = e.message ?: "Failed to load conflicts"
            }
        }
    }

    // Load conflicts on start
    androidx.compose.runtime.LaunchedEffect(Unit) {
        reload()
    }

    com.noteflowai.app.ui.components.FreshScreen(
        title = stringResource(R.string.conflict_detection_title),
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
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            // Detect button
            Button(
                onClick = {
                    isDetecting = true
                    error = null
                    scope.launch {
                        try {
                            detectionService.detectConflicts()
                            reload()
                        } catch (e: Exception) {
                            error = e.message ?: "Detection failed"
                        } finally {
                            isDetecting = false
                        }
                    }
                },
                enabled = !isDetecting,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isDetecting) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.padding(start = 8.dp))
                }
                Text(stringResource(R.string.conflict_detection_detect))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Conflict list grouped by status
            val pendingConflicts = conflicts.filter { it.status == ConflictStatus.PENDING }
            val otherConflicts = conflicts.filter { it.status != ConflictStatus.PENDING }

            if (pendingConflicts.isEmpty() && otherConflicts.isEmpty() && !isDetecting) {
                // Empty state
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.conflict_detection_empty_title),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            stringResource(R.string.conflict_detection_empty_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Pending conflicts section
                    if (pendingConflicts.isEmpty() && otherConflicts.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.conflict_detection_no_pending),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }
                    if (pendingConflicts.isNotEmpty()) {
                        item {
                            SectionHeader(
                                title = stringResource(R.string.conflict_detection_section_pending),
                                count = pendingConflicts.size,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        items(pendingConflicts, key = { it.id }) { conflict ->
                            ConflictCard(
                                conflict = conflict,
                                segments = parseSegmentIds(conflict.sourceSegmentIds).mapNotNull { sourceSegments[it] },
                                dateFormat = dateFormat,
                                onOpenNote = onOpenNote,
                                onConfirm = {
                                    scope.launch {
                                        try {
                                            detectionService.confirmConflict(conflict.id)
                                            reload()
                                        } catch (e: Exception) {
                                            error = e.message ?: "Confirm failed"
                                        }
                                    }
                                },
                                onDismiss = {
                                    scope.launch {
                                        try {
                                            detectionService.dismissConflict(conflict.id)
                                            reload()
                                        } catch (e: Exception) {
                                            error = e.message ?: "Dismiss failed"
                                        }
                                    }
                                },
                                onResolve = {
                                    showResolveDialog = conflict
                                }
                            )
                        }
                    }

                    // Other conflicts section
                    if (otherConflicts.isNotEmpty()) {
                        item {
                            SectionHeader(
                                title = stringResource(R.string.conflict_detection_section_resolved),
                                count = otherConflicts.size,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        items(otherConflicts, key = { it.id }) { conflict ->
                            ConflictCard(
                                conflict = conflict,
                                segments = parseSegmentIds(conflict.sourceSegmentIds).mapNotNull { sourceSegments[it] },
                                dateFormat = dateFormat,
                                onOpenNote = onOpenNote,
                                onConfirm = {},
                                onDismiss = {},
                                onResolve = {},
                                isResolved = true
                            )
                        }
                    }
                }
            }
        }
    }

    // Resolve dialog
    if (showResolveDialog != null) {
        AlertDialog(
            onDismissRequest = {
                showResolveDialog = null
                resolutionText = ""
            },
            title = { Text(stringResource(R.string.conflict_detection_resolve_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.conflict_detection_resolve_body),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = resolutionText,
                        onValueChange = { resolutionText = it },
                        label = { Text(stringResource(R.string.conflict_detection_resolve_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Snapshot before launch: the dialog can be dismissed
                        // while the coroutine is still starting.
                        val target = showResolveDialog ?: return@TextButton
                        val resolution = resolutionText
                        scope.launch {
                            try {
                                detectionService.resolveConflict(target.id, resolution)
                                reload()
                                showResolveDialog = null
                                resolutionText = ""
                            } catch (e: Exception) {
                                error = e.message ?: "Resolve failed"
                            }
                        }
                    },
                    enabled = resolutionText.isNotBlank()
                ) {
                    Text(stringResource(R.string.conflict_detection_resolve_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showResolveDialog = null
                    resolutionText = ""
                }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String, count: Int, color: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        AssistChip(
            onClick = {},
            label = { Text("$count", style = MaterialTheme.typography.labelSmall) },
            colors = AssistChipDefaults.assistChipColors(containerColor = color.copy(alpha = 0.15f))
        )
    }
}

@Composable
private fun ConflictCard(
    conflict: Conflict,
    segments: List<SourceSegment> = emptyList(),
    dateFormat: SimpleDateFormat,
    onOpenNote: (String) -> Unit = {},
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onResolve: () -> Unit,
    isResolved: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isResolved) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    conflict.conflictType.replace("_", " "),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            conflict.status.name,
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = when (conflict.status) {
                            ConflictStatus.PENDING -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                            ConflictStatus.CONFIRMED -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            ConflictStatus.RESOLVED -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                            ConflictStatus.DISMISSED -> MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                        }
                    )
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                stringResource(R.string.conflict_detection_confidence, (conflict.confidence * 100).toInt()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )

            Text(
                "${dateFormat.format(Date(conflict.firstObservedAt))} - ${dateFormat.format(Date(conflict.latestObservedAt))}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )

            if (segments.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.conflict_detection_contrasting_evidence),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    segments.forEach { segment ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(
                                    text = "“${segment.text.trim()}”",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                AssistChip(
                                    onClick = { onOpenNote(segment.sourceId) },
                                    label = {
                                        Text(
                                            segment.sourceId.noteDisplayTitle(),
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
                                    modifier = Modifier.height(28.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (conflict.resolution != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.conflict_detection_resolution_prefix, conflict.resolution ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            if (!isResolved) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onConfirm) {
                        Text(stringResource(R.string.conflict_detection_confirm))
                    }
                    OutlinedButton(onClick = onDismiss) {
                        Text(stringResource(R.string.conflict_detection_dismiss))
                    }
                    Button(
                        onClick = onResolve,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary
                        )
                    ) {
                        Text(stringResource(R.string.conflict_detection_resolve_confirm))
                    }
                }
            }
        }
    }
}
