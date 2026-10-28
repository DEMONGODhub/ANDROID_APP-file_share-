package com.localshare.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Same black and yellow palette as assets/web/index.html — change both together to re-skin the whole product.
private val Dark = darkColorScheme(
    primary = Color(0xFFFFE33B), onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF3A3400), onPrimaryContainer = Color(0xFFFFF3A0),
    secondary = Color(0xFFFFC400), onSecondary = Color(0xFF000000),
    background = Color(0xFF000000), onBackground = Color(0xFFF7F3DC),
    surface = Color(0xFF0E0E0E), onSurface = Color(0xFFF7F3DC),
    surfaceVariant = Color(0xFF1B1B1B), onSurfaceVariant = Color(0xFFA7A27A),
    outline = Color(0xFF34310F), error = Color(0xFFF0745F), onError = Color(0xFF2A0A05),
)

@Composable
fun LocalShareTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Dark, // black and yellow, always
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}
