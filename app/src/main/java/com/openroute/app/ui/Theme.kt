package com.openroute.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B6E4F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8F3E7),
    secondary = Color(0xFF1D3557),
    surface = Color(0xFFF7F6F2),
    background = Color(0xFFF1EFE8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6AD7A8),
    secondary = Color(0xFF9AB7D3),
)

@Composable
fun OpenRouteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

