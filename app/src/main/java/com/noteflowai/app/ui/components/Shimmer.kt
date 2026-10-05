package com.noteflowai.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.noteflowai.app.ui.theme.AppRadius
import com.noteflowai.app.ui.theme.isReducedMotionEnabled

@Composable
fun shimmerBrush(): Brush {
    val reducedMotion = isReducedMotionEnabled()
    val transition = rememberInfiniteTransition()
    val translate = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (reducedMotion) 1 else 1200,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        )
    )
    val base = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val mid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    return Brush.linearGradient(
        0.0f to base,
        0.5f to mid,
        1.0f to base,
        start = Offset.Zero,
        end = Offset(x = translate.value, y = translate.value)
    )
}

@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(6.dp),
    brush: Brush? = null
) {
    // Hoisted brush lets a whole skeleton share ONE infinite transition
    // instead of spawning one animator per sub-element (was 20+ per skeleton).
    val b = brush ?: shimmerBrush()
    Box(
        modifier = modifier
            .clip(shape)
            .background(b)
    )
}

@Composable
fun ShimmerText(
    widthFraction: Float = 0.7f,
    height: Dp = 16.dp,
    modifier: Modifier = Modifier,
    brush: Brush? = null
) {
    ShimmerBox(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .height(height),
        shape = RoundedCornerShape(4.dp),
        brush = brush
    )
}

@Composable
fun ShimmerCircle(
    size: Dp = 48.dp,
    modifier: Modifier = Modifier,
    brush: Brush? = null
) {
    ShimmerBox(
        modifier = modifier.size(size),
        shape = CircleShape,
        brush = brush
    )
}

@Composable
fun ShimmerCard(modifier: Modifier = Modifier) {
    val brush = shimmerBrush()
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(AppRadius.large),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            ShimmerCircle(size = 48.dp, brush = brush)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                ShimmerText(widthFraction = 0.7f, height = 18.dp, brush = brush)
                Spacer(Modifier.height(10.dp))
                ShimmerText(widthFraction = 0.95f, height = 12.dp, brush = brush)
                Spacer(Modifier.height(6.dp))
                ShimmerText(widthFraction = 0.85f, height = 12.dp, brush = brush)
            }
        }
    }
}

@Composable
fun NoteShimmerCard(modifier: Modifier = Modifier) {
    val brush = shimmerBrush()
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(AppRadius.large),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            ShimmerText(widthFraction = 0.6f, height = 14.dp, brush = brush)
            Spacer(Modifier.height(8.dp))
            ShimmerText(widthFraction = 0.9f, height = 12.dp, brush = brush)
            Spacer(Modifier.height(6.dp))
            ShimmerText(widthFraction = 0.75f, height = 12.dp, brush = brush)
            Spacer(Modifier.height(10.dp))
            Row {
                ShimmerBox(
                    modifier = Modifier
                        .width(60.dp)
                        .height(20.dp),
                    shape = RoundedCornerShape(10.dp),
                    brush = brush
                )
                Spacer(Modifier.width(8.dp))
                ShimmerBox(
                    modifier = Modifier
                        .width(40.dp)
                        .height(20.dp),
                    shape = RoundedCornerShape(10.dp),
                    brush = brush
                )
            }
        }
    }
}

@Composable
fun ListSkeleton(count: Int = 4, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(count) { ShimmerCard() }
    }
}

@Composable
fun NoteListSkeleton(count: Int = 4, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(count) { NoteShimmerCard() }
    }
}

