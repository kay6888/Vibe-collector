package com.vibecollector.ui.theme

import android.app.Activity
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

val NeonCyan = Color(0xFF00F0FF)
val NeonMagenta = Color(0xFFFF2BD6)
val NeonGreen = Color(0xFF39FF14)
val StatusBlue = Color(0xFF2E9BFF)
val StatusYellow = Color(0xFFFFE600)
val StatusRed = Color(0xFFFF3B5C)

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
    primary = NeonCyan,
    onPrimary = Color(0xFF00141A),
    primaryContainer = Color(0xFF06303A),
    onPrimaryContainer = Color(0xFFB8FBFF),
    secondary = NeonMagenta,
    onSecondary = Color(0xFF2A0020),
    secondaryContainer = Color(0xFF3A0A33),
    onSecondaryContainer = Color(0xFFFFD0F5),
    tertiary = NeonGreen,
    onTertiary = Color(0xFF062000),
    background = Color(0xFF05050D),
    onBackground = Color(0xFFE8F4FF),
    surface = Color(0xFF0B0B18),
    onSurface = Color(0xFFE8F4FF),
    surfaceVariant = Color(0xFF151528),
    onSurfaceVariant = Color(0xFFA9B4D0),
    surfaceContainer = Color(0xFF0F0F20),
    surfaceContainerHigh = Color(0xFF151530),
    outline = Color(0xFF3B3B66),
    error = StatusRed,
)

@Composable
fun VibeCollectorTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors // the neon look is dark-first
    val view = LocalView.current
    if (!view.isInEditMode) {
        val context = LocalContext.current
        SideEffect {
            val activity = context as? Activity ?: return@SideEffect
            val window = activity.window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
