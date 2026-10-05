package com.noteflowai.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * Compact processing-status pill shared by note cards (grid + recent list).
 *
 * Renders one of the [UserFacingStatus] states as a small chip with a leading indicator:
 * a subtle text-only chip for calm states, an indeterminate spinner while a granular pipeline
 * stage is running, and an error-tinted chip for failure / attention states. The single-line
 * ellipsized label keeps the pill from clipping on large system fonts.
 */
@Composable
fun ProcessingPill(status: StatusUiModel?, modifier: Modifier = Modifier) {
    if (status == null) return

    val running = status.status in RUNNING_STATUSES
    val attention = status.status == UserFacingStatus.NEEDS_ATTENTION ||
        status.status == UserFacingStatus.FAILED
    val label = stringResource(labelFor(status.status))

    val container = when {
        attention -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when {
        attention -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val accent = when {
        attention -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(
        color = container,
        shape = RoundedCornerShape(AppRadius.small),
        modifier = modifier.semantics { contentDescription = label }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AppSpacing.sm, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)
        ) {
            when {
                running -> CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                    color = accent
                )
                attention -> Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(12.dp)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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

/** String-resource id for the user-facing label of a [UserFacingStatus]. */
fun labelFor(status: UserFacingStatus): Int = when (status) {
    UserFacingStatus.SAVED_LOCALLY -> R.string.status_saved_locally
    UserFacingStatus.PROCESSING -> R.string.status_processing
    UserFacingStatus.TRANSCRIBING -> R.string.status_transcribing
    UserFacingStatus.EXTRACTING_ENTITIES -> R.string.status_extracting_entities
    UserFacingStatus.EXTRACTING_TIMELINE -> R.string.status_extracting_timeline
    UserFacingStatus.BUILDING_SEMANTIC_INDEX -> R.string.status_building_semantic_index
    UserFacingStatus.PREPARING_SEARCH -> R.string.status_preparing_search
    UserFacingStatus.READY -> R.string.status_ready
    UserFacingStatus.NEEDS_ATTENTION -> R.string.status_needs_attention
    UserFacingStatus.FAILED -> R.string.status_failed
}
