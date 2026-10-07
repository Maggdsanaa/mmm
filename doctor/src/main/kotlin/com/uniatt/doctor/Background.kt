package com.uniatt.doctor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** خلفية تطبيق الدكتور: تدرّج أزرق فاتح مع موجات قارئ NFC خافتة. */
@Composable
fun AppBackground(content: @Composable () -> Unit) {
    val top = Color(0xFFD6E8FB)
    val bottom = Color(0xFFF5F9FE)
    val wave = Color(0xFF1565C0)
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .drawBehind {
                val c = Offset(size.width * 0.08f, size.height * 0.92f)
                for (i in 1..6) {
                    drawCircle(wave.copy(alpha = 0.10f), radius = size.minDimension * 0.18f * i,
                        center = c, style = Stroke(width = 2.5.dp.toPx()))
                }
                val c2 = Offset(size.width * 0.95f, size.height * 0.05f)
                for (i in 1..3) {
                    drawCircle(wave.copy(alpha = 0.07f), radius = size.minDimension * 0.14f * i,
                        center = c2, style = Stroke(width = 2.dp.toPx()))
                }
            }
    ) { content() }
}
