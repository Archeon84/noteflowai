package com.noteflowai.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.noteflowai.app.R
import androidx.core.view.WindowCompat

private fun mix(c: Color, target: Color, ratio: Float) = Color(
    red = c.red + (target.red - c.red) * ratio,
    green = c.green + (target.green - c.green) * ratio,
    blue = c.blue + (target.blue - c.blue) * ratio,
    alpha = c.alpha
)
private fun darken(c: Color, ratio: Float) = mix(c, Color.Black, ratio)
private fun lighten(c: Color, ratio: Float) = mix(c, Color.White, ratio)

// Choose black/white text for a given background based on its luminance.
private fun onColorFor(background: Color): Color {
    val luminance = 0.299f * background.red + 0.587f * background.green + 0.114f * background.blue
    return if (luminance > 0.5f) Color.Black else Color.White
}

// Builds a complete, harmonized, high-contrast Material3 scheme from a primary seed.
private fun buildScheme(primary: Color, dark: Boolean, secondaryOverride: Color? = null): ColorScheme {
    val secondary = secondaryOverride ?: if (dark) lighten(primary, 0.18f) else darken(primary, 0.08f)
    val onPrimary = onColorFor(primary)
    val onSecondary = onColorFor(secondary)
    return if (dark) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = darken(primary, 0.3f).copy(alpha = 0.35f),
            onPrimaryContainer = lighten(primary, 0.62f),
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = darken(secondary, 0.3f).copy(alpha = 0.35f),
            onSecondaryContainer = lighten(secondary, 0.62f),
            background = DarkBackground,
            surface = DarkSurface,
            surfaceVariant = DarkSurfaceVariant,
            onSurface = DarkInk,
            onSurfaceVariant = DarkInkSubtle,
            outline = DarkHairline,
            outlineVariant = DarkHairline.copy(alpha = 0.6f),
            scrim = Color.Black.copy(alpha = 0.55f),
            error = ErrorColorDark,
            onError = Color(0xFF1A0E0E),
            errorContainer = ErrorColorDark.copy(alpha = 0.22f),
            onErrorContainer = lighten(ErrorColorDark, 0.35f)
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = darken(primary, 0.4f).copy(alpha = 0.28f),
            onPrimaryContainer = darken(primary, 0.5f),
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = darken(secondary, 0.4f).copy(alpha = 0.28f),
            onSecondaryContainer = darken(secondary, 0.5f),
            background = LightBackground,
            surface = LightSurface,
            surfaceVariant = LightSurfaceVariant,
            onSurface = LightInk,
            onSurfaceVariant = LightInkSubtle,
            outline = LightHairline,
            outlineVariant = LightHairline.copy(alpha = 0.6f),
            scrim = Color.Black.copy(alpha = 0.42f),
            error = ErrorColor,
            onError = Color.White,
            errorContainer = ErrorColor.copy(alpha = 0.12f),
            onErrorContainer = darken(ErrorColor, 0.1f)
        )
    }
}

