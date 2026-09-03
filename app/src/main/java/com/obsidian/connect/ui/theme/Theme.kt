package com.obsidian.connect.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Ink = Color(0xFF16161A)
private val Cloud = Color(0xFFFAFAFC)
private val Warm = Color(0xFFE8735A)
private val WarmDark = Color(0xFFFF9880)
private val Muted = Color(0xFFB4B4BE)

private val DarkColors = darkColorScheme(
    primary = WarmDark,
    onPrimary = Ink,
    background = Ink,
    onBackground = Cloud,
    surface = Color(0xFF1F1F25),
    onSurface = Cloud,
    onSurfaceVariant = Muted,
)

private val LightColors = lightColorScheme(
    primary = Warm,
    onPrimary = Color.White,
    background = Cloud,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
)

@Composable
fun ConnectTheme(
    darkTheme: Boolean = AppearanceStore.themeMode.isDark(isSystemInDarkTheme()),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        // Material You, where the device offers it.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(colorScheme = colors, content = content)
}

/**
 * Whether this mode means dark, given what the phone itself is set to.
 *
 * Read as a Compose state, so flipping the switch repaints every screen that
 * is currently composed rather than only the next one opened.
 */
@Composable
fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.System -> systemDark
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}
