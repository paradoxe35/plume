package me.pngwasi.plume.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import me.pngwasi.plume.data.ThemeMode

/**
 * Paper and ink: a warm off-white ground, deep teal ink for actions, terracotta for accents.
 * Flat fills only — no gradients anywhere in the app.
 *
 * Dynamic colour is deliberately not used: Plume's surfaces appear on top of other apps, and a
 * stable identity reads better there than one that shifts with the user's wallpaper.
 */
private val InkTeal = Color(0xFF1E4D4A)
private val InkTealBright = Color(0xFF7FD1C4)
private val Terracotta = Color(0xFFB4571F)
private val TerracottaSoft = Color(0xFFE8A87C)

private val LightColors = lightColorScheme(
    primary = InkTeal,
    onPrimary = Color(0xFFF7F6F3),
    primaryContainer = Color(0xFFD3E7E3),
    onPrimaryContainer = Color(0xFF0B2422),
    secondary = Terracotta,
    onSecondary = Color(0xFFFDF6F1),
    secondaryContainer = Color(0xFFF6DFCE),
    onSecondaryContainer = Color(0xFF3B1B08),
    background = Color(0xFFF7F6F3),
    onBackground = Color(0xFF16211F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF16211F),
    surfaceVariant = Color(0xFFEDEBE6),
    onSurfaceVariant = Color(0xFF4E5B58),
    outline = Color(0xFFBFC6C3),
    outlineVariant = Color(0xFFDDE2E0),
    error = Color(0xFF9B2C22),
    onError = Color(0xFFFFF6F4),
    errorContainer = Color(0xFFF7DAD5),
    onErrorContainer = Color(0xFF3F0F0A),
    scrim = Color(0xFF000000),
)

private val DarkColors = darkColorScheme(
    primary = InkTealBright,
    onPrimary = Color(0xFF06201D),
    primaryContainer = Color(0xFF1B3E3A),
    onPrimaryContainer = Color(0xFFB9E7E0),
    secondary = TerracottaSoft,
    onSecondary = Color(0xFF37190A),
    secondaryContainer = Color(0xFF593018),
    onSecondaryContainer = Color(0xFFF6DFCE),
    background = Color(0xFF101413),
    onBackground = Color(0xFFE6EBE9),
    surface = Color(0xFF171C1B),
    onSurface = Color(0xFFE6EBE9),
    surfaceVariant = Color(0xFF232A29),
    onSurfaceVariant = Color(0xFFB2BEBB),
    outline = Color(0xFF3D4746),
    outlineVariant = Color(0xFF2A3231),
    error = Color(0xFFF2B8B0),
    onError = Color(0xFF3F0F0A),
    errorContainer = Color(0xFF6B1D15),
    onErrorContainer = Color(0xFFF7DAD5),
    scrim = Color(0xFF000000),
)

/**
 * Amber, for "you need to do something" as distinct from "something broke".
 *
 * Material 3 has no warning role, and the two nearest fits are both wrong: `error` red reads as a
 * failure the user caused, and the secondary container is already what an ordinary unfinished
 * setup looks like. A withheld permission is neither.
 */
@Immutable
data class WarningColors(val container: Color, val onContainer: Color, val accent: Color)

private val LightWarning = WarningColors(
    container = Color(0xFFFBEBCC),
    onContainer = Color(0xFF4A3005),
    accent = Color(0xFFB0740F),
)

private val DarkWarning = WarningColors(
    container = Color(0xFF4A3610),
    onContainer = Color(0xFFFBEBCC),
    accent = Color(0xFFE8C07C),
)

val LocalWarningColors = staticCompositionLocalOf { LightWarning }

@Composable
fun PlumeTheme(
    mode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    CompositionLocalProvider(LocalWarningColors provides if (dark) DarkWarning else LightWarning) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = PlumeTypography,
            content = content,
        )
    }
}
