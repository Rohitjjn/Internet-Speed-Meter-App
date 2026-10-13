package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkCardSolid,
    onPrimaryContainer = DarkTextPrimary,
    secondary = SpeedUpDark,
    onSecondary = Color.Black,
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkCardSolid,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkCardSolid,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkBorder,
    error = AccentDangerDark,
)

private val LightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightCardSolid,
    onPrimaryContainer = LightTextPrimary,
    secondary = SpeedUp,
    onSecondary = Color.White,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightCardSolid,
    onSurface = LightTextPrimary,
    surfaceVariant = LightCardSolid,
    onSurfaceVariant = LightTextSecondary,
    outline = LightBorder,
    error = AccentDanger,
)

/** Frosted glass fill for cards in the active mode. */
val LocalGlassColor = staticCompositionLocalOf { LightCard }
val LocalGlassBorder = staticCompositionLocalOf { LightBorder }
val LocalGlassHighlight = staticCompositionLocalOf { LightHighlight }

/** Whether the ACTIVE theme is dark (respects the user override, not just the system). */
val LocalDarkTheme = staticCompositionLocalOf { false }

@Composable
fun isDarkModeActive(): Boolean = LocalDarkTheme.current

val glassColor: Color
    @Composable get() = LocalGlassColor.current

val glassBorder: Color
    @Composable get() = LocalGlassBorder.current

val glassHighlight: Color
    @Composable get() = LocalGlassHighlight.current

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean,
    accent: AccentTheme = AccentTheme.INK,
    content: @Composable () -> Unit,
) {
    val isDark = darkTheme
    MaterialTheme(
        colorScheme = withAccent(if (isDark) DarkColors else LightColors, accent, isDark),
        typography = Typography,
        content = {
            CompositionLocalProvider(
                LocalGlassColor provides (if (isDark) DarkCard else LightCard),
                LocalGlassBorder provides (if (isDark) DarkBorder else LightBorder),
                LocalGlassHighlight provides (if (isDark) DarkHighlight else LightHighlight),
                LocalDarkTheme provides isDark,
            ) {
                content()
            }
        }
    )
}

/** Applies the user's accent to the Material 3 scheme (only primary/onPrimary change). */
private fun withAccent(scheme: ColorScheme, accent: AccentTheme, dark: Boolean): ColorScheme {
    val primary = when (accent) {
        AccentTheme.INK -> return scheme
        AccentTheme.GREEN -> if (dark) SpeedDownDark else SpeedDown
        AccentTheme.BLUE -> if (dark) SpeedUpDark else SpeedUp
        AccentTheme.VIOLET -> if (dark) AccentVioletDark else AccentViolet
    }
    return scheme.copy(primary = primary, onPrimary = Color.White)
}
