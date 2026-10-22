package com.localshare.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Same amber palette as assets/web/index.html — change both together to re-skin the whole product.
private val Dark = darkColorScheme(
    primary = Color(0xFFF7A600), onPrimary = Color(0xFF2A1C00),
    primaryContainer = Color(0xFF4A3300), onPrimaryContainer = Color(0xFFFFDDA0),
    secondary = Color(0xFFFF7A45), onSecondary = Color(0xFF2A0E00),
    background = Color(0xFF17140F), onBackground = Color(0xFFF6EFE2),
    surface = Color(0xFF211C14), onSurface = Color(0xFFF6EFE2),
    surfaceVariant = Color(0xFF2B2419), onSurfaceVariant = Color(0xFFA39982),
    outline = Color(0xFF3A3122), error = Color(0xFFF0745F), onError = Color(0xFF2A0A05),
)
private val Light = lightColorScheme(
    primary = Color(0xFF9C5F00), onPrimary = Color.White,
    primaryContainer = Color(0xFFF7A600), onPrimaryContainer = Color(0xFF2A1C00),
    secondary = Color(0xFFD9541F), onSecondary = Color.White,
    background = Color(0xFFFBF6EA), onBackground = Color(0xFF231C0F),
    surface = Color.White, onSurface = Color(0xFF231C0F),
    surfaceVariant = Color(0xFFF4ECD9), onSurfaceVariant = Color(0xFF7D705A),
    outline = Color(0xFFE6DAC0), error = Color(0xFFC8402F), onError = Color.White,
)

@Composable
fun LocalShareTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}
