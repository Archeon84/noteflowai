package com.noteflowai.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.noteflowai.app.ui.theme.isReducedMotionEnabled

// Shared color references
@Composable
private fun primaryColor() = MaterialTheme.colorScheme.primary
@Composable
private fun primaryContainerColor() = MaterialTheme.colorScheme.primaryContainer
@Composable
private fun tertiaryColor() = MaterialTheme.colorScheme.tertiary

// ---------------------------------------------------------------------------
// Notes: floating note cards with a pencil
// ---------------------------------------------------------------------------
@Composable
fun NotesIllustration(modifier: Modifier = Modifier) {
    val primary = primaryColor()
    val container = primaryContainerColor()
    val reducedMotion = isReducedMotionEnabled()
    val infiniteTransition = rememberInfiniteTransition(label = "notes")
    val bob by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (reducedMotion) 0f else 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bob"
    )

    Canvas(modifier = modifier.size(120.dp)) {
        val w = size.width
        val h = size.height

        // Back card (rotated slightly)
        rotate(-8f, pivot = Offset(w * 0.35f, h * 0.5f)) {
            drawRoundRect(
                color = container,
                topLeft = Offset(w * 0.15f, h * 0.2f + bob),
                size = Size(w * 0.45f, h * 0.55f),
                cornerRadius = CornerRadius(12f)
            )
            // Lines on back card
            for (i in 0..2) {
                drawLine(
                    color = primary.copy(alpha = 0.2f),
                    start = Offset(w * 0.22f, h * 0.38f + i * 14f + bob),
                    end = Offset(w * 0.52f, h * 0.38f + i * 14f + bob),
                    strokeWidth = 3f
                )
            }
        }

        // Front card
        drawRoundRect(
            color = primary.copy(alpha = 0.12f),
            topLeft = Offset(w * 0.35f, h * 0.15f - bob * 0.5f),
            size = Size(w * 0.5f, h * 0.6f),
            cornerRadius = CornerRadius(12f)
        )
        // Lines on front card
        for (i in 0..3) {
            drawLine(
                color = primary.copy(alpha = 0.35f),
                start = Offset(w * 0.42f, h * 0.32f + i * 12f - bob * 0.5f),
                end = Offset(w * 0.72f, h * 0.32f + i * 12f - bob * 0.5f),
                strokeWidth = 3f
            )
        }

        // Pencil (diagonal, bottom-right)
        val pencilColor = primary
        rotate(-45f, pivot = Offset(w * 0.82f, h * 0.82f)) {
            // Pencil body
            drawRect(
                color = pencilColor,
                topLeft = Offset(w * 0.75f, h * 0.65f),
                size = Size(w * 0.06f, h * 0.25f)
            )
            // Pencil tip
            val tipPath = Path().apply {
                moveTo(w * 0.75f, h * 0.65f)
                lineTo(w * 0.78f, h * 0.58f)
                lineTo(w * 0.81f, h * 0.65f)
                close()
            }
            drawPath(tipPath, pencilColor)
        }
    }
}

// ---------------------------------------------------------------------------
// Recordings: microphone with sound waves
// ---------------------------------------------------------------------------
@Composable
fun RecordingsIllustration(modifier: Modifier = Modifier) {
    val primary = primaryColor()
    val container = primaryContainerColor()
    val reducedMotion = isReducedMotionEnabled()
    val infiniteTransition = rememberInfiniteTransition(label = "rec")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (reducedMotion) 0f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )

    Canvas(modifier = modifier.size(120.dp)) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        val cy = h * 0.45f

        // Mic body (rounded rect)
        drawRoundRect(
            color = primary,
            topLeft = Offset(cx - 10f, cy - 28f),
            size = Size(20f, 36f),
            cornerRadius = CornerRadius(10f)
        )
        // Mic base arc
        val arcPath = Path().apply {
            moveTo(cx - 18f, cy + 8f)
            arcTo(
                rect = androidx.compose.ui.geometry.Rect(cx - 22f, cy - 8f, cx + 22f, cy + 28f),
                startAngleDegrees = 0f,
                sweepAngleDegrees = 180f,
                forceMoveTo = false
            )
        }
        drawPath(arcPath, primary, style = Stroke(width = 3f, cap = StrokeCap.Round))
        // Mic stand
        drawLine(primary, Offset(cx, cy + 28f), Offset(cx, cy + 42f), strokeWidth = 3f)
        drawLine(primary, Offset(cx - 10f, cy + 42f), Offset(cx + 10f, cy + 42f), strokeWidth = 3f)

        // Sound waves (3 arcs, animated opacity)
        for (i in 1..3) {
            val waveAlpha = ((wavePhase + i * 0.33f) % 1f).coerceIn(0.15f, 0.6f)
            val radius = 30f + i * 12f
            drawArc(
                color = primary.copy(alpha = waveAlpha),
                startAngle = -40f,
                sweepAngle = 80f,
                useCenter = false,
                topLeft = Offset(cx - radius, cy - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = 2.5f, cap = StrokeCap.Round)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Scan: camera viewfinder with corner brackets
// ---------------------------------------------------------------------------
@Composable
fun ScanIllustration(modifier: Modifier = Modifier) {
    val primary = primaryColor()
    val reducedMotion = isReducedMotionEnabled()
    val infiniteTransition = rememberInfiniteTransition(label = "scan")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = if (reducedMotion) 0.85f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val scanPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (reducedMotion) 0f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scanLinePhase"
    )

    Canvas(modifier = modifier.size(120.dp)) {
        val w = size.width
        val h = size.height
        val pad = w * 0.18f
        val cornerLen = w * 0.22f
        val stroke = Stroke(width = 3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)

        val cx = w / 2f
        val cy = h / 2f

        fun drawCorner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(primary, Offset(x, y), Offset(x + cornerLen * dx, y), stroke.width, cap = stroke.cap)
            drawLine(primary, Offset(x, y), Offset(x, y + cornerLen * dy), stroke.width, cap = stroke.cap)
        }

        // Top-left
        drawCorner(pad, pad, 1f, 1f)
        // Top-right
        drawCorner(w - pad, pad, -1f, 1f)
        // Bottom-left
        drawCorner(pad, h - pad, 1f, -1f)
        // Bottom-right
        drawCorner(w - pad, h - pad, -1f, -1f)

        // Center scan line (animated)
        val scanY = pad + (h - 2 * pad) * ((scanPhase + 0.5f) % 1f)
        drawLine(
            primary.copy(alpha = 0.4f),
            Offset(pad + 4f, scanY),
            Offset(w - pad - 4f, scanY),
            strokeWidth = 1.5f
        )
    }
}

