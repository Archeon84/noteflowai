package com.noteflowai.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Quiet Intelligence — warm paper neutral surfaces with a reserved teal→violet gradient
// reserved for AI moments (greeting, streaming). These pair with the existing semantic colors.

// Light scheme surfaces
val LightBackground = Color(0xFFFAF9F8)   // warm paper-gray
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFF4F2EF)
val LightInk = Color(0xFF1C1B1A)          // body text
val LightInkSubtle = Color(0xFF6B6560)    // secondary text
val LightHairline = Color(0xFFE8E5E0)     // hairline borders

// Dark scheme surfaces — warm near-black
val DarkBackground = Color(0xFF141312)
val DarkSurface = Color(0xFF1E1C1A)
val DarkSurfaceVariant = Color(0xFF26232022)
val DarkInk = Color(0xFFEDEBE8)
val DarkInkSubtle = Color(0xFFA29C95)
val DarkHairline = Color(0xFF2E2A26)

// Accent (reserved for AI moments; less used than the old gradient hero)
val AccentTeal = Color(0xFF0F766E)
val AccentViolet = Color(0xFF7C3AED)
val AccentTealDark = Color(0xFF5EEAD4)
val AccentVioletDark = Color(0xFFA78BFA)

// Default (modern neutral) seed — calm teal-violet pair, accessible in both themes
val DefaultPrimaryLight = AccentTeal
val DefaultPrimaryDark = AccentTealDark

// Backward-compat alias: AiAccent is still referenced by HomeScreen and other screens
val AiAccentLight = AccentViolet
val AiAccentDark = AccentVioletDark
val AiGradientLight = listOf(DefaultPrimaryLight, AiAccentLight)
val AiGradientDark = listOf(DefaultPrimaryDark, AiAccentDark)

// Pastel theme seeds (preserved for theme picker)
val LavenderPrimary = Color(0xFF7E57C2)
val LavenderPrimaryDark = Color(0xFFAB7BE0)
val LavenderSecondary = Color(0xFF6A3FA0)

val PeachPrimary = Color(0xFFE2673F)
val PeachPrimaryDark = Color(0xFFFF7D55)
val PeachSecondary = Color(0xFFC2410C)

val MintPrimary = Color(0xFF2E9E5B)
val MintPrimaryDark = Color(0xFF5BBF60)
val MintSecondary = Color(0xFF1B7A43)

val SkyPrimary = Color(0xFF2F7FD6)
val SkyPrimaryDark = Color(0xFF52A8F5)
val SkySecondary = Color(0xFF1769AA)

val SakuraPrimary = Color(0xFFD6457F)
val SakuraPrimaryDark = Color(0xFFF05E8E)
val SakuraSecondary = Color(0xFFB02A5C)

// Semantic colors — contrast-checked against their surfaces
val ErrorColor = Color(0xFFD32F2F)
val ErrorColorDark = Color(0xFFEF9A9A)
val SuccessColor = Color(0xFF2E7D32)
val SuccessColorDark = Color(0xFF81C784)
val WarningColor = Color(0xFFF57C00)
val WarningColorDark = Color(0xFFFFCC80)

// Recording & Audio semantic colors
val RecordingRed = Color(0xFFDC2626)
val RecordingRedDark = Color(0xFFEF4444)
val WaveformBlue = Color(0xFF2563EB)
val WaveformBlueDark = Color(0xFF60A5FA)

// Soft Organic Intelligence — tonal container roles for layered cards/sheets.
// Derived from the warm paper surfaces so light/dark stay in the same family.
val LightContainerLow = Color(0xFFF4F2EF)    // sunken / nested wells
val LightContainer = Color(0xFFFFFFFF)       // raised cards on paper bg
val LightContainerHigh = Color(0xFFFFFFFF)   // sheets, dialogs
val DarkContainerLow = Color(0xFF1E1C1A)     // sunken / nested wells
val DarkContainer = Color(0xFF26232022)      // raised cards
val DarkContainerHigh = Color(0xFF2E2A26)    // sheets, dialogs

// Entity tints for the Insights card constellation (replaces raw hex in graph).
// Muted enough for 4.5:1 body text on container surfaces in both themes.
val EntityTintPerson = Color(0xFF4CAF50)
val EntityTintTheme = Color(0xFF2196F3)
val EntityTintAction = Color(0xFFF57C00)
val EntityTintTemporal = Color(0xFF9C27B0)
val EntityTintTechnical = Color(0xFF00BCD4)

// Consistent text emphasis helpers (alpha kept >= 0.6 for WCAG-AA readability)
@Composable
fun secondaryText() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)

@Composable
fun hintText() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

@Composable
fun disabledText() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

private fun isLightColor(c: Color): Boolean {
    val luminance = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
    return luminance > 0.5f
}

// Public luminance check used by screens for AI-native accent/gradient choices.
fun isLightBg(color: Color): Boolean = isLightColor(color)

@Composable
fun successTextColor(): Color = if (isLightColor(MaterialTheme.colorScheme.background)) SuccessColor else SuccessColorDark

@Composable
fun errorTextColor(): Color = if (isLightColor(MaterialTheme.colorScheme.background)) ErrorColor else ErrorColorDark

@Composable
fun warningTextColor(): Color = if (isLightColor(MaterialTheme.colorScheme.background)) WarningColor else WarningColorDark

@Composable
fun recordingColor(): Color = if (isLightColor(MaterialTheme.colorScheme.background)) RecordingRed else RecordingRedDark

@Composable
fun waveformColor(): Color = if (isLightColor(MaterialTheme.colorScheme.background)) WaveformBlue else WaveformBlueDark
