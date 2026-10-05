package com.noteflowai.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noteflowai.app.R
import com.noteflowai.app.data.memory.model.StatusUiModel
import com.noteflowai.app.data.memory.model.UserFacingStatus
import com.noteflowai.app.ui.theme.AppRadius
import com.noteflowai.app.ui.theme.AppSpacing

/**
 * Detailed processing-status block shown on the note detail screen.
 *
 * Surfaces the coarse state plus the current granular stage (with a spinner while running),
 * a local-vs-cloud indicator, retry / cancel affordances, and (for failures) an expandable
 * user-friendly message with the technical detail hidden behind a toggle. The whole card is
 * merged into a single TalkBack node so a screen reader announces stage + actions together.
 */
@Composable
fun ProcessingStatusCard(
    status: StatusUiModel,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val label = stringResource(labelFor(status.status))
    val retryLabel = stringResource(R.string.common_retry)
    val cancelLabel = stringResource(R.string.common_cancel)
    val running = status.status in RUNNING_STATUSES
    var showDetail by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.large),
        color = if (status.status == UserFacingStatus.FAILED ||
            status.status == UserFacingStatus.NEEDS_ATTENTION
        ) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .padding(AppSpacing.md)
                .semantics(mergeDescendants = true) {}
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(16.dp)
                            .semantics { contentDescription = label },
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.sm))
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (status.isCloud) {
                    Spacer(modifier = Modifier.width(AppSpacing.xs))
                    LocalCloudChip(R.string.status_cloud, Icons.Default.Cloud)
                }
            }

            if (running) {
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                Text(
                    text = stringResource(R.string.status_processing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            if (!running && !status.isCloud) {
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                LocalCloudChip(R.string.status_on_device, Icons.Default.PhoneAndroid)
            }

            status.message?.let { message ->
                Spacer(modifier = Modifier.height(AppSpacing.sm))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (status.detail != null && status.message != null && status.detail != status.message) {
                TextButton(
                    onClick = { showDetail = !showDetail },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                ) {
                    Text(
                        stringResource(if (showDetail) R.string.status_hide_technical else R.string.status_show_technical),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                AnimatedVisibility(
                    visible = showDetail,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Text(
                        text = status.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (status.canRetry || status.canCancel) {
                Spacer(modifier = Modifier.height(AppSpacing.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    if (status.canRetry) {
                        // Retry is also "Resume" for a paused / cancelled source.
                        OutlinedButton(
                            onClick = onRetry,
                            modifier = Modifier
                                .weight(1f)
                                .semantics { contentDescription = retryLabel }
                        ) {
                            Text(retryLabel)
                        }
                    }
                    if (status.canCancel) {
                        TextButton(
                            onClick = onCancel,
                            modifier = Modifier
                                .weight(1f)
                                .semantics { contentDescription = cancelLabel }
                        ) {
                            Text(cancelLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalCloudChip(resId: Int, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        shape = RoundedCornerShape(AppRadius.small)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AppSpacing.sm, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(10.dp)
            )
            Spacer(modifier = Modifier.width(AppSpacing.xs))
            Text(
                text = stringResource(resId),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private val RUNNING_STATUSES = setOf(
    UserFacingStatus.PROCESSING,
    UserFacingStatus.TRANSCRIBING,
    UserFacingStatus.EXTRACTING_ENTITIES,
    UserFacingStatus.EXTRACTING_TIMELINE,
    UserFacingStatus.BUILDING_SEMANTIC_INDEX,
    UserFacingStatus.PREPARING_SEARCH
)
