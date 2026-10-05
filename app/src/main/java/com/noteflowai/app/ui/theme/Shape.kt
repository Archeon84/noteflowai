package com.noteflowai.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Standardized UI Design System Tokens
// Clean, cohesive modern curve hierarchy:
// - extraSmall (4dp): tooltips, micro badges, menus
// - small (8dp): chips, tags, code snippets, status indicators
// - medium (12dp): text fields, buttons, action items, list cards
// - large (16dp): standard cards, dialogs, chat message bubbles, hero cards
// - extraLarge (20dp): bottom sheets, modal surfaces, floating sheets
// - full (50%): circular containers, pill indicators
object AppRadius {
    val extraSmall = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val large = 16.dp
    val extraLarge = 20.dp
    val full = RoundedCornerShape(percent = 50)
}

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(AppRadius.extraSmall),
    small = RoundedCornerShape(AppRadius.small),
    medium = RoundedCornerShape(AppRadius.medium),
    large = RoundedCornerShape(AppRadius.large),
    extraLarge = RoundedCornerShape(AppRadius.extraLarge)
)

