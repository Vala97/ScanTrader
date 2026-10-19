package com.scantrader.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Green = Color(0xFF1B3A2F)
private val Cream = Color(0xFFF4EFE6)
private val Gold = Color(0xFFC4A574)

private val Scheme = darkColorScheme(
    primary = Gold,
    onPrimary = Green,
    secondary = Cream,
    onSecondary = Green,
    background = Color(0xFF12241D),
    onBackground = Cream,
    surface = Color(0xFF1B3A2F),
    onSurface = Cream,
    surfaceVariant = Color(0xFF244A3C),
    onSurfaceVariant = Cream,
)

@Composable
fun ScanTraderTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
