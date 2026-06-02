package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkSurface,
    onPrimaryContainer = DarkOnSurface,
    secondary = DarkPrimary,
    onSecondary = DarkOnPrimary,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurface,
    onSurfaceVariant = DarkOnBackground,
    outline = DarkBorder,
    error = Color(0xFFFF453A)
)

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightSurface,
    onPrimaryContainer = LightOnSurface,
    secondary = LightPrimary,
    onSecondary = LightOnPrimary,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurface,
    onSurfaceVariant = LightOnBackground,
    outline = LightBorder,
    error = Color(0xFFFF3B30)
)

/**
 * Accessor for glassy colors that fits either Light or Dark styles depending on the active scheme.
 */
val MaterialTheme.glassColor: Color
    @Composable
    get() = if (colorScheme.background == DarkBackground) DarkGlass else LightGlass

val MaterialTheme.borderColor: Color
    @Composable
    get() = if (colorScheme.background == DarkBackground) DarkBorder else LightBorder

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // DynamicLight/DarkColorScheme yields generic colored styles. 
    // To preserve our strictly customized, high-end pure minimal palette, 
    // we bypass dynamic colors and strictly use our premium Light / Dark schemes.
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
