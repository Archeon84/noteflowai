package com.noteflowai.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.noteflowai.app.R
import com.noteflowai.app.ui.theme.AppMotion
import com.noteflowai.app.ui.theme.AppRadius
import com.noteflowai.app.ui.theme.AppSpacing
import com.noteflowai.app.ui.theme.Haptics
import com.noteflowai.app.ui.theme.isReducedMotionEnabled

/**
 * Omni-Capture Sheet: Unified frictionless entry point for all capture modalities.
 * Preserves the original raw capture fidelity while routing directly to transcription,
 * OCR, document parsing, or instant note editing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmniCaptureSheet(
    onDismiss: () -> Unit,
    onRecordVoice: () -> Unit,
    onNewNote: () -> Unit,
    onScan: () -> Unit,
    onImportDocument: () -> Unit,
    onYouTube: () -> Unit
) {
    val context = LocalContext.current
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    FreshSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.omni_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.omni_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.xs))

            // Primary Capture Grid
            StaggeredItem(index = 0, visible = visible) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
            ) {
                // Voice Note (Primary Hero Capture)
                OmniHeroCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.omni_voice_title),
                    subtitle = stringResource(R.string.omni_voice_subtitle),
                    icon = Icons.Default.Mic,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    contentDescription = stringResource(R.string.omni_cd_hero_voice),
                    onClick = {
                        Haptics.tick(context)
                        onDismiss()
                        onRecordVoice()
                    }
                )

                // Quick Written Note
                OmniHeroCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.omni_note_title),
                    subtitle = stringResource(R.string.omni_note_subtitle),
                    icon = Icons.Default.EditNote,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    contentDescription = stringResource(R.string.omni_cd_hero_note),
                    onClick = {
                        Haptics.tick(context)
                        onDismiss()
                        onNewNote()
                    }
                )
            }
            }

            // Secondary Ingestion Sources
            StaggeredItem(index = 1, visible = visible) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(AppRadius.large)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(AppSpacing.sm)
                ) {
                    OmniListRow(
                        title = stringResource(R.string.omni_scan_title),
                        subtitle = stringResource(R.string.omni_scan_subtitle),
                        icon = Icons.Default.DocumentScanner,
                        contentDescription = stringResource(R.string.omni_cd_scan),
                        onClick = {
                            Haptics.tick(context)
                            onDismiss()
                            onScan()
                        }
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = AppSpacing.md),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    OmniListRow(
                        title = stringResource(R.string.omni_import_title),
                        subtitle = stringResource(R.string.omni_import_subtitle),
                        icon = Icons.Default.Description,
                        contentDescription = stringResource(R.string.omni_cd_import),
                        onClick = {
                            Haptics.tick(context)
                            onDismiss()
                            onImportDocument()
                        }
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = AppSpacing.md),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    OmniListRow(
                        title = stringResource(R.string.omni_youtube_title),
                        subtitle = stringResource(R.string.omni_youtube_subtitle),
                        icon = Icons.Default.PlayCircle,
                        contentDescription = stringResource(R.string.omni_cd_youtube),
                        onClick = {
                            Haptics.tick(context)
                            onDismiss()
                            onYouTube()
                        }
                    )
                }
            }
            }
        }
    }
}

/** Staggered entrance: fade + rise, 60ms apart, instant under reduced motion. */
@Composable
private fun StaggeredItem(
    index: Int,
    visible: Boolean,
    content: @Composable () -> Unit
) {
    val reducedMotion = isReducedMotionEnabled()
    AnimatedVisibility(
        visible = visible,
        enter = if (reducedMotion) {
            fadeIn(tween(durationMillis = 1, easing = LinearEasing))
        } else {
            fadeIn(tween(durationMillis = AppMotion.fast, delayMillis = index * 60)) +
                slideInVertically(
                    animationSpec = tween(
                        durationMillis = AppMotion.standard,
                        delayMillis = index * 60,
                        easing = LinearEasing
                    ),
                    initialOffsetY = { it / 6 }
                )
        }
    ) {
        content()
    }
}

@Composable
private fun OmniHeroCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    contentDescription: String? = null,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .height(130.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(AppRadius.large),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(AppSpacing.md),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(contentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(24.dp),
                    tint = contentColor
                )
            }

            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.75f)
                )
            }
        }
    }
}

@Composable
private fun OmniListRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    contentDescription: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.medium))
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.width(AppSpacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp)
        )
    }
}
