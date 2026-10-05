package com.noteflowai.app.ui.screens

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import com.noteflowai.app.data.llm.AvailableModels
import com.noteflowai.app.data.llm.ModelId
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noteflowai.app.BuildConfig
import com.noteflowai.app.R
import com.noteflowai.app.data.WhisperModelManager
import com.noteflowai.app.data.tts.DeepgramTtsManager
import com.noteflowai.app.ui.theme.*
import com.noteflowai.app.ui.theme.Haptics
import com.noteflowai.app.viewmodel.BackupDriveViewModel
import com.noteflowai.app.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

val TRANSCRIPTION_LANGUAGES = listOf("Auto", "English", "Mandarin", "Japanese", "Korean", "Malay")
val APP_FONTS = listOf("Default", "Montserrat", "Playfair", "Pacifico", "Roboto", "Open Sans", "Lora", "Oswald", "Kanit", "Bebas Neue", "Dancing Script")

@Composable
fun ThemeColorCircle(name: String, color: Color, isSelected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .size(48.dp)
            .padding(6.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                shape = CircleShape
            )
            .clickable {
                Haptics.tap(context)
                onClick()
            }
            .semantics(mergeDescendants = true) {
                set(SemanticsProperties.ContentDescription, listOf("$name, ${if (isSelected) "selected" else "not selected"}"))
                set(SemanticsProperties.Role, Role.RadioButton)
            }
    )
}

// ── Reusable UI Components for Settings ─────────────────────────────────────

@Composable
fun SettingsSectionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    initialExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(initialExpanded) }
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.large),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.lg)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AppRadius.medium))
                    .clickable(
                        role = Role.Button,
                        onClick = {
                            Haptics.tap(context)
                            expanded = !expanded
                        }
                    )
                    .padding(vertical = AppSpacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(modifier = Modifier.width(AppSpacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(AppSpacing.md))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(AppSpacing.md))
                    content()
                }
            }
        }
    }
}

@Composable
fun SettingRow(
    label: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    trailingContent: @Composable () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = AppSpacing.md)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    lineHeight = 16.sp
                )
            }
        }
        trailingContent()
    }
}

@Composable
fun SettingToggle(
    label: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    SettingRow(
        label = label,
        description = description,
        modifier = modifier
    ) {
        Switch(
            checked = checked,
            onCheckedChange = { newValue ->
                Haptics.tap(context)
                onCheckedChange(newValue)
            }
        )
    }
}