// ---------------------------------------------------------------------------
// YouTube: play button with transcript lines
// ---------------------------------------------------------------------------
@Composable
fun YouTubeIllustration(modifier: Modifier = Modifier) {
    val primary = primaryColor()
    val container = primaryContainerColor()
    val reducedMotion = isReducedMotionEnabled()
    val infiniteTransition = rememberInfiniteTransition(label = "yt")
    val glow by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = if (reducedMotion) 0.3f else 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    Canvas(modifier = modifier.size(120.dp)) {
        val w = size.width
        val h = size.height

        // Play button circle
        drawCircle(
            color = primary.copy(alpha = glow),
            radius = w * 0.22f,
            center = Offset(w * 0.38f, h * 0.42f)
        )
        drawCircle(
            color = primary,
            radius = w * 0.18f,
            center = Offset(w * 0.38f, h * 0.42f)
        )
        // Play triangle
        val playPath = Path().apply {
            moveTo(w * 0.33f, h * 0.32f)
            lineTo(w * 0.33f, h * 0.52f)
            lineTo(w * 0.48f, h * 0.42f)
            close()
        }
        drawPath(playPath, Color.White)

        // Transcript lines (right side)
        for (i in 0..4) {
            val lineWidth = when (i) {
                0 -> w * 0.35f
                1 -> w * 0.28f
                2 -> w * 0.32f
                3 -> w * 0.2f
                else -> w * 0.25f
            }
            drawLine(
                color = primary.copy(alpha = 0.25f + i * 0.05f),
                start = Offset(w * 0.58f, h * 0.25f + i * 14f),
                end = Offset(w * 0.58f + lineWidth, h * 0.25f + i * 14f),
                strokeWidth = 3f,
                cap = StrokeCap.Round
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Documents: file with magnifying glass
// ---------------------------------------------------------------------------
@Composable
fun DocumentsIllustration(modifier: Modifier = Modifier) {
    val primary = primaryColor()
    val container = primaryContainerColor()
    val reducedMotion = isReducedMotionEnabled()
    val infiniteTransition = rememberInfiniteTransition(label = "docs")
    val magnifierBob by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (reducedMotion) 0f else 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "magBob"
    )

    Canvas(modifier = modifier.size(120.dp)) {
        val w = size.width
        val h = size.height

        // Document (file shape with folded corner)
        val docLeft = w * 0.15f
        val docTop = h * 0.15f
        val docW = w * 0.5f
        val docH = h * 0.65f
        val foldSize = w * 0.12f

        val docPath = Path().apply {
            moveTo(docLeft, docTop)
            lineTo(docLeft + docW - foldSize, docTop)
            lineTo(docLeft + docW, docTop + foldSize)
            lineTo(docLeft + docW, docTop + docH)
            lineTo(docLeft, docTop + docH)
            close()
        }
        drawPath(docPath, primary.copy(alpha = 0.12f))
        // Fold triangle
        val foldPath = Path().apply {
            moveTo(docLeft + docW - foldSize, docTop)
            lineTo(docLeft + docW - foldSize, docTop + foldSize)
            lineTo(docLeft + docW, docTop + foldSize)
            close()
        }
        drawPath(foldPath, primary.copy(alpha = 0.25f))

        // Text lines on document
        for (i in 0..3) {
            val lineW = when (i) {
                0 -> docW * 0.7f
                1 -> docW * 0.85f
                2 -> docW * 0.6f
                else -> docW * 0.75f
            }
            drawLine(
                primary.copy(alpha = 0.3f),
                Offset(docLeft + 10f, docTop + foldSize + 14f + i * 12f),
                Offset(docLeft + 10f + lineW, docTop + foldSize + 14f + i * 12f),
                strokeWidth = 2.5f,
                cap = StrokeCap.Round
            )
        }

        // Magnifying glass (bottom-right, floating)
        val magCx = w * 0.72f
        val magCy = h * 0.62f + magnifierBob
        val magR = w * 0.16f

        drawCircle(
            color = primary.copy(alpha = 0.15f),
            radius = magR,
            center = Offset(magCx, magCy)
        )
        drawCircle(
            color = primary,
            radius = magR,
            center = Offset(magCx, magCy),
            style = Stroke(width = 3f)
        )
        // Handle
        drawLine(
            primary,
            Offset(magCx + magR * 0.7f, magCy + magR * 0.7f),
            Offset(magCx + magR * 1.3f, magCy + magR * 1.3f),
            strokeWidth = 3.5f,
            cap = StrokeCap.Round
        )
    }
}
