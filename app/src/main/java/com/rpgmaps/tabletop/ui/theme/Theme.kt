package com.rpgmaps.tabletop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Deliberately dark by default. The DM is usually the only lit screen at a
 * table playing in low light, and a bright UI next to a dim TV map is
 * unpleasant to look at for three hours.
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF9EC5FF),
    onPrimary = Color(0xFF00325B),
    primaryContainer = Color(0xFF1B3A5C),
    onPrimaryContainer = Color(0xFFD3E4FF),
    secondary = Color(0xFFD5A45B),
    onSecondary = Color(0xFF3D2A00),
    secondaryContainer = Color(0xFF56400F),
    onSecondaryContainer = Color(0xFFFFDEA8),
    tertiary = Color(0xFFA5D6A7),
    background = Color(0xFF0D1017),
    onBackground = Color(0xFFE2E6EE),
    surface = Color(0xFF12161F),
    onSurface = Color(0xFFE2E6EE),
    surfaceVariant = Color(0xFF1D232F),
    onSurfaceVariant = Color(0xFFB6BECD),
    outline = Color(0xFF4A5261),
    error = Color(0xFFFFB4AB),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF29558B),
    secondary = Color(0xFF6E5424),
    background = Color(0xFFF7F9FD),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun RpgMapsTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}

/** Convenience for callers that want to follow the system setting instead. */
@Composable
fun RpgMapsThemeFollowingSystem(content: @Composable () -> Unit) =
    RpgMapsTheme(darkTheme = isSystemInDarkTheme(), content = content)
