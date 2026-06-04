package com.duddylabs.translator.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF24524A),
    secondary = Color(0xFF375A7F),
    tertiary = Color(0xFFB77D2C),
    background = Color(0xFFF4F7F6),
    surface = Color(0xFFFFFFFF),
    error = Color(0xFFB94037),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9BD4C3),
    secondary = Color(0xFFAFC9E7),
    tertiary = Color(0xFFE6B875),
    background = Color(0xFF101816),
    surface = Color(0xFF17211E),
    error = Color(0xFFFFB4AB),
)

@Composable
fun DuddyTranslatorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
