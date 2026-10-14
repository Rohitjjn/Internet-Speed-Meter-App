package com.example.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

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

/**
 * `true` when the user asked for reduced motion (Settings → Theme): decorative animations
 * (splash zoom, breathing "waiting" label, digit roll) are skipped; state changes still
 * render, just without the choreography. Measurement cadence is never affected.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun isReducedMotion(): Boolean = LocalReducedMotion.current

@Composable
fun isDarkModeActive(): Boolean = LocalDarkTheme.current

val glassColor: Color
    @Composable get() = LocalGlassColor.current

val glassBorder: Color
    @Composable get() = LocalGlassBorder.current

val glassHighlight: Color
    @Composable get() = LocalGlassHighlight.current

/**
 * User-selectable accent for the glass theme.
 *
 * Persisted as its [key] string, so the data layer never has to know about UI types;
 * [fromKey] degrades to [INK] for anything unknown (old installs, corrupted prefs).
 */
enum class AccentTheme(val key: String) {
    INK("INK"),
    GREEN("GREEN"),
    BLUE("BLUE"),
    VIOLET("VIOLET"),

    /** Material You: the wallpaper-derived primary (Android 12+); behaves like [INK] below. */
    SYSTEM("SYSTEM");

    companion object {
        fun fromKey(key: String?): AccentTheme =
            entries.firstOrNull { it.key == key } ?: INK
    }
}

/**
 * The wallpaper-derived primary/onPrimary pair (Material You), or null below Android 12.
 * Only the primary is borrowed — the glass surfaces, text and status colours stay the
 * app's own so the theme remains legible on every wallpaper.
 */
@Composable
fun dynamicPrimary(dark: Boolean): Pair<Color, Color>? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val context = LocalContext.current
    val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    return scheme.primary to scheme.onPrimary
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean,
    accent: AccentTheme = AccentTheme.INK,
    reducedMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val isDark = darkTheme
    val base = if (isDark) DarkColors else LightColors
    val scheme = if (accent == AccentTheme.SYSTEM) {
        val dynamic = dynamicPrimary(isDark)
        if (dynamic != null) base.copy(primary = dynamic.first, onPrimary = dynamic.second) else base
    } else {
        withAccent(base, accent, isDark)
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography,
        content = {
            CompositionLocalProvider(
                LocalGlassColor provides (if (isDark) DarkCard else LightCard),
                LocalGlassBorder provides (if (isDark) DarkBorder else LightBorder),
                LocalGlassHighlight provides (if (isDark) DarkHighlight else LightHighlight),
                LocalDarkTheme provides isDark,
                LocalReducedMotion provides reducedMotion,
            ) {
                content()
            }
        }
    )
}

/** Applies the user's accent to the Material 3 scheme (only primary/onPrimary change). */
private fun withAccent(scheme: ColorScheme, accent: AccentTheme, dark: Boolean): ColorScheme {
    val primary = when (accent) {
        AccentTheme.INK, AccentTheme.SYSTEM -> return scheme
        AccentTheme.GREEN -> if (dark) SpeedDownDark else SpeedDown
        AccentTheme.BLUE -> if (dark) SpeedUpDark else SpeedUp
        AccentTheme.VIOLET -> if (dark) AccentVioletDark else AccentViolet
    }
    return scheme.copy(primary = primary, onPrimary = Color.White)
}
