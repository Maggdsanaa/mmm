package com.uniatt.admin

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

/** خلفية تطبيق المسؤول: تدرّج بنفسجي فاتح مع حلقات خافتة. */
@Composable
fun AppBackground(content: @Composable () -> Unit) {
    val top = Color(0xFFE6DDF5)
    val bottom = Color(0xFFF9F7FD)
    val wave = Color(0xFF5E35B1)
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .drawBehind {
                val c = Offset(size.width * 0.5f, size.height * 1.02f)
                for (i in 1..7) {
                    drawCircle(wave.copy(alpha = 0.09f), radius = size.minDimension * 0.16f * i,
                        center = c, style = Stroke(width = 2.5.dp.toPx()))
                }
                val c2 = Offset(size.width * 0.04f, size.height * 0.05f)
                for (i in 1..3) {
                    drawCircle(wave.copy(alpha = 0.07f), radius = size.minDimension * 0.13f * i,
                        center = c2, style = Stroke(width = 2.dp.toPx()))
                }
            }
    ) { content() }
}
