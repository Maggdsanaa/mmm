package com.uniatt.student

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

/** خلفية تطبيق الطالب: تدرّج أخضر فاتح مع موجات NFC خافتة. */
@Composable
fun AppBackground(content: @Composable () -> Unit) {
    val top = Color(0xFFD7F2E0)
    val bottom = Color(0xFFF6FBF7)
    val wave = Color(0xFF2E7D32)
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .drawBehind {
                val c = Offset(size.width * 0.92f, size.height * 0.90f)
                for (i in 1..6) {
                    drawCircle(wave.copy(alpha = 0.10f), radius = size.minDimension * 0.18f * i,
                        center = c, style = Stroke(width = 2.5.dp.toPx()))
                }
                val c2 = Offset(size.width * 0.05f, size.height * 0.04f)
                for (i in 1..3) {
                    drawCircle(wave.copy(alpha = 0.07f), radius = size.minDimension * 0.14f * i,
                        center = c2, style = Stroke(width = 2.dp.toPx()))
                }
            }
    ) { content() }
}
