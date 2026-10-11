package com.codexvoice.app.core.designsystem
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.codexvoice.app.core.model.ThemeMode

private val dark = darkColorScheme(
    primary = Color(0xFFA2E4C8), onPrimary = Color(0xFF103B2D),
    primaryContainer = Color(0xFF214C3D), onPrimaryContainer = Color(0xFFC6F5DF),
    background = Color(0xFF101416), onBackground = Color(0xFFF0F3F1),
    surface = Color(0xFF101416), onSurface = Color(0xFFF0F3F1),
    surfaceContainer = Color(0xFF1B2225), surfaceContainerHigh = Color(0xFF242D30),
    onSurfaceVariant = Color(0xFFAEBDB7), outlineVariant = Color(0xFF33423D),
    error = Color(0xFFFFB4AB))
private val light = lightColorScheme(
    primary = Color(0xFF246B50), onPrimary = Color.White,
    primaryContainer = Color(0xFFC7EEDC), onPrimaryContainer = Color(0xFF173A2C),
    background = Color(0xFFF5F8F6), surface = Color(0xFFF5F8F6),
    surfaceContainer = Color(0xFFE9EFEB), surfaceContainerHigh = Color(0xFFDFE7E2),
    onSurface = Color(0xFF17211D), onSurfaceVariant = Color(0xFF485C51),
    outlineVariant = Color(0xFFC7D5CD), error = Color(0xFF9C302D))
private val type = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.7).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 27.sp, lineHeight = 34.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.7.sp))
@Composable
fun CodexVoiceTheme(mode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    val useDark = mode == ThemeMode.DARK || (mode == ThemeMode.SYSTEM && isSystemInDarkTheme())
    MaterialTheme(colorScheme = if (useDark) dark else light, typography = type, content = content)
}

