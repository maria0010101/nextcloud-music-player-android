package com.nextcloud.musicplayer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = NextcloudBlueLight,
    onPrimary = Color.Black,
    secondary = AccentCyan,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onBackground = DarkTextPrimary,
    onSurface = DarkTextPrimary,
    onSurfaceVariant = DarkTextSecondary
)

private val LightColorScheme = lightColorScheme(
    primary = NextcloudBlue,
    onPrimary = Color.White,
    secondary = NextcloudBlueDark,
    background = Color(0xFFF8F9FA),
    surface = Color.White,
    surfaceVariant = Color(0xFFECEFF1),
    onBackground = Color(0xFF1E2024),
    onSurface = Color(0xFF1E2024),
    onSurfaceVariant = Color(0xFF5A626A)
)

@Composable
fun NextcloudMusicTheme(
    darkTheme: Boolean = true, // Default to dark theme for modern audio player look
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
