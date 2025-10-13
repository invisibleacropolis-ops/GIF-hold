package com.gifvision.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = MidnightBlue,
    secondary = PrismMagenta,
    onSecondary = MidnightBlue,
    tertiary = AuroraPurple,
    background = MidnightBlue,
    onBackground = SnowWhite,
    surface = DeepSurface,
    onSurface = SnowWhite,
    surfaceVariant = SlateGrey,
    onSurfaceVariant = MistGrey
)

private val LightColorScheme = lightColorScheme(
    primary = AuroraPurple,
    onPrimary = SnowWhite,
    secondary = PrismMagenta,
    onSecondary = SnowWhite,
    tertiary = NeonCyan,
    background = SnowWhite,
    onBackground = MidnightBlue,
    surface = SnowWhite,
    onSurface = MidnightBlue,
    surfaceVariant = MistGrey,
    onSurfaceVariant = SlateGrey
)

private val HighContrastDarkColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = EclipseBlack,
    secondary = NeonAmber,
    onSecondary = EclipseBlack,
    tertiary = PrismMagenta,
    background = EclipseBlack,
    onBackground = LuminousWhite,
    surface = GalacticNavy,
    onSurface = LuminousWhite,
    surfaceVariant = SlateGrey,
    onSurfaceVariant = NeonAmber
)

private val HighContrastLightColorScheme = lightColorScheme(
    primary = NeonCyan,
    onPrimary = EclipseBlack,
    secondary = PrismMagenta,
    onSecondary = SnowWhite,
    tertiary = NeonAmber,
    background = LuminousWhite,
    onBackground = EclipseBlack,
    surface = SnowWhite,
    onSurface = EclipseBlack,
    surfaceVariant = MistGrey,
    onSurfaceVariant = EclipseBlack
)

/**
 * Material3 theme wrapper that selects between light/dark and high-contrast palettes. The host can
 * opt into [highContrast] to satisfy accessibility requirements while retaining automatic dark
 * theme detection from the system.
 */
@Composable
fun GifVisionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    highContrast: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        highContrast && darkTheme -> HighContrastDarkColorScheme
        highContrast && !darkTheme -> HighContrastLightColorScheme
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}