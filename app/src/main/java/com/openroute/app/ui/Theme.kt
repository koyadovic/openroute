package com.openroute.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF178B2A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7F2B2),
    onPrimaryContainer = Color(0xFF053911),
    secondary = Color(0xFF0B5DAE),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8EAFE),
    onSecondaryContainer = Color(0xFF062B53),
    tertiary = Color(0xFFF6CD42),
    onTertiary = Color(0xFF3B3000),
    tertiaryContainer = Color(0xFFFFF1B8),
    onTertiaryContainer = Color(0xFF4A3900),
    background = Color(0xFFF8FAF3),
    onBackground = Color(0xFF172117),
    surface = Color(0xFFFFFCF0),
    onSurface = Color(0xFF172117),
    surfaceVariant = Color(0xFFE8F2D7),
    onSurfaceVariant = Color(0xFF42513B),
    outline = Color(0xFF78936C),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7ED957),
    onPrimary = Color(0xFF083B12),
    primaryContainer = Color(0xFF126B22),
    onPrimaryContainer = Color(0xFFD7F2B2),
    secondary = Color(0xFF7BC2FF),
    onSecondary = Color(0xFF06345F),
    secondaryContainer = Color(0xFF0B477F),
    onSecondaryContainer = Color(0xFFD8EAFE),
    tertiary = Color(0xFFFFD966),
    onTertiary = Color(0xFF3B3000),
    tertiaryContainer = Color(0xFF6B5600),
    onTertiaryContainer = Color(0xFFFFF1B8),
    background = Color(0xFF0E1F17),
    onBackground = Color(0xFFE7F0DE),
    surface = Color(0xFF14281D),
    onSurface = Color(0xFFE7F0DE),
    surfaceVariant = Color(0xFF263A27),
    onSurfaceVariant = Color(0xFFC8D7BD),
    outline = Color(0xFF91A684),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun OpenRouteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