// ── 1. AI & Intelligence Section ──────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiIntelligenceSection(viewModel: MainViewModel) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val aiProvider by viewModel.aiProvider.collectAsStateWithLifecycle()
    val aiApiKey by viewModel.aiApiKey.collectAsStateWithLifecycle()
    val aiBaseUrl by viewModel.aiBaseUrl.collectAsStateWithLifecycle()
    val aiModelName by viewModel.aiModelName.collectAsStateWithLifecycle()
    val aiSystemPrompt by viewModel.aiSystemPrompt.collectAsStateWithLifecycle()
    val aiTemperature by viewModel.aiTemperature.collectAsStateWithLifecycle()
    val aiPresencePenalty by viewModel.aiPresencePenalty.collectAsStateWithLifecycle()
    val aiTopP by viewModel.aiTopP.collectAsStateWithLifecycle()
    val aiContextTokens by viewModel.aiContextTokens.collectAsStateWithLifecycle()
    val aiKvCache by viewModel.aiKvCache.collectAsStateWithLifecycle()
    val aiMaxRequestsPerMin by viewModel.aiMaxRequestsPerMin.collectAsStateWithLifecycle()

    val ragEnabled by viewModel.ragEnabled.collectAsStateWithLifecycle()
    val ragMaxExcerpts by viewModel.ragMaxExcerpts.collectAsStateWithLifecycle()
    val embeddingEnabled by viewModel.embeddingEnabled.collectAsStateWithLifecycle()
    val embeddingModel by viewModel.embeddingModel.collectAsStateWithLifecycle()
    val citationEnabled by viewModel.citationEnabled.collectAsStateWithLifecycle()
    val cloudFullNoteEnabled by viewModel.cloudFullNoteEnabled.collectAsStateWithLifecycle()
    val conceptGraphEnabled by viewModel.conceptGraphEnabled.collectAsStateWithLifecycle()
    val multiHopEnabled by viewModel.multiHopEnabled.collectAsStateWithLifecycle()
    val rerankerEnabled by viewModel.rerankerEnabled.collectAsStateWithLifecycle(initialValue = false)
    val isRerankerDownloaded by viewModel.isRerankerDownloaded.collectAsStateWithLifecycle()
    val isRerankerDownloading by viewModel.isRerankerDownloading.collectAsStateWithLifecycle()
    val rerankerDownloadProgress by viewModel.rerankerDownloadProgress.collectAsStateWithLifecycle()
    var showRerankerDeleteDialog by remember { mutableStateOf(false) }
    val chatWebSearchActive by viewModel.chatWebSearchActive.collectAsStateWithLifecycle()
    val aiSearchApiUrl by viewModel.aiSearchApiUrl.collectAsStateWithLifecycle()
    val aiSearchApiKey by viewModel.aiSearchApiKey.collectAsStateWithLifecycle()

    val isLlamaDownloading by viewModel.isLlamaInferenceDownloading.collectAsStateWithLifecycle()
    val llamaProgress by viewModel.llamaInferenceDownloadProgress.collectAsStateWithLifecycle()
    val downloadingModelId by viewModel.downloadingLocalModelId.collectAsStateWithLifecycle()
    val downloadedModels by viewModel.downloadedLocalModels.collectAsStateWithLifecycle()
    val activeModelId by viewModel.activeLocalLlmModelId.collectAsStateWithLifecycle(initialValue = "gemma4_e2b")
    val enableLocalLlmFallback by viewModel.enableLocalLlmFallback.collectAsStateWithLifecycle()

    var tempProvider by remember(aiProvider) { mutableStateOf(aiProvider) }
    var tempApiKey by remember(aiApiKey) { mutableStateOf(aiApiKey) }
    var tempBaseUrl by remember(aiBaseUrl) { mutableStateOf(aiBaseUrl) }
    var tempModel by remember(aiModelName) { mutableStateOf(aiModelName) }
    var tempPrompt by remember(aiSystemPrompt) { mutableStateOf(aiSystemPrompt) }
    var tempTemp by remember(aiTemperature) { mutableStateOf(aiTemperature) }
    var tempPenalty by remember(aiPresencePenalty) { mutableStateOf(aiPresencePenalty) }
    var tempTopP by remember(aiTopP) { mutableStateOf(aiTopP) }
    var tempContextTokens by remember(aiContextTokens) { mutableStateOf(aiContextTokens) }
    var tempKvCache by remember(aiKvCache) { mutableStateOf(aiKvCache) }
    var tempMaxRequestsPerMin by remember(aiMaxRequestsPerMin) { mutableStateOf(aiMaxRequestsPerMin) }
    var tempWebSearchEnabled by remember(chatWebSearchActive) { mutableStateOf(chatWebSearchActive) }
    var tempSearchApiUrl by remember(aiSearchApiUrl) { mutableStateOf(aiSearchApiUrl) }
    var tempSearchApiKey by remember(aiSearchApiKey) { mutableStateOf(aiSearchApiKey) }

    var showApiKey by remember { mutableStateOf(false) }
    var testingConnection by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var availableModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var showRAGRefinement by remember { mutableStateOf(false) }

    val isInitiallyLocal = tempProvider.equals("Local", ignoreCase = true) || tempProvider.contains("Local", ignoreCase = true)
    var engineTab by remember(tempProvider) { mutableIntStateOf(if (isInitiallyLocal) 0 else 1) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // ── 1. AI Engine Card ──────────────────────────────────────────────────
        SettingsSectionCard(
            icon = Icons.Default.Psychology,
            title = stringResource(R.string.settings_engine_title),
            subtitle = stringResource(R.string.settings_engine_desc)
        ) {
            TabRow(
                selectedTabIndex = engineTab,
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                contentColor = MaterialTheme.colorScheme.primary,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[engineTab]),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            ) {
                Tab(
                    selected = engineTab == 0,
                    onClick = {
                        engineTab = 0
                        tempProvider = "Local"
                        tempBaseUrl = "local"
                        val activeModel = AvailableModels.ALL.firstOrNull { it.id.id == activeModelId } ?: AvailableModels.GEMMA_4_E2B
                        tempModel = activeModel.name
                        viewModel.updateAiConfig(
                            tempProvider, "", tempBaseUrl, tempModel, tempPrompt,
                            tempTemp, tempPenalty, tempTopP, tempContextTokens, tempKvCache
                        )
                    },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.config_tab_on_device), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                )
                Tab(
                    selected = engineTab == 1,
                    onClick = {
                        engineTab = 1
                        if (tempProvider == "Local" || tempProvider == "Local (On-Device)") {
                            tempProvider = "Gemini"
                            tempBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/"
                            tempModel = "gemini-1.5-flash"
                        }
                    },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.config_tab_cloud), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(AppSpacing.md))

            if (engineTab == 0) {
                // On-Device Mode
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.sm))
                        Text(
                            text = stringResource(R.string.config_privacy_banner),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(modifier = Modifier.height(AppSpacing.sm))

                AvailableModels.ALL.forEach { modelInfo ->
                    val isDownloaded = downloadedModels.contains(modelInfo.id.id)
                    val isActive = activeModelId == modelInfo.id.id
                    val isCurrentlyDownloading = isLlamaDownloading && downloadingModelId == modelInfo.id.id

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(AppRadius.medium),
                        border = if (isActive) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(AppSpacing.sm)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = modelInfo.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (isActive) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                color = MaterialTheme.colorScheme.primary,
                                                shape = RoundedCornerShape(AppRadius.small)
                                            ) {
                                                Text(
                                                    stringResource(R.string.settings_badge_active),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = stringResource(R.string.settings_model_size_format, modelInfo.approximateSizeMb / 1000.0),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }

                                if (isDownloaded) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (!isActive) {
                                            FilledTonalButton(
                                                onClick = {
                                                    viewModel.setActiveLocalLlmModel(modelInfo.id)
                                                    tempModel = modelInfo.name
                                                    tempProvider = "Local"
                                                    tempBaseUrl = "local"
                                                    viewModel.updateAiConfig(
                                                        tempProvider, "", tempBaseUrl, tempModel, tempPrompt,
                                                        tempTemp, tempPenalty, tempTopP, tempContextTokens, tempKvCache
                                                    )
                                                },
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                modifier = Modifier.height(32.dp)
                                            ) {
                                                Text(stringResource(R.string.settings_use_model), style = MaterialTheme.typography.labelSmall)
                                            }
                                            Spacer(modifier = Modifier.width(6.dp))
                                        }
                                        IconButton(
                                            onClick = { viewModel.deleteLlamaInferenceModel(modelInfo.id) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.DeleteOutline,
                                                contentDescription = "Delete model",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                } else if (isCurrentlyDownloading) {
                                    Text(
                                        "${(llamaProgress * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Button(
                                        onClick = { viewModel.downloadLlamaInferenceModel(modelInfo.id) },
                                        enabled = !isLlamaDownloading,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(stringResource(R.string.settings_button_download_model), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }

                            if (isCurrentlyDownloading) {
                                Spacer(modifier = Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { llamaProgress },
                                    modifier = Modifier.fillMaxWidth().height(4.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(AppSpacing.xs))
                SettingToggle(
                    label = stringResource(R.string.settings_offline_llm_fallback_label),
                    description = stringResource(R.string.settings_offline_llm_fallback_desc),
                    checked = enableLocalLlmFallback,
                    onCheckedChange = { viewModel.setEnableLocalLlmFallback(it) }
                )
            } else {
                // Cloud AI Mode
                val cloudProviders = remember { listOf("Gemini", "Claude", "OpenAI", "DeepSeek", "Ollama", "Custom") }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    cloudProviders.forEach { p ->
                        FilterChip(
                            selected = tempProvider == p,
                            onClick = {
                                tempProvider = p
                                when (p) {
                                    "Gemini" -> {
                                        tempBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/"
                                        tempModel = "gemini-1.5-flash"
                                        if (tempApiKey.isBlank()) tempApiKey = viewModel.aiApiKey.value
                                    }
                                    "Claude" -> {
                                        tempBaseUrl = "https://api.anthropic.com/v1/"
                                        tempModel = "claude-3-5-sonnet-20241022"
                                        if (tempApiKey.isBlank()) tempApiKey = viewModel.aiApiKey.value
                                    }
                                    "OpenAI" -> {
                                        tempBaseUrl = "https://api.openai.com/v1/"
                                        tempModel = "gpt-4o-mini"
                                        if (tempApiKey.isBlank()) tempApiKey = viewModel.aiApiKey.value
                                    }
                                    "DeepSeek" -> {
                                        tempBaseUrl = "https://api.deepseek.com/v1/"
                                        tempModel = "deepseek-chat"
                                        if (tempApiKey.isBlank()) tempApiKey = viewModel.aiApiKey.value
                                    }
                                    "Ollama" -> {
                                        tempBaseUrl = "http://10.0.2.2:11434/"
                                        tempModel = "llama3.2"
                                    }
                                }
                            },
                            label = { Text(p, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(AppSpacing.sm))

                if (tempProvider != "Ollama") {
                    OutlinedTextField(
                        value = tempApiKey,
                        onValueChange = { tempApiKey = it },
                        label = { Text(stringResource(R.string.settings_ai_api_key)) },
                        placeholder = { Text(stringResource(R.string.settings_api_key_placeholder)) },
                        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    if (showApiKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = stringResource(R.string.settings_toggle_password_visibility)
                                )
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.xs))
                    Text(
                        text = stringResource(R.string.settings_ai_api_key_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = tempModel,
                        onValueChange = { tempModel = it },
                        label = { Text(stringResource(R.string.settings_ai_model_name)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                testingConnection = true
                                testResult = context.getString(R.string.config_status_connecting)
                                try {
                                    val models = viewModel.testAiConnection(tempBaseUrl, tempProvider, tempApiKey)
                                    availableModels = models
                                    testResult = context.getString(R.string.config_status_success)
                                } catch (e: Exception) {
                                    testResult = context.getString(R.string.config_status_error)
                                } finally {
                                    testingConnection = false
                                }
                            }
                        },
                        enabled = !testingConnection,
                        modifier = Modifier.height(56.dp)
                    ) {
                        if (testingConnection) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.settings_ai_test_connection), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                if (testResult != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = testResult!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (testResult == context.getString(R.string.config_status_success)) successTextColor() else MaterialTheme.colorScheme.error
                    )
                }

                if (availableModels.isNotEmpty()) {
                    var mExpanded by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.padding(top = AppSpacing.xs)) {
                        TextButton(onClick = { mExpanded = true }) {
                            Text(stringResource(R.string.config_detected_models), style = MaterialTheme.typography.labelMedium)
                        }
                        DropdownMenu(expanded = mExpanded, onDismissRequest = { mExpanded = false }) {
                            availableModels.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m) },
                                    onClick = {
                                        tempModel = m
                                        mExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                if (tempProvider == "Custom" || tempProvider == "Ollama" || tempProvider == "LM Studio") {
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                    OutlinedTextField(
                        value = tempBaseUrl,
                        onValueChange = { tempBaseUrl = it },
                        label = { Text(stringResource(R.string.settings_ai_base_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(AppSpacing.md))
                Button(
                    onClick = {
                        viewModel.updateAiConfig(
                            tempProvider, tempApiKey, tempBaseUrl, tempModel, tempPrompt,
                            tempTemp, tempPenalty, tempTopP, tempContextTokens, tempKvCache
                        )
                        Toast.makeText(context, context.getString(R.string.config_saved_toast), Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(AppSpacing.sm))
                    Text("Save Cloud Settings")
                }
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))

        // ── 2. Knowledge Grounding (RAG) Card ──────────────────────────────────
        SettingsSectionCard(
            icon = Icons.Default.AutoStories,
            title = stringResource(R.string.settings_rag_card_title),
            subtitle = stringResource(R.string.settings_rag_card_desc),
            initialExpanded = true
        ) {
            SettingToggle(
                label = stringResource(R.string.settings_rag_grounded_label),
                description = stringResource(R.string.settings_rag_grounded_desc),
                checked = ragEnabled,
                onCheckedChange = { viewModel.setRagEnabled(it) }
            )

            if (ragEnabled) {
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.xs)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.settings_rag_depth_label),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.settings_excerpts_value, ragMaxExcerpts),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_rag_depth_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                    Slider(
                        value = ragMaxExcerpts.toFloat(),
                        onValueChange = { viewModel.setRagMaxExcerpts(it.toInt()) },
                        valueRange = 1f..10f,
                        steps = 8,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = context.getString(
                                    R.string.settings_excerpts_value,
                                    ragMaxExcerpts
                                )
                            }
                    )
                }

                Spacer(modifier = Modifier.height(AppSpacing.sm))

                // Alibaba GTE Multilingual Reranker Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(AppSpacing.md)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = AppSpacing.sm)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = stringResource(R.string.settings_reranker_label),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (isRerankerDownloaded) {
                                        Spacer(modifier = Modifier.width(AppSpacing.xs))
                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.settings_reranker_ready),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = stringResource(R.string.settings_reranker_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                            Switch(
                                checked = rerankerEnabled && isRerankerDownloaded,
                                onCheckedChange = { viewModel.setRerankerEnabled(it) },
                                enabled = isRerankerDownloaded && !isRerankerDownloading
                            )
                        }

                        if (!isRerankerDownloaded) {
                            Spacer(modifier = Modifier.height(AppSpacing.sm))
                            if (isRerankerDownloading) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = stringResource(
                                                R.string.settings_reranker_downloading,
                                                (rerankerDownloadProgress * 100).toInt()
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(AppSpacing.xs))
                                    LinearProgressIndicator(
                                        progress = { rerankerDownloadProgress },
                                        modifier = Modifier.fillMaxWidth().height(6.dp),
                                    )
                                }
                            } else {
                                Button(
                                    onClick = { viewModel.downloadReranker() },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(AppSpacing.xs))
                                    Text(stringResource(R.string.settings_reranker_download_btn))
                                }
                            }
                        } else {
                            Spacer(modifier = Modifier.height(AppSpacing.xs))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = { showRerankerDeleteDialog = true },
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(R.string.settings_reranker_delete_btn),
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(AppSpacing.sm))

                // Progressive disclosure: Retrieval Precision & Reasoning sub-accordion
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                    )
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(AppSpacing.sm)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showRAGRefinement = !showRAGRefinement }
                                .padding(AppSpacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.settings_rag_refinement_title),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(R.string.settings_rag_refinement_desc),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                                )
                            }
                            Icon(
                                imageVector = if (showRAGRefinement) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        AnimatedVisibility(
                            visible = showRAGRefinement,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.xs)) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = AppSpacing.xs),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                                SettingToggle(
                                    label = "Semantic Vector Embedding",
                                    description = "Vector search powered by IBM Granite-Embedding-311M-Multilingual-R2",
                                    checked = embeddingEnabled,
                                    onCheckedChange = { viewModel.setEmbeddingEnabled(it) }
                                )
                                SettingToggle(
                                    label = stringResource(R.string.settings_citation_label),
                                    description = stringResource(R.string.settings_citation_desc),
                                    checked = citationEnabled,
                                    onCheckedChange = { viewModel.setCitationEnabled(it) }
                                )
                                SettingToggle(
                                    label = stringResource(R.string.settings_cloud_full_note_label),
                                    description = stringResource(R.string.settings_cloud_full_note_desc),
                                    checked = cloudFullNoteEnabled,
                                    onCheckedChange = { viewModel.setCloudFullNoteEnabled(it) }
                                )
                                SettingToggle(
                                    label = stringResource(R.string.settings_concept_graph_label),
                                    description = stringResource(R.string.settings_concept_graph_desc),
                                    checked = conceptGraphEnabled,
                                    onCheckedChange = { viewModel.setConceptGraphEnabled(it) }
                                )
                                SettingToggle(
                                    label = stringResource(R.string.settings_multi_hop_label),
                                    description = stringResource(R.string.settings_multi_hop_desc),
                                    checked = multiHopEnabled,
                                    onCheckedChange = { viewModel.setMultiHopEnabled(it) }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showRerankerDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showRerankerDeleteDialog = false },
                title = { Text(stringResource(R.string.settings_reranker_delete_btn)) },
                text = { Text(stringResource(R.string.settings_reranker_delete_confirm)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showRerankerDeleteDialog = false
                            viewModel.deleteReranker()
                        }
                    ) {
                        Text(stringResource(R.string.notes_menu_delete), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRerankerDeleteDialog = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))

        // ── 3. Advanced Model Tuning Card ──────────────────────────────────────
        SettingsSectionCard(
            icon = Icons.Default.Tune,
            title = stringResource(R.string.settings_ai_advanced_params),
            subtitle = stringResource(R.string.settings_ai_advanced_params_desc),
            initialExpanded = false
        ) {
            // Visual Response Style Selector
            Text(
                text = stringResource(R.string.config_response_style),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(AppSpacing.xs))

            val isPrecise = tempTemp <= 0.35f
            val isBalanced = tempTemp > 0.35f && tempTemp <= 0.95f
            val isCreative = tempTemp > 0.95f

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    Triple(stringResource(R.string.config_style_precise), stringResource(R.string.config_style_precise_desc), isPrecise),
                    Triple(stringResource(R.string.config_style_balanced), stringResource(R.string.config_style_balanced_desc), isBalanced),
                    Triple(stringResource(R.string.config_style_creative), stringResource(R.string.config_style_creative_desc), isCreative)
                ).forEachIndexed { idx, (title, desc, selected) ->
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                when (idx) {
                                    0 -> { tempTemp = 0.2f; tempTopP = 0.8f }
                                    1 -> { tempTemp = 0.7f; tempTopP = 0.95f }
                                    2 -> { tempTemp = 1.2f; tempTopP = 1.0f }
                                }
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.md))

            // Temperature Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Temperature",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = String.format(Locale.US, "%.1f", tempTemp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = tempTemp,
                onValueChange = { tempTemp = it },
                valueRange = 0f..2f,
                steps = 20,
                modifier = Modifier.fillMaxWidth()
            )

            // Context Window Limit Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Context Limit",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.settings_context_limit_label, tempContextTokens),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = tempContextTokens.toFloat(),
                onValueChange = { tempContextTokens = it.toInt() },
                valueRange = 512f..32768f,
                steps = 62,
                modifier = Modifier.fillMaxWidth()
            )

            // Presence Penalty Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Presence Penalty",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = String.format(Locale.US, "%.1f", tempPenalty),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = tempPenalty,
                onValueChange = { tempPenalty = it },
                valueRange = 0f..2f,
                steps = 20,
                modifier = Modifier.fillMaxWidth()
            )

            // KV Cache Toggle
            SettingToggle(
                label = stringResource(R.string.settings_kv_cache_label),
                description = stringResource(R.string.settings_kv_cache_desc),
                checked = tempKvCache,
                onCheckedChange = { tempKvCache = it }
            )

            SettingToggle(
                label = stringResource(R.string.settings_offline_llm_fallback_label),
                description = stringResource(R.string.settings_offline_llm_fallback_desc),
                checked = enableLocalLlmFallback,
                onCheckedChange = { viewModel.setEnableLocalLlmFallback(it) }
            )

            // Web Search Grounding
            SettingToggle(
                label = stringResource(R.string.settings_web_search_label),
                description = stringResource(R.string.settings_web_search_desc),
                checked = tempWebSearchEnabled,
                onCheckedChange = { tempWebSearchEnabled = it }
            )

            if (tempWebSearchEnabled) {
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                OutlinedTextField(
                    value = tempSearchApiUrl,
                    onValueChange = { tempSearchApiUrl = it },
                    label = { Text(stringResource(R.string.config_search_api_url_label)) },
                    placeholder = { Text(stringResource(R.string.config_search_api_url_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                OutlinedTextField(
                    value = tempSearchApiKey,
                    onValueChange = { tempSearchApiKey = it },
                    label = { Text(stringResource(R.string.config_search_api_key_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(AppSpacing.xs))

            // Rate Limit
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Request Rate Limit",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (tempMaxRequestsPerMin <= 0) stringResource(R.string.config_unlimited) else "$tempMaxRequestsPerMin / min",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = tempMaxRequestsPerMin.toFloat(),
                onValueChange = { tempMaxRequestsPerMin = it.toInt() },
                valueRange = 0f..60f,
                steps = 11,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(AppSpacing.sm))

            // System Prompt
            OutlinedTextField(
                value = tempPrompt,
                onValueChange = { tempPrompt = it },
                label = { Text(stringResource(R.string.config_system_prompt_label)) },
                placeholder = { Text("Custom system instructions...") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 5
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = {
                        tempPrompt = "You are a helpful AI assistant."
                    }
                ) {
                    Text("Reset System Prompt", style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.sm))

            Button(
                onClick = {
                    viewModel.updateAiConfig(
                        tempProvider, tempApiKey, tempBaseUrl, tempModel, tempPrompt,
                        tempTemp, tempPenalty, tempTopP, tempContextTokens, tempKvCache
                    )
                    viewModel.saveWebSearchConfig(tempWebSearchEnabled, tempSearchApiUrl, tempSearchApiKey)
                    viewModel.saveMaxRequestsPerMin(tempMaxRequestsPerMin)
                    Toast.makeText(context, context.getString(R.string.config_saved_toast), Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(AppSpacing.sm))
                Text("Save Advanced Settings")
            }
        }
    }
}

// ── 2. Voice & Speech Section ───────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSpeechSection(viewModel: MainViewModel) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val isOnlineMode by viewModel.isOnlineMode.collectAsStateWithLifecycle()
    val currentModel by viewModel.currentModel.collectAsStateWithLifecycle()
    val customModelPath by viewModel.customModelPath.collectAsStateWithLifecycle()
    val transcriptionLanguage by viewModel.transcriptionLanguage.collectAsStateWithLifecycle()
    val deepgramApiKey by viewModel.deepgramApiKey.collectAsStateWithLifecycle()
    val ttsEnabled by viewModel.ttsEnabled.collectAsStateWithLifecycle()
    val ttsVoice by viewModel.ttsVoice.collectAsStateWithLifecycle()
    val scheduleConfig by viewModel.scheduleConfig.collectAsStateWithLifecycle()
    val showScheduleTimePicker by viewModel.showScheduleTimePicker.collectAsStateWithLifecycle()

    var editingDeepgramKey by remember(deepgramApiKey) { mutableStateOf(deepgramApiKey) }
    var showDeepgramKey by remember { mutableStateOf(false) }

    if (showScheduleTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = scheduleConfig.hour,
            initialMinute = scheduleConfig.minute,
            is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
        )
        AlertDialog(
            onDismissRequest = { viewModel.dismissScheduleTimePicker() },
            title = { Text(stringResource(R.string.settings_schedule_pick_time)) },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setScheduleTime(timePickerState.hour, timePickerState.minute)
                    viewModel.dismissScheduleTimePicker()
                }) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissScheduleTimePicker() }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    val modelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.loadCustomModel(it) }
    }

    SettingsSectionCard(
        icon = Icons.Default.RecordVoiceOver,
        title = stringResource(R.string.settings_section_voice_speech),
        subtitle = stringResource(R.string.settings_section_voice_speech_desc)
    ) {
        // Speech-to-Text Mode (Segmented Button)
        Text(
            text = stringResource(R.string.settings_stt_mode_label),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.settings_stt_mode_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        )
        Spacer(modifier = Modifier.height(AppSpacing.sm))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !isOnlineMode,
                onClick = { viewModel.toggleOnlineMode(false) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
            ) {
                                            Text(stringResource(R.string.settings_whisper_offline), style = MaterialTheme.typography.labelSmall)
            }
            SegmentedButton(
                selected = isOnlineMode,
                onClick = { viewModel.toggleOnlineMode(true) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
            ) {
                Text(stringResource(R.string.settings_whisper_cloud), style = MaterialTheme.typography.labelSmall)
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))

        if (!isOnlineMode) {
            // Whisper Model Selector
            Text(stringResource(R.string.settings_whisper_model), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(AppSpacing.xs))
            var whisperExpanded by remember { mutableStateOf(false) }
            val modelDisplay = if (customModelPath != null) stringResource(R.string.settings_custom_model, customModelPath!!.substringAfterLast("/")) else stringResource(R.string.settings_current_model, currentModel)

            ExposedDropdownMenuBox(
                expanded = whisperExpanded,
                onExpandedChange = { whisperExpanded = it },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = modelDisplay,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = whisperExpanded) },
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = whisperExpanded, onDismissRequest = { whisperExpanded = false }) {
                    WhisperModelManager.MODELS.forEach { m ->
                        DropdownMenuItem(text = { Text(m) }, onClick = { viewModel.setModel(m); whisperExpanded = false })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(stringResource(R.string.settings_load_custom)) }, onClick = { modelPickerLauncher.launch("*/*"); whisperExpanded = false })
                }
            }
        } else {
            // Deepgram API Key
            OutlinedTextField(
                value = editingDeepgramKey,
                onValueChange = { editingDeepgramKey = it },
                label = { Text(stringResource(R.string.settings_deepgram_key)) },
                placeholder = { Text(stringResource(R.string.settings_deepgram_placeholder)) },
                visualTransformation = if (showDeepgramKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showDeepgramKey = !showDeepgramKey }) {
                        Icon(
                            if (showDeepgramKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = stringResource(R.string.settings_toggle_password_visibility)
                        )
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(AppSpacing.xs))
            Button(
                onClick = { viewModel.setDeepgramApiKey(editingDeepgramKey) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_save_key))
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))

        // Transcription Language
        Text(stringResource(R.string.settings_stt_language_label), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(AppSpacing.xs))
        var langExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = langExpanded,
            onExpandedChange = { langExpanded = it },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = transcriptionLanguage,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = langExpanded) },
                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = langExpanded, onDismissRequest = { langExpanded = false }) {
                TRANSCRIPTION_LANGUAGES.forEach { lang ->
                    DropdownMenuItem(
                        text = { Text(lang) },
                        onClick = { viewModel.setTranscriptionLanguage(lang); langExpanded = false }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(AppSpacing.md))

        // AI Voice Readout (TTS)
        SettingToggle(
            label = stringResource(R.string.settings_tts_label),
            description = stringResource(R.string.settings_tts_desc),
            checked = ttsEnabled,
            onCheckedChange = { viewModel.setTtsEnabled(it) }
        )

        if (ttsEnabled) {
            Spacer(modifier = Modifier.height(AppSpacing.xs))
            Text(stringResource(R.string.settings_tts_voice_label), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(AppSpacing.xs))
            var voiceExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = voiceExpanded,
                onExpandedChange = { voiceExpanded = it },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = DeepgramTtsManager.VOICES.firstOrNull { it.model == ttsVoice }?.label ?: ttsVoice,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceExpanded) },
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = voiceExpanded, onDismissRequest = { voiceExpanded = false }) {
                    DeepgramTtsManager.VOICES.forEach { v ->
                        DropdownMenuItem(
                            text = { Text(v.label) },
                            onClick = { viewModel.setTtsVoice(v.model); voiceExpanded = false }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(AppSpacing.md))

        // Scheduled Recording
        SettingRow(
            label = stringResource(R.string.settings_scheduled_rec_label),
            description = if (scheduleConfig.enabled)
                stringResource(R.string.settings_schedule_enabled, String.format("%02d:%02d", scheduleConfig.hour, scheduleConfig.minute), scheduleConfig.durationMinutes)
            else stringResource(R.string.settings_scheduled_rec_desc)
        ) {
            Switch(
                checked = scheduleConfig.enabled,
                onCheckedChange = { enabled ->
                    Haptics.tap(context)
                    if (enabled) viewModel.enableScheduledRecording()
                    else viewModel.disableScheduledRecording()
                }
            )
        }

        if (!scheduleConfig.enabled) {
            Spacer(modifier = Modifier.height(AppSpacing.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                OutlinedButton(onClick = { viewModel.showScheduleTimePicker() }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_set_time), style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(onClick = { viewModel.showScheduleDurationPicker() }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_duration_prefix, scheduleConfig.durationMinutes), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

// ── 3. Privacy & Data Vault Section ────────────────────────────────────────

@Composable
fun PrivacyVaultSection(
    viewModel: MainViewModel,
    backupDriveViewModel: BackupDriveViewModel
) {
    val context = LocalContext.current
    val isLocalOnlyMode by viewModel.isLocalOnlyMode.collectAsStateWithLifecycle()
    val isBackingUp by backupDriveViewModel.isBackingUp.collectAsStateWithLifecycle()
    val backupMessage by backupDriveViewModel.backupMessage.collectAsStateWithLifecycle()
    val includeRecordings by backupDriveViewModel.includeRecordings.collectAsStateWithLifecycle()
    val isGoogleSignedIn by backupDriveViewModel.isGoogleSignedIn.collectAsStateWithLifecycle()
    val googleEmail by backupDriveViewModel.googleEmail.collectAsStateWithLifecycle()
    val ttsCacheBytes by viewModel.ttsCacheSizeBytes.collectAsStateWithLifecycle()
    val tempFilesBytes by viewModel.tempFilesSizeBytes.collectAsStateWithLifecycle()

    var showRestoreDialog by remember { mutableStateOf(false) }
    var restoreOverwrite by remember { mutableStateOf(true) }
    var showPurgeConfirmDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshPrivacyCacheSizes()
    }

    LaunchedEffect(backupMessage) {
        if (backupMessage.isNotEmpty() &&
            !backupMessage.contains("failed", ignoreCase = true) &&
            !backupMessage.contains("error", ignoreCase = true)
        ) {
            delay(4000)
            backupDriveViewModel.clearBackupMessage()
        }
    }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        backupDriveViewModel.handleGoogleSignInResult(result.data)
    }

    SettingsSectionCard(
        icon = Icons.Default.Shield,
        title = stringResource(R.string.settings_section_privacy_vault),
        subtitle = stringResource(R.string.settings_section_privacy_vault_desc)
    ) {
        // Local-Only Mode Firewall Toggle
        SettingToggle(
            label = stringResource(R.string.settings_local_only_label),
            description = stringResource(R.string.settings_local_only_desc),
            checked = isLocalOnlyMode,
            onCheckedChange = { viewModel.setLocalOnlyMode(it) }
        )

        if (isLocalOnlyMode) {
            Spacer(modifier = Modifier.height(AppSpacing.xs))
            Surface(
                color = successTextColor().copy(alpha = 0.15f),
                shape = RoundedCornerShape(AppRadius.small),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(AppSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = successTextColor(), modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(AppSpacing.xs))
                    Text(
                        text = "100% Offline & Private: No cloud requests will be made.",
                        style = MaterialTheme.typography.bodySmall,
                        color = successTextColor(),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(AppSpacing.md))

        // Local Encrypted Backup
        Text(
            text = stringResource(R.string.settings_local_backup_label),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = stringResource(R.string.settings_local_backup_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        )
        Spacer(modifier = Modifier.height(AppSpacing.sm))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.settings_backup_include_recordings), style = MaterialTheme.typography.bodySmall)
            Checkbox(
                checked = includeRecordings,
                onCheckedChange = { backupDriveViewModel.setIncludeRecordings(it) }
            )
        }

        Spacer(modifier = Modifier.height(AppSpacing.xs))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Button(
                onClick = { backupDriveViewModel.backupToLocal() },
                modifier = Modifier.weight(1f),
                enabled = !isBackingUp
            ) {
                if (isBackingUp) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Default.Backup, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.settings_backup_all), style = MaterialTheme.typography.labelSmall)
                }
            }

            OutlinedButton(
                onClick = { showRestoreDialog = true },
                modifier = Modifier.weight(1f),
                enabled = !isBackingUp
            ) {
                Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.settings_restore_button), style = MaterialTheme.typography.labelSmall)
            }
        }

        if (backupMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(AppSpacing.sm))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (backupMessage.contains("failed", ignoreCase = true) || backupMessage.contains("error", ignoreCase = true))
                        MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(AppSpacing.sm).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(backupMessage, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { backupDriveViewModel.clearBackupMessage() }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close), modifier = Modifier.size(14.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(AppSpacing.md))

        // Google Drive Sync
        Text(
            text = stringResource(R.string.settings_gdrive_sync_label),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = stringResource(R.string.settings_gdrive_sync_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        )
        Spacer(modifier = Modifier.height(AppSpacing.sm))

        if (isGoogleSignedIn) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier.padding(AppSpacing.sm).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_connected), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(googleEmail ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                    TextButton(onClick = { backupDriveViewModel.googleSignOut() }) {
                        Text(stringResource(R.string.settings_sign_out), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Spacer(modifier = Modifier.height(AppSpacing.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                Button(
                    onClick = { backupDriveViewModel.backupToGoogleDrive() },
                    modifier = Modifier.weight(1f),
                    enabled = !isBackingUp
                ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.settings_sync_drive), style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = { backupDriveViewModel.restoreFromGoogleDrive() },
                    modifier = Modifier.weight(1f),
                    enabled = !isBackingUp
                ) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.settings_restore_drive), style = MaterialTheme.typography.labelSmall)
                }
            }
        } else {
            OutlinedButton(
                onClick = { googleSignInLauncher.launch(backupDriveViewModel.getGoogleSignInIntent()) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(AppSpacing.sm))
                Text(stringResource(R.string.settings_sign_in))
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(AppSpacing.md))

        // Data & Cache Management
        Text(
            text = stringResource(R.string.settings_cache_cleanup_label),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = stringResource(R.string.settings_cache_cleanup_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        )
        Spacer(modifier = Modifier.height(AppSpacing.sm))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val totalCacheKb = (ttsCacheBytes + tempFilesBytes) / 1024
            Text(
                text = stringResource(R.string.settings_cache_usage, totalCacheKb),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            OutlinedButton(
                onClick = {
                    viewModel.clearTtsCache { ttsOk ->
                        viewModel.clearTempFiles { tempOk ->
                            Toast.makeText(
                                context,
                                context.getString(
                                    if (ttsOk && tempOk) R.string.settings_cache_cleared
                                    else R.string.settings_cache_clear_failed
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            ) {
                Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.settings_clear_cache_btn), style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text(stringResource(R.string.settings_restore_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_restore_message), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(AppSpacing.md))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = restoreOverwrite, onCheckedChange = { restoreOverwrite = it })
                        Spacer(Modifier.width(AppSpacing.sm))
                        Text(stringResource(R.string.settings_overwrite_label), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    backupDriveViewModel.restoreFromLocal(restoreOverwrite)
                    showRestoreDialog = false
                }) { Text(stringResource(R.string.settings_restore_button)) }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }) { Text(stringResource(R.string.dialog_cancel_button)) }
            }
        )
    }
}