@Composable
fun ChatMessageSkeleton(isUser: Boolean = true, modifier: Modifier = Modifier) {
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val widthFraction = if (isUser) 0.6f else 0.75f
    val brush = shimmerBrush()

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        if (!isUser) {
            Row {
                ShimmerCircle(size = 32.dp, brush = brush)
                Spacer(Modifier.width(8.dp))
                Column {
                    ShimmerText(widthFraction = widthFraction, height = 14.dp, brush = brush)
                    Spacer(Modifier.height(4.dp))
                    ShimmerText(widthFraction = widthFraction * 0.8f, height = 12.dp, brush = brush)
                }
            }
        } else {
            ShimmerBox(
                modifier = Modifier
                    .fillMaxWidth(widthFraction)
                    .height(40.dp),
                shape = RoundedCornerShape(16.dp),
                brush = brush
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Screen-specific content-aware skeletons
// ---------------------------------------------------------------------------

/** Mimics HomeScreen: hero card + quick action row + recent section */
@Composable
fun HomeDashboardSkeleton(modifier: Modifier = Modifier) {
    val brush = shimmerBrush()
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero card (large)
        ShimmerBox(
            modifier = Modifier.fillMaxWidth().height(140.dp),
            shape = RoundedCornerShape(AppRadius.large),
            brush = brush
        )
        // Quick actions row
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(4) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ShimmerCircle(size = 52.dp, brush = brush)
                    Spacer(Modifier.height(6.dp))
                    ShimmerBox(
                        modifier = Modifier.width(48.dp).height(12.dp),
                        shape = RoundedCornerShape(4.dp),
                        brush = brush
                    )
                }
            }
        }
        // Recent section header
        ShimmerText(widthFraction = 0.4f, height = 16.dp, brush = brush)
        // Recent items
        repeat(2) { ShimmerCard() }
    }
}

/** Mimics NoteDetail: title block + body text lines */
@Composable
fun NoteDetailSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Title
        ShimmerText(widthFraction = 0.65f, height = 22.dp)
        // Metadata row (date, tags)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShimmerBox(
                modifier = Modifier.width(80.dp).height(16.dp),
                shape = RoundedCornerShape(8.dp)
            )
            ShimmerBox(
                modifier = Modifier.width(60.dp).height(16.dp),
                shape = RoundedCornerShape(8.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        // Body lines
        ShimmerText(widthFraction = 0.95f, height = 14.dp)
        ShimmerText(widthFraction = 0.88f, height = 14.dp)
        ShimmerText(widthFraction = 0.92f, height = 14.dp)
        ShimmerText(widthFraction = 0.7f, height = 14.dp)
        Spacer(Modifier.height(8.dp))
        ShimmerText(widthFraction = 0.85f, height = 14.dp)
        ShimmerText(widthFraction = 0.78f, height = 14.dp)
    }
}

/** Mimics YouTube: thumbnail + title + transcript lines */
@Composable
fun YouTubeSkeleton(modifier: Modifier = Modifier) {
    val brush = shimmerBrush()
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Thumbnail placeholder
        ShimmerBox(
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            shape = RoundedCornerShape(AppRadius.large),
            brush = brush
        )
        // Title
        ShimmerText(widthFraction = 0.75f, height = 18.dp, brush = brush)
        // Channel row
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShimmerCircle(size = 24.dp, brush = brush)
            Spacer(Modifier.width(8.dp))
            ShimmerText(widthFraction = 0.35f, height = 12.dp, brush = brush)
        }
        Spacer(Modifier.height(4.dp))
        // Transcript lines
        ShimmerText(widthFraction = 0.95f, height = 12.dp, brush = brush)
        ShimmerText(widthFraction = 0.88f, height = 12.dp, brush = brush)
        ShimmerText(widthFraction = 0.92f, height = 12.dp, brush = brush)
        ShimmerText(widthFraction = 0.65f, height = 12.dp, brush = brush)
    }
}

/** Mimics Document: file info card + extracted text block */
@Composable
fun DocumentSkeleton(modifier: Modifier = Modifier) {
    val brush = shimmerBrush()
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // File picker button
        ShimmerBox(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(AppRadius.large),
            brush = brush
        )
        // File info card
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(AppRadius.large),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ShimmerCircle(size = 20.dp, brush = brush)
                    Spacer(Modifier.width(8.dp))
                    ShimmerText(widthFraction = 0.5f, height = 16.dp, brush = brush)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShimmerBox(
                        modifier = Modifier.width(60.dp).height(20.dp),
                        shape = RoundedCornerShape(10.dp),
                        brush = brush
                    )
                    ShimmerBox(
                        modifier = Modifier.width(80.dp).height(20.dp),
                        shape = RoundedCornerShape(10.dp),
                        brush = brush
                    )
                }
            }
        }
        // Text block
        ShimmerText(widthFraction = 0.6f, height = 16.dp, brush = brush)
        ShimmerText(widthFraction = 0.95f, height = 12.dp, brush = brush)
        ShimmerText(widthFraction = 0.88f, height = 12.dp, brush = brush)
        ShimmerText(widthFraction = 0.92f, height = 12.dp, brush = brush)
    }
}
