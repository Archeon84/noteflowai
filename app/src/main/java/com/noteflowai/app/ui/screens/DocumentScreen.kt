package com.noteflowai.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noteflowai.app.R
import com.noteflowai.app.data.document.DocumentRepository
import com.noteflowai.app.ui.theme.AppSpacing
import com.noteflowai.app.ui.components.EmptyState
import com.noteflowai.app.ui.components.ErrorRetryCard
import com.noteflowai.app.ui.components.DocumentSkeleton
import com.noteflowai.app.ui.components.ListSkeleton
import com.noteflowai.app.viewmodel.DocumentViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(
    viewModel: DocumentViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboardManager = @Suppress("DEPRECATION") LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val mimeType = context.contentResolver.getType(it) ?: ""
            val supportedMimeTypes = DocumentRepository.getSupportedMimeTypes()
            if (mimeType in supportedMimeTypes) {
                viewModel.processDocument(it, mimeType)
            } else {
                Toast.makeText(
                    context,
                    context.getString(R.string.document_unsupported_format),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    com.noteflowai.app.ui.components.FreshScreen(
        title = stringResource(R.string.document_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.document_desc_back),
        actions = {
            if (uiState.hasText) {
                IconButton(onClick = { viewModel.clearAll() }) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.document_desc_refresh))
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = AppSpacing.lg)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(AppSpacing.xs))

                // Loading indicator
                if (uiState.isLoading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.sm))
                        Text(
                            stringResource(R.string.document_extracting),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }

                // Error
                if (uiState.error.isNotEmpty()) {
                    ErrorRetryCard(
                        message = uiState.error,
                        onRetry = {
                            viewModel.clearError()
                            filePickerLauncher.launch("*/*")
                        }
                    )
                }

                // Loading skeleton
                if (uiState.isLoading) {
                    DocumentSkeleton()
                }

                // File info card
                if (uiState.metadata != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(AppSpacing.md)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Description,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(AppSpacing.sm))
                                Text(
                                    uiState.metadata!!.fileName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Spacer(modifier = Modifier.height(AppSpacing.xs))
                            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                                Surface(
                                    shape = AssistChipDefaults.shape,
                                    color = AssistChipDefaults.assistChipColors().containerColor
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                                    ) {
                                        Icon(
                                            when (uiState.mimeType) {
                                                DocumentRepository.MIME_PDF -> Icons.Default.PictureAsPdf
                                                DocumentRepository.MIME_DOCX -> Icons.Default.Description
                                                DocumentRepository.MIME_HTML -> Icons.Default.Language
                                                else -> Icons.Default.FilePresent
                                            },
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(uiState.metadata!!.format, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                Surface(
                                    shape = AssistChipDefaults.shape,
                                    color = AssistChipDefaults.assistChipColors().containerColor
                                ) {
                                    Text(
                                        stringResource(R.string.document_word_count, uiState.metadata!!.wordCount),
                                        modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = 6.dp),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }

                // Extracted text preview
                if (uiState.hasText && uiState.extractedText.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(modifier = Modifier.padding(AppSpacing.md)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.FilePresent,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(AppSpacing.sm))
                                    Text(
                                        stringResource(R.string.document_extracted_title),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        coroutineScope.launch { clipboardManager.setText(AnnotatedString(uiState.extractedText)) }
                                    },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = stringResource(R.string.document_desc_copy),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(AppSpacing.sm))
                            SelectionContainer {
                                Box(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                                    Text(
                                        uiState.extractedText,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }

                // Summary error
                if (uiState.summaryError.isNotEmpty()) {
                    ErrorRetryCard(
                        message = uiState.summaryError,
                        onRetry = { viewModel.summarizeDocument() }
                    )
                }

                // Summary loading
                if (uiState.isSummarizing) {
                    ListSkeleton(count = 2)
                }

                // Summary card
                if (uiState.summary.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(AppSpacing.md)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(AppSpacing.sm))
                                    Text(
                                        stringResource(R.string.document_summary_title),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        coroutineScope.launch { clipboardManager.setText(AnnotatedString(uiState.summary)) }
                                    },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = stringResource(R.string.document_desc_copy),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(AppSpacing.sm))
                            SelectionContainer {
                                Text(
                                    uiState.summary,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Spacer(modifier = Modifier.height(AppSpacing.sm))
                            OutlinedButton(
                                onClick = { viewModel.saveSummaryAsNote() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(AppSpacing.sm))
                                Text(stringResource(R.string.document_save_summary_button))
                            }
                        }
                    }
                }

                // Empty state
                if (!uiState.isLoading && uiState.extractedText.isEmpty()) {
                    EmptyState(
                        icon = Icons.Default.FolderOpen,
                        title = stringResource(R.string.document_title),
                        message = stringResource(R.string.document_empty_hint),
                        action = {
                            OutlinedButton(onClick = { filePickerLauncher.launch("*/*") }) {
                                Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.document_empty_action))
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(AppSpacing.lg))
            }

            // Action buttons (outside scroll)
            if (uiState.hasText && uiState.extractedText.isNotEmpty()) {
                Spacer(modifier = Modifier.height(AppSpacing.sm))
                Text(
                    stringResource(R.string.document_actions_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(AppSpacing.sm))
                HorizontalDivider(modifier = Modifier.padding(vertical = AppSpacing.xs))
                Spacer(modifier = Modifier.height(AppSpacing.sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
                ) {
                    Button(
                        onClick = { viewModel.summarizeDocument() },
                        modifier = Modifier.weight(1f),
                        enabled = !uiState.isSummarizing
                    ) {
                        if (uiState.isSummarizing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(AppSpacing.sm))
                            Text(stringResource(R.string.document_summarizing))
                        } else {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null)
                            Spacer(modifier = Modifier.width(AppSpacing.sm))
                            Text(stringResource(R.string.document_summarize_button))
                        }
                    }
                    OutlinedButton(
                        onClick = { viewModel.saveExtractedTextAsNote() },
                        modifier = Modifier.weight(1f),
                        enabled = !uiState.isSummarizing
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(modifier = Modifier.width(AppSpacing.sm))
                        Text(stringResource(R.string.document_save_text_button))
                    }
                }
            }
            Spacer(modifier = Modifier.height(AppSpacing.sm))
        }
    }
}