// ── 4. Appearance & Habits Section ─────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppearanceHabitsSection(viewModel: MainViewModel) {
    val darkModeOption by viewModel.darkModeOption.collectAsStateWithLifecycle()
    val currentTheme by viewModel.appTheme.collectAsStateWithLifecycle()
    val currentFont by viewModel.appFont.collectAsStateWithLifecycle()
    val digestEnabled by viewModel.dailyDigestEnabled.collectAsStateWithLifecycle()
    val recallRemindersEnabled by viewModel.recallRemindersEnabled.collectAsStateWithLifecycle()

    SettingsSectionCard(
        icon = Icons.Default.Palette,
        title = stringResource(R.string.settings_section_appearance_habits),
        subtitle = stringResource(R.string.settings_section_appearance_habits_desc)
    ) {
        // Dark Mode
        Text(stringResource(R.string.settings_dark_mode), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(AppSpacing.xs))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = darkModeOption == "light",
                onClick = { viewModel.setDarkModeOption("light") },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
            ) { Text(stringResource(R.string.settings_dark_mode_light)) }
            SegmentedButton(
                selected = darkModeOption == "dark",
                onClick = { viewModel.setDarkModeOption("dark") },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
            ) { Text(stringResource(R.string.settings_dark_mode_dark)) }
            SegmentedButton(
                selected = darkModeOption == "system",
                onClick = { viewModel.setDarkModeOption("system") },
                shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
            ) { Text(stringResource(R.string.settings_dark_mode_system)) }
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))

        // App Accent Theme
        Text(stringResource(R.string.settings_app_theme), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(AppSpacing.xs))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            ThemeColorCircle(stringResource(R.string.theme_default), MaterialTheme.colorScheme.primary, currentTheme == "Default") { viewModel.setAppTheme("Default") }
            ThemeColorCircle(stringResource(R.string.theme_lavender), LavenderPrimary, currentTheme == "Lavender") { viewModel.setAppTheme("Lavender") }
            ThemeColorCircle(stringResource(R.string.theme_peach), PeachPrimary, currentTheme == "Peach") { viewModel.setAppTheme("Peach") }
            ThemeColorCircle(stringResource(R.string.theme_mint), MintPrimary, currentTheme == "Mint") { viewModel.setAppTheme("Mint") }
            ThemeColorCircle(stringResource(R.string.theme_sky), SkyPrimary, currentTheme == "Sky") { viewModel.setAppTheme("Sky") }
            ThemeColorCircle(stringResource(R.string.theme_sakura), SakuraPrimary, currentTheme == "Sakura") { viewModel.setAppTheme("Sakura") }
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))

        // App Font
        Text(stringResource(R.string.settings_app_font), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(AppSpacing.xs))
        var fExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = fExpanded,
            onExpandedChange = { fExpanded = it },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = stringResource(R.string.settings_current_font, currentFont),
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = fExpanded) },
                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = fExpanded, onDismissRequest = { fExpanded = false }) {
                APP_FONTS.forEach { font ->
                    DropdownMenuItem(text = { Text(font) }, onClick = { viewModel.setAppFont(font); fExpanded = false })
                }
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(AppSpacing.md))

        // Habits & Daily Briefing
        SettingToggle(
            label = stringResource(R.string.settings_daily_digest_title),
            description = stringResource(R.string.settings_daily_digest_desc),
            checked = digestEnabled,
            onCheckedChange = { viewModel.setDailyDigestEnabled(it) }
        )

        SettingToggle(
            label = stringResource(R.string.settings_recall_reminders),
            description = stringResource(R.string.settings_recall_reminders_desc),
            checked = recallRemindersEnabled,
            onCheckedChange = { viewModel.setRecallRemindersEnabled(it) }
        )
    }
}

// ── 5. About & Diagnostics Section ─────────────────────────────────────────

@Composable
fun AboutDiagnosticsSection(
    viewModel: MainViewModel,
    onResetOnboarding: () -> Unit
) {
    val context = LocalContext.current
    val diagnostics by viewModel.databaseDiagnostics.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.refreshDatabaseDiagnostics()
    }

    SettingsSectionCard(
        icon = Icons.Default.Info,
        title = stringResource(R.string.settings_section_about_diagnostics),
        subtitle = stringResource(R.string.settings_section_about_diagnostics_desc)
    ) {
        // App Version Card
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(AppRadius.small),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(AppSpacing.md)) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.settings_created_by),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                if (diagnostics == null) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp))
                } else {
                    val d = diagnostics!!
                    val ok = d.version >= 0
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (ok) successTextColor() else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.settings_health_summary, d.version, d.entityCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (ok) successTextColor() else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))

        OutlinedButton(
            onClick = onResetOnboarding,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(AppSpacing.sm))
            Text(stringResource(R.string.settings_show_onboarding))
        }
    }
}
