package com.kayan.x.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RoyalGold = Color(0xFFD4AF37)
private val RoyalGoldBright = Color(0xFFFFD86B)
private val Obsidian = Color(0xFF080808)
private val Carbon = Color(0xFF121212)
private val Graphite = Color(0xFF1D1D1D)
private val Ivory = Color(0xFFF5F0E6)

private val DarkColors = darkColorScheme(
    primary = RoyalGoldBright,
    onPrimary = Color(0xFF171105),
    primaryContainer = Color(0xFF5F4A12),
    onPrimaryContainer = Color(0xFFFFE9A6),
    secondary = RoyalGold,
    onSecondary = Color.Black,
    background = Obsidian,
    surface = Carbon,
    surfaceVariant = Graphite,
    onBackground = Ivory,
    onSurface = Ivory,
    onSurfaceVariant = Color(0xFFC8C0B0),
    error = Color(0xFFFF6B5F)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF8A6A12),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE9A6),
    onPrimaryContainer = Color(0xFF2B2105),
    secondary = Color(0xFF6B5310),
    background = Color(0xFFF7F3EA),
    surface = Color.White,
    surfaceVariant = Color(0xFFEDE7D8),
    onBackground = Color(0xFF17130A),
    onSurface = Color(0xFF17130A),
    onSurfaceVariant = Color(0xFF514A3C),
    error = Color(0xFFB3261E)
)

@Composable
fun KayanTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, typography = KayanTypography, content = content)
}
