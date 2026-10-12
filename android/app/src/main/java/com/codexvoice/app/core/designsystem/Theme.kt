package com.codexvoice.app.core.designsystem
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.codexvoice.app.core.model.ThemeMode

private val dark = darkColorScheme(
    secondary = Color(0xFFD4D4D8), onSecondary = Color(0xFF171717),
    secondaryContainer = Color(0xFF303030), onSecondaryContainer = Color(0xFFF5F5F5),
    primary = Color(0xFF0A84FF), onPrimary = Color.White,
    primaryContainer = Color(0xFF303030), onPrimaryContainer = Color(0xFFF2F2F2),
    background = Color(0xFF000000), onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF1C1C1E), onSurface = Color(0xFFF5F5F5),
    surfaceContainer = Color(0xFF1C1C1E), surfaceContainerHigh = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFFAAAAAE), outlineVariant = Color(0xFF38383A),
    error = Color(0xFFFFB4AB))
private val light = lightColorScheme(
    secondary = Color(0xFF636366), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5E5E8), onSecondaryContainer = Color(0xFF171717),
    primary = Color(0xFF007AFF), onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E6E8), onPrimaryContainer = Color(0xFF171717),
    background = Color(0xFFF2F2F7), onBackground = Color(0xFF171717), surface = Color.White,
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFE5E5EA),
    onSurface = Color(0xFF171717), onSurfaceVariant = Color(0xFF636366),
    outlineVariant = Color(0xFFD4D4D8), error = Color(0xFF9C302D))
private val type = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.7).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 27.sp, lineHeight = 34.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.7.sp))
@Composable
fun CodexVoiceTheme(mode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    val useDark = mode == ThemeMode.DARK || (mode == ThemeMode.SYSTEM && isSystemInDarkTheme())
    MaterialTheme(colorScheme = if (useDark) dark else light, typography = type,
        shapes = Shapes(extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(14.dp), extraLarge = RoundedCornerShape(18.dp)), content = content)
}

