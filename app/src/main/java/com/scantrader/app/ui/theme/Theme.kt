package com.scantrader.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val Navy = Color(0xFF07111F)
private val NavySurface = Color(0xFF0E1C33)
private val NavyElevated = Color(0xFF1A3358)
private val Sky = Color(0xFF9DCEFF)
private val VividBlue = Color(0xFF1F6FE0)
private val Blue = Color(0xFF0B57D0)
private val Cyan = Color(0xFF67E8F9)
private val Ink = Color(0xFF0B1B33)
private val Mist = Color(0xFFE7F0FB)

private val DarkColors = darkColorScheme(
    primary = Sky,
    onPrimary = Color(0xFF003258),
    primaryContainer = VividBlue,
    onPrimaryContainer = Color(0xFFF4F8FF),
    secondary = Color(0xFF7FDBFF),
    onSecondary = Color(0xFF003544),
    secondaryContainer = Color(0xFF0E4E66),
    onSecondaryContainer = Color(0xFFC8F4FF),
    tertiary = Cyan,
    onTertiary = Color(0xFF00363D),
    tertiaryContainer = Color(0xFF155E75),
    onTertiaryContainer = Color(0xFFE0FBFF),
    background = Navy,
    onBackground = Mist,
    surface = NavySurface,
    onSurface = Mist,
    surfaceVariant = NavyElevated,
    onSurfaceVariant = Color(0xFFC5D4E8),
    outline = Color(0xFF8AA4C4),
    outlineVariant = Color(0xFF334864),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Mist,
    inverseOnSurface = NavySurface,
    inversePrimary = Blue,
    scrim = Color(0xFF000000),
    surfaceTint = Sky,
)

private val LightColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E6FF),
    onPrimaryContainer = Color(0xFF041633),
    secondary = Color(0xFF0E7490),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFF6FF),
    onSecondaryContainer = Color(0xFF06323C),
    tertiary = Color(0xFF0369A1),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD4F1FF),
    onTertiaryContainer = Color(0xFF04293A),
    background = Color(0xFFF3F7FC),
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE1ECF8),
    onSurfaceVariant = Color(0xFF3D5168),
    outline = Color(0xFF6D8298),
    outlineVariant = Color(0xFFC5D4E6),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF1C2B40),
    inverseOnSurface = Color(0xFFE7F0FB),
    inversePrimary = Sky,
    scrim = Color(0xFF000000),
    surfaceTint = Blue,
)

/** Colors that sit on the camera preview. They stay navy/cyan in both themes. */
object ScanColors {
    val background = Navy
    val scrim = Color(0xE607111F)
    val topScrim = Color(0xCC07111F)
    val panel = Color(0xF0102344)
    val text = Mist
    val accent = Color(0xFF5CC8FF)
    val onAccent = Color(0xFF04182C)
    val chipIdle = Color(0x1FFFFFFF)
}

private val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.15.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun scanTraderAppBarColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.surface,
    scrolledContainerColor = MaterialTheme.colorScheme.surface,
    titleContentColor = MaterialTheme.colorScheme.onSurface,
    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
    actionIconContentColor = MaterialTheme.colorScheme.primary,
)

@Composable
fun ScanTraderTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
