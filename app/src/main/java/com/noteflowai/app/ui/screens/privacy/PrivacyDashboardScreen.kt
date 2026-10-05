package com.noteflowai.app.ui.screens.privacy

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noteflowai.app.R
import com.noteflowai.app.ui.theme.AppRadius
import com.noteflowai.app.ui.theme.AppSpacing
import com.noteflowai.app.viewmodel.BackupDriveViewModel
import com.noteflowai.app.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

import kotlinx.coroutines.withContext
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyDashboardScreen(
    viewModel: MainViewModel,
    backupDriveViewModel: BackupDriveViewModel?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val isLocalOnlyMode by viewModel.isLocalOnlyMode.collectAsStateWithLifecycle()
    val ttsCacheBytes by viewModel.ttsCacheSizeBytes.collectAsStateWithLifecycle()
    val tempFilesBytes by viewModel.tempFilesSizeBytes.collectAsStateWithLifecycle()
    val backupMessage by backupDriveViewModel?.backupMessage?.collectAsStateWithLifecycle() ?: mutableStateOf(null)

    var showPurgeDialog by remember { mutableStateOf(false) }
    var showBackupPasswordDialog by remember { mutableStateOf(false) }
    var backupPassword by remember { mutableStateOf("") }
    var showRestorePasswordDialog by remember { mutableStateOf(false) }
    var restorePassword by remember { mutableStateOf("") }
    var pendingRestoreFile by remember { mutableStateOf<File?>(null) }
    var isRestoring by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(backupMessage) {
        val msg = backupMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            backupDriveViewModel?.clearBackupMessage()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshPrivacyCacheSizes()
    }

    val restoreFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch {
                isRestoring = true
                try {
                    val inputStream = context.contentResolver.openInputStream(it)
                    val tempFile = File(context.cacheDir, "picked_restore_${System.currentTimeMillis()}.zip")
                    withContext(Dispatchers.IO) {
                        inputStream?.use { input ->
                            FileOutputStream(tempFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    pendingRestoreFile = tempFile

                    val headerBytes = ByteArray(7)
                    val isEncrypted = withContext(Dispatchers.IO) {
                        val read = tempFile.inputStream().use { fis -> fis.read(headerBytes) }
                        read == 7 && String(headerBytes, Charsets.US_ASCII) == "NFENC01"
                    }

                    if (isEncrypted) {
                        showRestorePasswordDialog = true
                    } else {
                        backupDriveViewModel?.restoreFromBackupFile(tempFile, overwrite = true, password = null)
                    }
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("Failed to read backup file: ${e.message}")
                } finally {
                    isRestoring = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            com.noteflowai.app.ui.components.FreshTopBar(
                title = stringResource(R.string.privacy_dashboard_title),
                onBack = onBack
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AppSpacing.lg)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            Text(
                text = stringResource(R.string.privacy_dashboard_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = AppSpacing.xs)
            )

            // 1. Security & Storage Status Card
            Card(
                shape = RoundedCornerShape(AppRadius.large),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(AppSpacing.md), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    Text(
                        text = "Security Invariants",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    PrivacyStatusRow(
                        icon = Icons.Default.Lock,
                        title = stringResource(R.string.privacy_encryption_status),
                        subtitle = stringResource(R.string.privacy_encryption_desc),
                        isActive = true
                    )

                    PrivacyStatusRow(
                        icon = Icons.Default.Security,
                        title = "OS Backup Protection",
                        subtitle = "Database and private preferences excluded from Android cloud & ADB backups",
                        isActive = true
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = AppSpacing.xs))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.privacy_local_only_title),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.privacy_local_only_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                        Switch(
                            checked = isLocalOnlyMode,
                            onCheckedChange = { viewModel.setLocalOnlyMode(it) }
                        )
                    }
                }
            }

            // 2. Cache & Storage Purging Card
            Card(
                shape = RoundedCornerShape(AppRadius.large),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(AppSpacing.md), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    Text(
                        text = "Caches and Temporary Storage",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("TTS Audio Cache", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(formatBytes(ttsCacheBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.clearTtsCache { success ->
                                    Toast.makeText(
                                        context,
                                        context.getString(
                                            if (success) R.string.privacy_tts_cleared
                                            else R.string.privacy_tts_clear_failed
                                        ),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        ) {
                            Text(stringResource(R.string.privacy_clear_tts))
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Temporary Files", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(formatBytes(tempFilesBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.clearTempFiles { success ->
                                    Toast.makeText(
                                        context,
                                        context.getString(
                                            if (success) R.string.privacy_temp_cleared
                                            else R.string.privacy_temp_clear_failed
                                        ),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        ) {
                            Text(stringResource(R.string.privacy_clear_temp))
                        }
                    }
                }
            }

            // 3. Data Lifecycle & Backup Card
            Card(
                shape = RoundedCornerShape(AppRadius.large),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(AppSpacing.md), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    Text(
                        text = "Data Lifecycle & Encrypted Backup",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Button(
                        onClick = { showBackupPasswordDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isRestoring
                    ) {
                        Icon(Icons.Default.EnhancedEncryption, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(AppSpacing.xs))
                        Text(stringResource(R.string.privacy_create_encrypted_backup))
                    }

                    OutlinedButton(
                        onClick = {
                            restoreFilePickerLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                        },
                        enabled = !isRestoring,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(AppSpacing.xs))
                        Text("Restore Backup Archive")
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = AppSpacing.xs))

                    OutlinedButton(
                        onClick = { showPurgeDialog = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(AppSpacing.xs))
                        Text(stringResource(R.string.privacy_purge_derived_title))
                    }
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.lg))
        }
    }

    // Purge Derived Data Confirmation Dialog
    if (showPurgeDialog) {
        AlertDialog(
            onDismissRequest = { showPurgeDialog = false },
            title = { Text(stringResource(R.string.privacy_purge_derived_title)) },
            text = { Text(stringResource(R.string.privacy_purge_derived_desc)) },
            confirmButton = {
                Button(
                    onClick = {
                        showPurgeDialog = false
                        viewModel.purgeDerivedMemoryData { success ->
                            if (success) {
                                Toast.makeText(context, "Derived memory data purged. Raw notes are safe.", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Failed to purge derived memory data.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Purge")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPurgeDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // Encrypted Backup Password Dialog
    if (showBackupPasswordDialog) {
        AlertDialog(
            onDismissRequest = {
                showBackupPasswordDialog = false
                backupPassword = ""
            },
            title = { Text(stringResource(R.string.privacy_password_prompt)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    Text(
                        "Set a password to encrypt this backup with AES-256-GCM. Leave blank for unencrypted export.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = backupPassword,
                        onValueChange = { backupPassword = it },
                        label = { Text("Backup Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pass = backupPassword.ifBlank { null }
                        showBackupPasswordDialog = false
                        backupPassword = ""
                        backupDriveViewModel?.backupToLocal(password = pass)
                        Toast.makeText(context, "Creating backup...", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Export")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBackupPasswordDialog = false
                    backupPassword = ""
                }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // Restore Encrypted Backup Password Dialog
    if (showRestorePasswordDialog) {
        AlertDialog(
            onDismissRequest = {
                showRestorePasswordDialog = false
                restorePassword = ""
                pendingRestoreFile?.delete()
                pendingRestoreFile = null
            },
            title = { Text("Encrypted Backup Password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    Text(
                        "This archive is encrypted with AES-256-GCM. Please enter the password used when creating the backup.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = restorePassword,
                        onValueChange = { restorePassword = it },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val file = pendingRestoreFile
                        val pass = restorePassword
                        showRestorePasswordDialog = false
                        restorePassword = ""
                        if (file != null) {
                            backupDriveViewModel?.restoreFromBackupFile(file, overwrite = true, password = pass)
                            Toast.makeText(context, "Restoring encrypted backup...", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Decrypt & Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRestorePasswordDialog = false
                    restorePassword = ""
                    pendingRestoreFile?.delete()
                    pendingRestoreFile = null
                }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun PrivacyStatusRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isActive: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(AppSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        }
        if (isActive) {
            Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                Text("Active", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) {
        String.format("%.2f MB", mb)
    } else if (kb >= 1.0) {
        String.format("%.1f KB", kb)
    } else {
        "$bytes B"
    }
}
