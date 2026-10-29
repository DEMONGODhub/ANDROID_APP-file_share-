package com.localshare.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Black and soft gold. The web page uses the same family (assets/web/index.html).
private val Dark = darkColorScheme(
    primary = Color(0xFFF2C230), onPrimary = Color(0xFF1A1400),
    primaryContainer = Color(0xFF2A2208), onPrimaryContainer = Color(0xFFF2D77A),
    secondary = Color(0xFFD9A400), onSecondary = Color(0xFF000000),
    background = Color(0xFF000000), onBackground = Color(0xFFF7F3DC),
    surface = Color(0xFF0A0A0A), onSurface = Color(0xFFF7F3DC),
    surfaceVariant = Color(0xFF1B1B1B), onSurfaceVariant = Color(0xFFA7A27A),
    surfaceContainerLowest = Color(0xFF000000), surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF111111), surfaceContainerHigh = Color(0xFF181818), surfaceContainerHighest = Color(0xFF212121),
    outline = Color(0xFF34310F), outlineVariant = Color(0xFF26240B),
    error = Color(0xFFF0745F), onError = Color(0xFF2A0A05),
)

private val Type = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.6).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 0.2.sp),
)

@Composable
fun LocalShareTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Dark, // black and yellow, always
        typography = Type,
        shapes = Shapes(
            small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(34.dp),
        ),
        content = content,
    )
}
