package com.openroute.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val OpenRouteNavy = Color(0xFF073B67)

private val LightColors = lightColorScheme(
    primary = OpenRouteNavy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8F2FA),
    onPrimaryContainer = Color(0xFF021D36),
    secondary = Color(0xFF5E6875),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0F3F6),
    onSecondaryContainer = Color(0xFF20262D),
    tertiary = Color(0xFF8A929D),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF4F5F7),
    onTertiaryContainer = Color(0xFF2A3036),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF171A1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171A1F),
    surfaceVariant = Color(0xFFF1F3F5),
    onSurfaceVariant = Color(0xFF454B54),
    outline = Color(0xFFC6CCD3),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8CCBFF),
    onPrimary = Color(0xFF062B4C),
    primaryContainer = Color(0xFF103A5D),
    onPrimaryContainer = Color(0xFFE8F2FA),
    secondary = Color(0xFFB8C0CB),
    onSecondary = Color(0xFF20262D),
    secondaryContainer = Color(0xFF30363E),
    onSecondaryContainer = Color(0xFFE5E8EC),
    tertiary = Color(0xFF9EA6B1),
    onTertiary = Color(0xFF22272E),
    tertiaryContainer = Color(0xFF2A3037),
    onTertiaryContainer = Color(0xFFE8EAED),
    background = Color(0xFF101214),
    onBackground = Color(0xFFE7E9EC),
    surface = Color(0xFF181A1D),
    onSurface = Color(0xFFE7E9EC),
    surfaceVariant = Color(0xFF25282D),
    onSurfaceVariant = Color(0xFFC7CCD2),
    outline = Color(0xFF737A83),
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
