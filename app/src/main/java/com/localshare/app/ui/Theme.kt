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

// Same palette as assets/web/index.html — change both together to re-skin the whole product.
private val Dark = darkColorScheme(
    primary = Color(0xFF7C9CFF), onPrimary = Color(0xFF0B1230),
    secondary = Color(0xFF46E0B8), onSecondary = Color(0xFF00201A),
    background = Color(0xFF0B0F14), onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF131A22), onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1B2430), onSurfaceVariant = Color(0xFF8B98A9),
    outline = Color(0xFF263241), error = Color(0xFFFF6B7A), onError = Color(0xFF2A0006),
)
private val Light = lightColorScheme(
    primary = Color(0xFF4A6CF7), onPrimary = Color.White,
    secondary = Color(0xFF12A98A), onSecondary = Color.White,
    background = Color(0xFFF6F8FB), onBackground = Color(0xFF101820),
    surface = Color.White, onSurface = Color(0xFF101820),
    surfaceVariant = Color(0xFFEEF2F7), onSurfaceVariant = Color(0xFF5B6B7E),
    outline = Color(0xFFD8E0EA), error = Color(0xFFD93A4B), onError = Color.White,
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
