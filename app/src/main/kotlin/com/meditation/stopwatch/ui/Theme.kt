package com.meditation.stopwatch.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Mist = Color(0xFFE8F1F5)
val Glow = Color(0xFF9FE3EE)
val Lilac = Color(0xFFB8A4FF)
val Ink = Color(0xFF06040F)

private val scheme = darkColorScheme(
    primary = Glow,
    onPrimary = Ink,
    secondary = Lilac,
    background = Ink,
    surface = Color(0xFF0E0B1C),
    onSurface = Mist,
    onBackground = Mist,
    surfaceVariant = Color(0xFF1A1630),
    onSurfaceVariant = Color(0xFFC4BEDC),
    outline = Color(0x66C4BEDC),
)

@Composable
fun MeditationTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
