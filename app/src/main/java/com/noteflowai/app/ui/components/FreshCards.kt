package com.noteflowai.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noteflowai.app.data.memory.model.StatusUiModel
import com.noteflowai.app.data.memory.model.UserFacingStatus
import com.noteflowai.app.ui.theme.AppRadius
import com.noteflowai.app.ui.theme.AppSpacing

/**
 * Phase 1 Fresh kit — cards.
 *
 * Soft Organic Intelligence: 28dp tonal cards, 48dp minimum touch height
 * on interactive cards, press-scale + haptics inherited from [NoteFlowCard].
 */

/** Primary card. Tonal container, 28dp corners, press feedback when clickable. */
@Composable
fun FreshCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentPadding: PaddingValues = PaddingValues(AppSpacing.lg),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    NoteFlowCard(
        modifier = modifier.defaultMinSize(minHeight = if (onClick != null) 48.dp else 0.dp),
        containerColor = containerColor,
        contentPadding = contentPadding,
        onClick = onClick,
        content = content
    )
}

/** Menu-style card with a leading icon well, title, description and trailing slot. */
@Composable
fun FreshMenuCard(
    icon: ImageVector,
    iconContentDescription: String?,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    FreshCard(modifier = modifier, onClick = onClick, contentPadding = PaddingValues(AppSpacing.lg)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(44.dp)
            ) {
                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = iconContentDescription, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(modifier = Modifier.width(AppSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (trailing != null) {
                Spacer(modifier = Modifier.width(AppSpacing.sm))
                trailing()
            }
        }
    }
}

/**
 * Shared Insights+detail scaffold: [FreshTopBar] with back affordance and a
 * content slot receiving the Scaffold padding. Used by all Memory screens
 * so navigation, titles and gutters stay identical.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreshScreen(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backContentDescription: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        topBar = {
            FreshTopBar(
                title = title,
                onBack = onBack,
                backContentDescription = backContentDescription,
                actions = actions
            )
        },
        modifier = modifier,
        content = content
    )
}

/** Section header: small semibold title with an optional trailing action. */
@Composable
fun FreshSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private val FreshStatusAttention = setOf(
    UserFacingStatus.NEEDS_ATTENTION,
    UserFacingStatus.FAILED
)

/**
 * Merged status card: header row (title + [ProcessingPill]) with the full
 * [ProcessingStatusCard] detail block inline when the status is running
 * or needs attention. Collapses to just the pill row when calm.
 */
@Composable
fun FreshStatusCard(
    status: StatusUiModel?,
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconContentDescription: String? = null,
    onRetry: () -> Unit = {},
    onCancel: () -> Unit = {},
    content: @Composable (ColumnScope.() -> Unit)? = null
) {
    if (status == null) {
        if (content != null) {
            FreshCard(modifier = modifier) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(AppSpacing.sm))
                content()
            }
        }
        return
    }
    val expanded = status.status !in setOf(UserFacingStatus.READY, UserFacingStatus.SAVED_LOCALLY) ||
        status.status in FreshStatusAttention
    FreshCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = iconContentDescription,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(AppSpacing.sm))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            ProcessingPill(status = status)
        }
        if (expanded) {
            Spacer(modifier = Modifier.height(AppSpacing.sm))
            ProcessingStatusCard(status = status, onRetry = onRetry, onCancel = onCancel)
        }
        if (content != null) {
            Spacer(modifier = Modifier.height(AppSpacing.sm))
            content()
        }
    }
}
