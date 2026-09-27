package com.vibecollector.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val VibePurple = Color(0xFF7A5CFF)
val VibeInk = Color(0xFF1B1B2E)
val VibeMint = Color(0xFF2BD9A8)
val VibeAmber = Color(0xFFFFB020)

private val LightColors = lightColorScheme(
    primary = VibePurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E3FF),
    onPrimaryContainer = Color(0xFF241057),
    secondary = VibeMint,
    onSecondary = VibeInk,
    tertiary = VibeAmber,
    background = Color(0xFFFBFAFF),
    onBackground = Color(0xFF16161D),
    surface = Color.White,
    onSurface = Color(0xFF16161D),
    surfaceVariant = Color(0xFFF0EEF7),
    onSurfaceVariant = Color(0xFF4A4757),
    outline = Color(0xFFB9B5C7),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9A7FF),
    onPrimary = Color(0xFF2A0F73),
    primaryContainer = Color(0xFF412C8F),
    onPrimaryContainer = Color(0xFFE8E3FF),
    secondary = Color(0xFF7BE8C4),
    onSecondary = Color(0xFF00382A),
    tertiary = Color(0xFFFFD08A),
    background = Color(0xFF121218),
    onBackground = Color(0xFFE6E1EC),
    surface = Color(0xFF1A1A22),
    onSurface = Color(0xFFE6E1EC),
    surfaceVariant = Color(0xFF2A2A34),
    onSurfaceVariant = Color(0xFFC9C4D4),
    outline = Color(0xFF5C5A6B),
)

@Composable
fun VibeCollectorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        val context = LocalContext.current
        SideEffect {
            val activity = context as? Activity ?: return@SideEffect
            val window = activity.window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
