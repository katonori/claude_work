package com.katonori.gitmobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    secondary = Color(0xFFA8C7FA),
    background = Color(0xFF1B1F24),
    surface = Color(0xFF23272E),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1A73E8),
    secondary = Color(0xFF4285F4),
    background = Color(0xFFFAFAFA),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun GitMobileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
