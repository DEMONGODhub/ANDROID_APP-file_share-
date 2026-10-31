package com.localshare.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = MaterialTheme.colorScheme
    // glides up and fades in the first time it appears, which also plays again as it scrolls into view
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
    Card(
        modifier.fillMaxWidth().graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 40f }.animateContentSize(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = c.surfaceContainer),
        border = BorderStroke(1.dp, c.outlineVariant),
    ) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
}

/** A QR code drawn on white so it scans in dark mode too. */
@Composable
fun QrCode(content: String, size: Dp, description: String, modifier: Modifier = Modifier) {
    val matrix = remember(content) {
        QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    }
    Canvas(
        modifier.size(size).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(10.dp).semantics { contentDescription = description },
    ) {
        val cell = this.size.width / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

/** Donut like a file manager's storage chart: yellow is what you share, grey is the rest of what is used, the dark track is free. */
@Composable
fun StorageRing(shared: Float, other: Float, size: Dp, modifier: Modifier = Modifier, center: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    val s by animateFloatAsState(shared.coerceIn(0f, 1f), tween(1200, easing = FastOutSlowInEasing), label = "shared")
    val o by animateFloatAsState(other.coerceIn(0f, 1f), tween(1200, easing = FastOutSlowInEasing), label = "other")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = this.size.minDimension * 0.12f
            val tl = Offset(stroke / 2, stroke / 2)
            val sz = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(c.surfaceContainerHighest, 0f, 360f, false, tl, sz, style = Stroke(stroke))
            val shownShared = if (shared > 0f) maxOf(360f * s, 5f) else 0f
            drawArc(c.onSurfaceVariant.copy(alpha = 0.55f), -90f + shownShared, 360f * o, false, tl, sz, style = Stroke(stroke))
            if (shownShared > 0f) drawArc(c.primary, -90f, shownShared, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        center()
    }
}
