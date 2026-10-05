package com.noteflowai.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.noteflowai.app.ui.theme.AppRadius
import com.noteflowai.app.ui.theme.AppSpacing

/**
 * Phase 1 Fresh kit — feedback: empty states, skeletons, sheets.
 *
 * Wraps the proven [EmptyState] / Shimmer primitives with the
 * Soft Organic Intelligence voice: 32dp sheet tops, pill CTAs,
 * and action slots that meet the 48dp touch target.
 */

/** Empty state with a pill CTA. Delegates animation to [EmptyState]. */
@Composable
fun FreshEmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    illustration: (@Composable () -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    EmptyState(
        icon = icon,
        title = title,
        message = message,
        modifier = modifier,
        illustration = illustration,
        action = if (actionLabel != null && onAction != null) {
            {
                Button(
                    onClick = onAction,
                    shape = RoundedCornerShape(AppRadius.medium),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text(actionLabel)
                }
            }
        } else {
            null
        }
    )
}

/** Rounded skeleton card matching [FreshCard] geometry for loading states. */
@Composable
fun FreshSkeletonCard(modifier: Modifier = Modifier) {
    ShimmerCard(modifier = modifier)
}

/** Skeleton list with the Fresh 12dp rhythm. */
@Composable
fun FreshSkeletonList(
    count: Int = 4,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
    ) {
        repeat(count) {
            ShimmerCard(modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Skeleton section: header line + card, for detail screens. */
@Composable
fun FreshSkeletonSection(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        ShimmerText(widthFraction = 0.4f, height = 20.dp)
        Spacer(modifier = Modifier.height(AppSpacing.sm))
        ShimmerCard(modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Standard bottom sheet: 32dp top corners, surface container, single
 * dismiss entry point. Content slots into a padded column.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreshSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(
            topStart = AppRadius.extraLarge,
            topEnd = AppRadius.extraLarge
        ),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.lg)
                .padding(bottom = AppSpacing.xl)
        ) {
            content()
        }
    }
}

/** Small semibold sheet title following the Fresh voice. */
@Composable
fun FreshSheetTitle(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
    )
}
