package com.sasayaki.player.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.sasayaki.player.data.ThemeMode

// Soft rose / lavender palette, tuned for late-night listening.
private val Dark = darkColorScheme(
    primary = Color(0xFFF2B5C8),
    onPrimary = Color(0xFF4C1D2E),
    primaryContainer = Color(0xFF663345),
    onPrimaryContainer = Color(0xFFFFD9E3),
    secondary = Color(0xFFCDBEEB),
    onSecondary = Color(0xFF34284D),
    secondaryContainer = Color(0xFF4B3F65),
    onSecondaryContainer = Color(0xFFE9DDFF),
    tertiary = Color(0xFFA9CDE8),
    onTertiary = Color(0xFF0E344B),
    tertiaryContainer = Color(0xFF2A4B63),
    onTertiaryContainer = Color(0xFFCDE5FF),
    background = Color(0xFF141216),
    onBackground = Color(0xFFE8E0E6),
    surface = Color(0xFF141216),
    onSurface = Color(0xFFE8E0E6),
    surfaceVariant = Color(0xFF4D444A),
    onSurfaceVariant = Color(0xFFD0C3CA),
    surfaceContainerLowest = Color(0xFF0F0D10),
    surfaceContainerLow = Color(0xFF1C1A1E),
    surfaceContainer = Color(0xFF211E22),
    surfaceContainerHigh = Color(0xFF2B282D),
    surfaceContainerHighest = Color(0xFF363338),
    outline = Color(0xFF998D94),
    outlineVariant = Color(0xFF4D444A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val Light = lightColorScheme(
    primary = Color(0xFF8A4A5E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E3),
    onPrimaryContainer = Color(0xFF380719),
    secondary = Color(0xFF64577E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9DDFF),
    onSecondaryContainer = Color(0xFF1F1537),
    tertiary = Color(0xFF3D6480),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCDE5FF),
    onTertiaryContainer = Color(0xFF001E31),
    background = Color(0xFFFFF8F9),
    onBackground = Color(0xFF201A1C),
    surface = Color(0xFFFFF8F9),
    onSurface = Color(0xFF201A1C),
    surfaceVariant = Color(0xFFF2DDE2),
    onSurfaceVariant = Color(0xFF514347),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFCF0F3),
    surfaceContainer = Color(0xFFF6EAED),
    surfaceContainerHigh = Color(0xFFF0E4E7),
    surfaceContainerHighest = Color(0xFFEADFE2),
    outline = Color(0xFF837377),
    outlineVariant = Color(0xFFD5C2C6),
)

@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

/** Tones of [argb] for a color role, its on-color, its container and on-container, using Material's tone assignments. */
private fun roleTones(argb: Int, dark: Boolean, minChroma: Double, maxChroma: Double): List<Color> =
    (if (dark) listOf(80.0, 20.0, 30.0, 90.0) else listOf(40.0, 100.0, 90.0, 10.0))
        .map { Color(Tones.tone(argb, it, maxChroma, minChroma)) }

/** Replaces the primary roles with tones of [argb], at least [minChroma] colorful where sRGB allows. */
fun ColorScheme.withAccent(argb: Int?, dark: Boolean, minChroma: Double = 0.0): ColorScheme {
    if (argb == null) return this
    val (c, on, container, onContainer) = roleTones(argb, dark, minChroma, Double.MAX_VALUE)
    return copy(primary = c, onPrimary = on, primaryContainer = container, onPrimaryContainer = onContainer)
}

/** Replaces the secondary roles with tones of [argb], with chroma limited to [chroma] where given. */
fun ColorScheme.withSecondary(argb: Int?, dark: Boolean, chroma: ClosedFloatingPointRange<Double>? = null): ColorScheme {
    if (argb == null) return this
    val (c, on, container, onContainer) = roleTones(argb, dark, chroma?.start ?: 0.0, chroma?.endInclusive ?: Double.MAX_VALUE)
    return copy(secondary = c, onSecondary = on, secondaryContainer = container, onSecondaryContainer = onContainer)
}

@Composable
fun SasayakiTheme(mode: ThemeMode, dynamic: Boolean, accent: Int?, secondary: Int?, content: @Composable () -> Unit) {
    val dark = isDark(mode)
    val ctx = LocalContext.current
    val base = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    val scheme = remember(base, accent, secondary, dark) { base.withAccent(accent, dark).withSecondary(secondary, dark) }
    CompositionLocalProvider(LocalBaseColorScheme provides base) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** The active palette before the user's accent and secondary colors, which pickers fall back to on reset. */
val LocalBaseColorScheme = staticCompositionLocalOf { Dark }