@Composable
fun NoteFlowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    themeName: String = "Default",
    fontName: String = "Default",
    content: @Composable () -> Unit
) {
    // Dynamic (system) scheme must be created in composable scope, so compute it
    // here (cheap, only for the "Default" theme on Android 12+) and cache
    // the final result with remember to avoid rebuilding on every recomposition.
    val dynamicScheme = if (themeName == "Default" && dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(LocalContext.current)
        else dynamicLightColorScheme(LocalContext.current)
    } else null

    val colorScheme = remember(themeName, darkTheme, dynamicColor, dynamicScheme) {
        dynamicScheme ?: when (themeName) {
            "Lavender" -> buildScheme(if (darkTheme) LavenderPrimaryDark else LavenderPrimary, darkTheme, LavenderSecondary)
            "Peach" -> buildScheme(if (darkTheme) PeachPrimaryDark else PeachPrimary, darkTheme, PeachSecondary)
            "Mint" -> buildScheme(if (darkTheme) MintPrimaryDark else MintPrimary, darkTheme, MintSecondary)
            "Sky" -> buildScheme(if (darkTheme) SkyPrimaryDark else SkyPrimary, darkTheme, SkySecondary)
            "Sakura" -> buildScheme(if (darkTheme) SakuraPrimaryDark else SakuraPrimary, darkTheme, SakuraSecondary)
            else -> if (darkTheme) buildScheme(DefaultPrimaryDark, true) else buildScheme(DefaultPrimaryLight, false)
        }
    }

    val headingFont: FontFamily? = remember(fontName) {
        when (fontName) {
        "Default", "Grotesk/Sans", "Space Grotesk" -> FontFamily(Font(R.font.space_grotesk_bold))
        "DM Sans" -> FontFamily(Font(R.font.dm_sans_bold))
        "Montserrat" -> FontFamily(Font(R.font.montserrat_regular))
        "Playfair" -> FontFamily(Font(R.font.playfair_regular))
        "Pacifico" -> FontFamily(Font(R.font.pacifico_regular))
        "Roboto" -> FontFamily(Font(R.font.roboto_regular))
        "Open Sans" -> FontFamily(Font(R.font.opensans_regular))
        "Lora" -> FontFamily(Font(R.font.lora_regular))
        "Oswald" -> FontFamily(Font(R.font.oswald_regular))
        "Kanit" -> FontFamily(Font(R.font.kanit_regular))
        "Bebas Neue" -> FontFamily(Font(R.font.bebasneue_regular))
        "Dancing Script" -> FontFamily(Font(R.font.dancingscript_regular))
        "Tech" -> FontFamily.Monospace
        "Elegant" -> FontFamily.Serif
        "Artistic" -> FontFamily.Cursive
        else -> null
        }
    }
    val bodyFont: FontFamily = remember(fontName) {
        when (fontName) {
        "Grotesk/Sans" -> FontFamily(Font(R.font.dm_sans_regular))
        "Space Grotesk" -> FontFamily(Font(R.font.space_grotesk_regular))
        "DM Sans" -> FontFamily(Font(R.font.dm_sans_regular))
        "Montserrat" -> FontFamily(Font(R.font.montserrat_regular))
        "Playfair" -> FontFamily(Font(R.font.playfair_regular))
        "Pacifico" -> FontFamily(Font(R.font.pacifico_regular))
        "Roboto" -> FontFamily(Font(R.font.roboto_regular))
        "Open Sans" -> FontFamily(Font(R.font.opensans_regular))
        "Lora" -> FontFamily(Font(R.font.lora_regular))
        "Oswald" -> FontFamily(Font(R.font.oswald_regular))
        "Kanit" -> FontFamily(Font(R.font.kanit_regular))
        "Bebas Neue" -> FontFamily(Font(R.font.bebasneue_regular))
        "Dancing Script" -> FontFamily(Font(R.font.dancingscript_regular))
        "Tech" -> FontFamily.Monospace
        "Elegant" -> FontFamily.Serif
        "Artistic" -> FontFamily.Cursive
        else -> FontFamily.Default
        }
    }

    val typography = remember(bodyFont, headingFont) {
        Typography(
        displayLarge = TextStyle(fontFamily = headingFont, fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        displayMedium = TextStyle(fontFamily = headingFont, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
        headlineLarge = TextStyle(fontFamily = headingFont, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        headlineMedium = TextStyle(fontFamily = headingFont, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        headlineSmall = TextStyle(fontFamily = headingFont, fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
        titleLarge = TextStyle(fontFamily = headingFont, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
        titleMedium = TextStyle(fontFamily = bodyFont, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
        titleSmall = TextStyle(fontFamily = bodyFont, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        bodyLarge = TextStyle(fontFamily = bodyFont, fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontFamily = bodyFont, fontSize = 15.sp, lineHeight = 22.sp),
        bodySmall = TextStyle(fontFamily = bodyFont, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
        labelLarge = TextStyle(fontFamily = bodyFont, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
        labelMedium = TextStyle(fontFamily = bodyFont, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
        labelSmall = TextStyle(fontFamily = bodyFont, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp)
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            @Suppress("DEPRECATION")
            window.statusBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        shapes = AppShapes,
        content = content
    )
}
