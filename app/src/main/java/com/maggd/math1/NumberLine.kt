package com.maggd.math1

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToLong

fun formatNum(d: Double): String {
    if (abs(d - d.roundToLong()) < 1e-9) return d.roundToLong().toString()
    for (q in 2..12) {
        val p = (d * q).roundToLong()
        if (abs(d * q - p) < 1e-9) return "$p/$q"
    }
    return String.format("%.2f", d)
}

/** خط الأعداد: دائرة مفتوحة للتباين الصارم ومغلقة لغير الصارم، وسهم للامتداد إلى ∞. */
@Composable
fun NumberLine(intervals: List<Iv>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.onSurface
    val solColor = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.surface
    val tm = rememberTextMeasurer()
    val style = TextStyle(fontSize = 12.sp, color = lineColor)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Canvas(modifier.fillMaxWidth().height(84.dp)) {
            val w = size.width
            val m = 28.dp.toPx()
            val pts = intervals.flatMap { listOf(it.lo, it.hi) }.filter { it.isFinite() }.distinct().sorted()
            val lo: Double
            val hi: Double
            if (pts.isEmpty()) { lo = -5.0; hi = 5.0 } else {
                val span = pts.last() - pts.first()
                val pad = if (span == 0.0) 2.0 else span * 0.35
                lo = pts.first() - pad; hi = pts.last() + pad
            }
            fun px(v: Double) = (m + ((v - lo) / (hi - lo)) * (w - 2 * m)).toFloat()
            val y = size.height * 0.6f
            val edgeL = 6.dp.toPx(); val edgeR = w - 6.dp.toPx()
            val sw = 2.dp.toPx()
            val ah = 6.dp.toPx()

            // الخط الأساسي بسهمين
            drawLine(lineColor, Offset(edgeL, y), Offset(edgeR, y), sw)
            drawLine(lineColor, Offset(edgeL, y), Offset(edgeL + ah, y - ah * 0.7f), sw)
            drawLine(lineColor, Offset(edgeL, y), Offset(edgeL + ah, y + ah * 0.7f), sw)
            drawLine(lineColor, Offset(edgeR, y), Offset(edgeR - ah, y - ah * 0.7f), sw)
            drawLine(lineColor, Offset(edgeR, y), Offset(edgeR - ah, y + ah * 0.7f), sw)

            // علامات وأرقام
            val ticks = pts + if (0.0 in lo..hi && 0.0 !in pts) listOf(0.0) else emptyList()
            for (v in ticks) {
                val x = px(v)
                drawLine(lineColor, Offset(x, y - 5.dp.toPx()), Offset(x, y + 5.dp.toPx()), 1.5.dp.toPx())
                val t = tm.measure(AnnotatedString(formatNum(v)), style)
                drawText(t, topLeft = Offset(x - t.size.width / 2f, y + 8.dp.toPx()))
            }

            // المناطق المظللة
            val segY = y - 14.dp.toPx()
            val seg = 4.dp.toPx()
            for (iv in intervals) {
                val x1 = if (iv.lo.isInfinite()) edgeL else px(iv.lo)
                val x2 = if (iv.hi.isInfinite()) edgeR else px(iv.hi)
                drawLine(solColor, Offset(x1, segY), Offset(x2, segY), seg)
                if (iv.lo.isInfinite()) {
                    drawLine(solColor, Offset(edgeL, segY), Offset(edgeL + ah, segY - ah * 0.7f), seg)
                    drawLine(solColor, Offset(edgeL, segY), Offset(edgeL + ah, segY + ah * 0.7f), seg)
                }
                if (iv.hi.isInfinite()) {
                    drawLine(solColor, Offset(edgeR, segY), Offset(edgeR - ah, segY - ah * 0.7f), seg)
                    drawLine(solColor, Offset(edgeR, segY), Offset(edgeR - ah, segY + ah * 0.7f), seg)
                }
                for ((v, closed) in listOf(iv.lo to iv.loC, iv.hi to iv.hiC)) {
                    if (v.isInfinite()) continue
                    val x = px(v)
                    drawLine(solColor, Offset(x, segY), Offset(x, y), 1.5.dp.toPx())
                    val r = 6.dp.toPx()
                    if (closed) drawCircle(solColor, r, Offset(x, y))
                    else {
                        drawCircle(bg, r, Offset(x, y))
                        drawCircle(solColor, r, Offset(x, y), style = Stroke(2.dp.toPx()))
                    }
                }
            }
        }
    }
}
