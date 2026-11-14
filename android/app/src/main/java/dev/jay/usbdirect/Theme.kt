package dev.jay.usbdirect

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFFA896FF),
    onPrimary = Color(0xFF1A0F3D),
    primaryContainer = Color(0xFF2C2350),
    onPrimaryContainer = Color(0xFFE7E0FF),
    secondary = Color(0xFF6FDCC0),
    onSecondary = Color(0xFF00382C),
    background = Color(0xFF0A0A0F),
    onBackground = Color(0xFFE9E7F0),
    surface = Color(0xFF0A0A0F),
    onSurface = Color(0xFFE9E7F0),
    surfaceVariant = Color(0xFF23232D),
    onSurfaceVariant = Color(0xFFA8A6B4),
    surfaceContainer = Color(0xFF14141B),
    surfaceContainerHigh = Color(0xFF1C1C25),
    surfaceContainerHighest = Color(0xFF242430),
    outline = Color(0xFF3A3A46),
    outlineVariant = Color(0xFF2A2A34)
)

private val Light = lightColorScheme(
    primary = Color(0xFF5B45D6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6E0FF),
    onPrimaryContainer = Color(0xFF190066),
    secondary = Color(0xFF00897B),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF7F5FC),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFF7F5FC),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE7E4F0),
    onSurfaceVariant = Color(0xFF5F5C6B),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF1EEF9),
    surfaceContainerHighest = Color(0xFFE9E5F4),
    outline = Color(0xFFC9C5D6),
    outlineVariant = Color(0xFFDDD9E8)
)

@Composable
fun VcTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}

@Composable
fun systemIsDark(): Boolean = isSystemInDarkTheme()
