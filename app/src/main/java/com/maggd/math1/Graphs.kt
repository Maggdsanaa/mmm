package com.maggd.math1

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private val INF = Double.POSITIVE_INFINITY
private fun fm(d: Double) = formatNum(d).replace("-", "−")

/** حل a x² + b x + c (op) 0 ؛ op: 0 ">" ، 1 "≥" ، 2 "<" ، 3 "≤". */
fun quadIneq(a: Double, b: Double, c: Double, op: Int): List<Iv> {
    val strict = op == 0 || op == 2
    val wantPos = op < 2
    val d = b * b - 4 * a * c
    val all = listOf(Iv(-INF, false, INF, false))
    if (d < 0) return if (wantPos == (a > 0)) all else emptyList()
    val sq = sqrt(d)
    val r1 = min((-b - sq) / (2 * a), (-b + sq) / (2 * a))
    val r2 = max((-b - sq) / (2 * a), (-b + sq) / (2 * a))
    val outSat = wantPos == (a > 0)
    if (d == 0.0) {
        return if (outSat) {
            if (strict) listOf(Iv(-INF, false, r1, false), Iv(r1, false, INF, false)) else all
        } else {
            if (strict) emptyList() else listOf(Iv(r1, true, r1, true))
        }
    }
    return if (outSat) listOf(Iv(-INF, false, r1, !strict), Iv(r2, !strict, INF, false))
    else listOf(Iv(r1, !strict, r2, !strict))
}

class Vp(val x0: Double, val x1: Double, val y0: Double, val y1: Double)

@Composable
fun ExplorerCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.tertiary),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
            content()
        }
    }
}

@Composable
fun Param(name: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$name = ${fm(value.toDouble())}", Modifier.width(84.dp), fontSize = 14.sp)
            Slider(value, onChange, Modifier.weight(1f), valueRange = range, steps = steps)
        }
    }
}

@Composable
fun ChoiceRow(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, t ->
            if (i == selected) Button(onClick = { onSelect(i) }, modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(0.dp)) { Text(t) }
            else OutlinedButton(onClick = { onSelect(i) }, modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(0.dp)) { Text(t) }
        }
    }
}

@Composable
fun Plot(vp: Vp, height: Dp = 240.dp, content: DrawScope.((Double, Double) -> Offset) -> Unit) {
    val axis = MaterialTheme.colorScheme.onSurface
    val grid = axis.copy(alpha = 0.12f)
    val tm = rememberTextMeasurer()
    val st = TextStyle(fontSize = 10.sp, color = axis)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val w = size.width; val h = size.height
            val pt = { x: Double, y: Double ->
                Offset(((x - vp.x0) / (vp.x1 - vp.x0) * w).toFloat(), (h - (y - vp.y0) / (vp.y1 - vp.y0) * h).toFloat())
            }
            val xa = ceil(vp.x0).toInt()..floor(vp.x1).toInt()
            val ya = ceil(vp.y0).toInt()..floor(vp.y1).toInt()
            for (xi in xa) drawLine(grid, pt(xi.toDouble(), vp.y0), pt(xi.toDouble(), vp.y1), 1f)
            for (yi in ya) drawLine(grid, pt(vp.x0, yi.toDouble()), pt(vp.x1, yi.toDouble()), 1f)
            val o = pt(0.0, 0.0)
            val hasX = vp.y0 <= 0 && 0 <= vp.y1
            val hasY = vp.x0 <= 0 && 0 <= vp.x1
            if (hasX) drawLine(axis, Offset(0f, o.y), Offset(w, o.y), 2f)
            if (hasY) drawLine(axis, Offset(o.x, 0f), Offset(o.x, h), 2f)
            for (xi in xa) if (xi != 0 && xi % 2 == 0) {
                val p = pt(xi.toDouble(), 0.0)
                val t = tm.measure(AnnotatedString("$xi"), st)
                val y = if (hasX) p.y + 3f else h - t.size.height - 2f
                drawText(t, topLeft = Offset(p.x - t.size.width / 2f, min(y, h - t.size.height.toFloat())))
            }
            for (yi in ya) if (yi != 0 && yi % 2 == 0) {
                val p = pt(0.0, yi.toDouble())
                val t = tm.measure(AnnotatedString("$yi"), st)
                val x = if (hasY) p.x + 4f else 2f
                drawText(t, topLeft = Offset(x, p.y - t.size.height / 2f))
            }
            clipRect(0f, 0f, w, h) { content(this, pt) }
        }
    }
}

private fun DrawScope.curve(pt: (Double, Double) -> Offset, x0: Double, x1: Double, color: Color, f: (Double) -> Double) {
    val path = Path()
    var x = x0; var started = false
    while (x <= x1 + 1e-9) {
        val y = f(x)
        if (y.isFinite()) {
            val p = pt(x, y)
            if (!started) { path.moveTo(p.x, p.y); started = true } else path.lineTo(p.x, p.y)
        }
        x += (x1 - x0) / 240.0
    }
    drawPath(path, color, style = Stroke(5f))
}

private fun DrawScope.shadeAxis(pt: (Double, Double) -> Offset, ivs: List<Iv>, color: Color, x0: Double, x1: Double) {
    for (iv in ivs) {
        val a = max(iv.lo, x0); val b = min(iv.hi, x1)
        if (a <= b) drawLine(color, pt(a, 0.0), pt(b, 0.0), 12f)
        for ((v, closed) in listOf(iv.lo to iv.loC, iv.hi to iv.hiC)) {
            if (v.isFinite() && v in x0..x1) {
                if (closed) drawCircle(color, 10f, pt(v, 0.0))
                else drawCircle(color, 10f, pt(v, 0.0), style = Stroke(4f))
            }
        }
    }
}

private fun polyD(a: Double, b: Double, c: Double): String {
    fun co(v: Double) = if (abs(v) == 1.0) "" else formatNum(abs(v)).replace("-", "−")
    val sb = StringBuilder()
    sb.append(if (a < 0) "−" else "").append(co(a)).append("x²")
    if (b != 0.0) sb.append(if (b < 0) " − " else " + ").append(co(b)).append("x")
    if (c != 0.0) sb.append(if (c < 0) " − " else " + ").append(fm(abs(c)))
    return sb.toString()
}

// ---------------------------------------------------------------- خطية
@Composable
fun LinearExplorer() {
    var a by remember { mutableFloatStateOf(2f) }
    var b by remember { mutableFloatStateOf(-4f) }
    val cp = MaterialTheme.colorScheme.primary
    val ct = MaterialTheme.colorScheme.tertiary
    ExplorerCard("مستكشف المعادلة الخطية  ax + b = 0") {
        Param("a", a, -5f..5f, 9) { a = it }
        Param("b", b, -8f..8f, 15) { b = it }
        val da = a.toDouble(); val db = b.toDouble()
        Plot(Vp(-8.0, 8.0, -8.0, 8.0), 220.dp) { pt ->
            curve(pt, -8.0, 8.0, cp) { da * it + db }
            if (da != 0.0) drawCircle(ct, 12f, pt(-db / da, 0.0))
        }
        if (da == 0.0) Text(if (db == 0.0) "a = b = 0: كل الأعداد حلول" else "a = 0 و b ≠ 0: لا يوجد حل")
        else {
            MathText("المعادلة: ${if (da == 1.0) "" else if (da == -1.0) "−" else fm(da)}x ${if (db < 0) "−" else "+"} ${fm(abs(db))} = 0")
            MathText("x = −b/a = (${fm(-db)})/(${fm(da)}) = ${fm(-db / da)}", bold = true)
            Text("الحل هو نقطة تقاطع المستقيم y = ax + b مع محور x.", fontSize = 12.sp)
        }
    }
}

// ---------------------------------------------------------------- نظام معادلتين
@Composable
fun LinesExplorer() {
    var a1 by remember { mutableFloatStateOf(1f) }; var b1 by remember { mutableFloatStateOf(1f) }; var c1 by remember { mutableFloatStateOf(3f) }
    var a2 by remember { mutableFloatStateOf(1f) }; var b2 by remember { mutableFloatStateOf(-1f) }; var c2 by remember { mutableFloatStateOf(1f) }
    val cp = MaterialTheme.colorScheme.primary
    val ce = MaterialTheme.colorScheme.error
    val ct = MaterialTheme.colorScheme.tertiary
    ExplorerCard("مستكشف نظام معادلتين (تقاطع مستقيمين)") {
        Param("a₁", a1, -6f..6f, 11) { a1 = it }; Param("b₁", b1, -6f..6f, 11) { b1 = it }; Param("c₁", c1, -10f..10f, 19) { c1 = it }
        Param("a₂", a2, -6f..6f, 11) { a2 = it }; Param("b₂", b2, -6f..6f, 11) { b2 = it }; Param("c₂", c2, -10f..10f, 19) { c2 = it }
        val A1 = a1.toInt(); val B1 = b1.toInt(); val C1 = c1.toInt(); val A2 = a2.toInt(); val B2 = b2.toInt(); val C2 = c2.toInt()
        val d = A1 * B2 - A2 * B1
        val dx = C1 * B2 - C2 * B1
        val dy = A1 * C2 - A2 * C1
        fun DrawScope.line(pt: (Double, Double) -> Offset, a: Int, b: Int, c: Int, col: Color) {
            if (b != 0) drawLine(col, pt(-10.0, (c + 10.0 * a) / b), pt(10.0, (c - 10.0 * a) / b), 5f)
            else if (a != 0) drawLine(col, pt(c.toDouble() / a, -10.0), pt(c.toDouble() / a, 10.0), 5f)
        }
        Plot(Vp(-10.0, 10.0, -10.0, 10.0), 260.dp) { pt ->
            line(pt, A1, B1, C1, cp); line(pt, A2, B2, C2, ce)
            if (d != 0) drawCircle(ct, 14f, pt(dx.toDouble() / d, dy.toDouble() / d))
        }
        Text("المعادلة (1) باللون الأزرق ، المعادلة (2) بالأحمر", fontSize = 12.sp)
        MathText("${Steps.eq(A1, "x", B1, "y", C1)}   (1)\n${Steps.eq(A2, "x", B2, "y", C2)}   (2)")
        if (d != 0) {
            MathText("Δ = ${d} ، Δ₁ = $dx ، Δ₂ = $dy")
            MathText("x = (${dx})/(${d}) = ${fm(dx.toDouble() / d)}   ،   y = (${dy})/(${d}) = ${fm(dy.toDouble() / d)}", bold = true)
            Text("نقطة التقاطع هي حل النظام.", fontSize = 12.sp)
        } else if (dx == 0 && dy == 0) Text("Δ = 0: المستقيمان منطبقان (عدد لا نهائي من الحلول).")
        else Text("Δ = 0: المستقيمان متوازيان (لا يوجد حل).")
    }
}

// ---------------------------------------------------------------- تربيعية
@Composable
fun QuadExplorer() {
    var a by remember { mutableFloatStateOf(1f) }
    var b by remember { mutableFloatStateOf(-3f) }
    var c by remember { mutableFloatStateOf(2f) }
    var op by remember { mutableIntStateOf(2) }
    val cp = MaterialTheme.colorScheme.primary
    val ce = MaterialTheme.colorScheme.error
    val ct = MaterialTheme.colorScheme.tertiary
    ExplorerCard("مستكشف المعادلة والمتباينة التربيعية") {
        Param("a", a, -3f..3f, 11) { a = it }
        Param("b", b, -8f..8f, 15) { b = it }
        Param("c", c, -8f..8f, 15) { c = it }
        ChoiceRow(listOf("> 0", "≥ 0", "< 0", "≤ 0"), op) { op = it }
        val da = a.toDouble(); val db = b.toDouble(); val dc = c.toDouble()
        if (da == 0.0) Text("a = 0: المعادلة ليست تربيعية. غيّر قيمة a.")
        else {
            val d = db * db - 4 * da * dc
            val sol = quadIneq(da, db, dc, op)
            Plot(Vp(-6.0, 6.0, -10.0, 10.0), 260.dp) { pt ->
                shadeAxis(pt, sol, ct, -6.0, 6.0)
                curve(pt, -6.0, 6.0, cp) { da * it * it + db * it + dc }
                if (d >= 0) {
                    val sq = sqrt(d)
                    drawCircle(ce, 9f, pt((-db + sq) / (2 * da), 0.0))
                    drawCircle(ce, 9f, pt((-db - sq) / (2 * da), 0.0))
                }
            }
            MathText("${polyD(da, db, dc)} ${listOf(">", "≥", "<", "≤")[op]} 0")
            MathText("Δ = b² − 4ac = ${fm(d)}")
            if (d > 0) {
                val sq = sqrt(d)
                MathText("x = ${fm(min((-db - sq) / (2 * da), (-db + sq) / (2 * da)))} ، x = ${fm(max((-db - sq) / (2 * da), (-db + sq) / (2 * da)))}")
            } else if (d == 0.0) MathText("جذر مكرر: x = ${fm(-db / (2 * da))}")
            else Text("Δ < 0: لا توجد جذور حقيقية (المنحنى لا يقطع محور x)")
            MathText("∴ مجموعة الحل: ${Choices.fmtIvs(sol)}", bold = true)
            NumberLine(sol)
            if (sol.isEmpty()) Text("∅ لا توجد نقاط مظللة")
            Text("الأحمر: الجذران ، الأخضر على محور x: مجموعة الحل.", fontSize = 12.sp)
        }
    }
}

// ---------------------------------------------------------------- قيمة مطلقة
@Composable
fun AbsExplorer() {
    var p by remember { mutableFloatStateOf(1f) }
    var q by remember { mutableFloatStateOf(1f) }
    var k by remember { mutableFloatStateOf(3f) }
    var op by remember { mutableIntStateOf(0) }
    val cp = MaterialTheme.colorScheme.primary
    val ce = MaterialTheme.colorScheme.error
    val ct = MaterialTheme.colorScheme.tertiary
    ExplorerCard("مستكشف القيمة المطلقة  |px + q| ؟ k") {
        Param("p", p, 1f..3f, 1) { p = it }
        Param("q", q, -6f..6f, 11) { q = it }
        Param("k", k, -2f..8f, 9) { k = it }
        ChoiceRow(listOf("=", "≤", "≥"), op) { op = it }
        val dp = p.toDouble(); val dq = q.toDouble(); val dk = k.toDouble()
        val sol: List<Iv> = when {
            dk < 0 -> if (op == 2) listOf(Iv(-INF, false, INF, false)) else emptyList()
            else -> {
                val lo = (-dk - dq) / dp; val hi = (dk - dq) / dp
                when (op) {
                    0 -> if (dk == 0.0) listOf(Iv(lo, true, lo, true)) else listOf(Iv(lo, true, lo, true), Iv(hi, true, hi, true))
                    1 -> listOf(Iv(lo, true, hi, true))
                    else -> if (dk == 0.0) listOf(Iv(-INF, false, INF, false)) else listOf(Iv(-INF, false, lo, true), Iv(hi, true, INF, false))
                }
            }
        }
        Plot(Vp(-8.0, 8.0, -3.0, 10.0), 240.dp) { pt ->
            curve(pt, -8.0, 8.0, cp) { abs(dp * it + dq) }
            drawLine(ce, pt(-8.0, dk), pt(8.0, dk), 4f)
            shadeAxis(pt, sol, ct, -8.0, 8.0)
        }
        MathText("|${if (dp == 1.0) "" else fm(dp)}x ${if (dq < 0) "−" else "+"} ${fm(abs(dq))}| ${listOf("=", "≤", "≥")[op]} ${fm(dk)}")
        if (dk < 0 && op != 2) Text("الطرف الأيمن سالب بينما |…| ≥ 0 دائمًا ⟹ لا يوجد حل")
        MathText("∴ مجموعة الحل: ${Choices.fmtIvs(sol)}", bold = true)
        NumberLine(sol)
        Text("الأزرق: y = |px + q| ، الأحمر: y = k ، نقاط التقاطع هي حلول المعادلة.", fontSize = 12.sp)
    }
}

// ---------------------------------------------------------------- إشارة المقدار
@Composable
fun SignExplorer() {
    var r1 by remember { mutableFloatStateOf(-2f) }
    var r2 by remember { mutableFloatStateOf(2f) }
    val pos = MaterialTheme.colorScheme.primary
    val neg = MaterialTheme.colorScheme.error
    val axis = MaterialTheme.colorScheme.onSurface
    val tm = rememberTextMeasurer()
    fun fx(r: Double) = if (r == 0.0) "x" else if (r > 0) "(x − ${fm(r)})" else "(x + ${fm(-r)})"
    ExplorerCard("مختبر إشارة المقدار (x − r₁)(x − r₂)") {
        Param("r₁", r1, -6f..6f, 11) { r1 = it }
        Param("r₂", r2, -6f..6f, 11) { r2 = it }
        val lo = min(r1, r2).toDouble(); val hi = max(r1, r2).toDouble()
        val dashed = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Canvas(Modifier.fillMaxWidth().height(190.dp)) {
                val w = size.width
                fun px(v: Double) = ((v + 8.0) / 16.0 * w).toFloat()
                fun row(y: Float, label: String, roots: List<Double>, signs: List<Boolean>) {
                    val bps = listOf(-8.0) + roots + listOf(8.0)
                    for (i in 0 until bps.size - 1) {
                        val s = signs[i]
                        drawLine(if (s) pos else neg, Offset(px(bps[i]), y), Offset(px(bps[i + 1]), y), 6f,
                            pathEffect = if (s) null else dashed)
                        val t = tm.measure(AnnotatedString(if (s) "+" else "−"), TextStyle(fontSize = 18.sp, color = if (s) pos else neg, fontWeight = FontWeight.Bold))
                        drawText(t, topLeft = Offset((px(bps[i]) + px(bps[i + 1])) / 2f - t.size.width / 2f, y - 30f))
                    }
                    for (r in roots) drawCircle(axis, 9f, Offset(px(r), y))
                    val tl = tm.measure(AnnotatedString(label), TextStyle(fontSize = 12.sp, color = axis))
                    drawText(tl, topLeft = Offset(4f, y + 10f))
                }
                row(40f, fx(r1.toDouble()), listOf(r1.toDouble()), listOf(false, true))
                row(105f, fx(r2.toDouble()), listOf(r2.toDouble()), listOf(false, true))
                if (lo < hi) row(170f, "${fx(lo)}${fx(hi)}", listOf(lo, hi), listOf(true, false, true))
                else row(170f, "${fx(lo)}²", listOf(lo), listOf(true, true))
            }
        }
        val posI = if (lo < hi) listOf(Iv(-INF, false, lo, false), Iv(hi, false, INF, false))
                   else listOf(Iv(-INF, false, lo, false), Iv(lo, false, INF, false))
        val negI = if (lo < hi) listOf(Iv(lo, false, hi, false)) else emptyList()
        MathText("المقدار موجب في: ${Choices.fmtIvs(posI)}", bold = true)
        MathText(if (negI.isEmpty()) "لا توجد فترة سالبة (الجذر مكرر)" else "المقدار سالب في: ${Choices.fmtIvs(negI)}", bold = true)
        MathText("المقدار يساوي صفرًا عند: x = ${fm(lo)}${if (lo < hi) " ، x = ${fm(hi)}" else ""}")
        Text("خط متصل (+) موجب ، خط متقطع (−) سالب ، ضرب الإشارات يعطي إشارة الحاصل.", fontSize = 12.sp)
    }
}

// ---------------------------------------------------------------- جذرية
@Composable
fun RadExplorer() {
    var k by remember { mutableFloatStateOf(6f) }
    val cp = MaterialTheme.colorScheme.primary
    val ce = MaterialTheme.colorScheme.error
    val ct = MaterialTheme.colorScheme.tertiary
    ExplorerCard("مستكشف المعادلة الجذرية  x + √x = k") {
        Param("k", k, 0f..12f, 23) { k = it }
        val dk = k.toDouble()
        val t1 = (-1 + sqrt(1 + 4 * dk)) / 2
        val t2 = (-1 - sqrt(1 + 4 * dk)) / 2
        val x1 = t1 * t1
        Plot(Vp(-1.0, 13.0, -2.0, 14.0), 240.dp) { pt ->
            curve(pt, 0.0, 13.0, cp) { it + sqrt(it) }
            drawLine(ce, pt(-1.0, dk), pt(13.0, dk), 4f)
            drawCircle(ct, 12f, pt(x1, dk))
        }
        MathText("√x = t ⟹ t² + t − ${fm(dk)} = 0")
        MathText("t = ${fm(t1)} (مقبول لأن √x ≥ 0) ⟹ x = ${fm(x1)}", bold = true)
        MathText("t = ${fm(t2)} < 0 (مرفوض لأن √x لا يكون سالبًا)")
        Text("للتحقق: عند k = 6 يكون الحل x = 4 فقط، أما x = 9 الناتج عن التربيع فلا يقطع المنحنى (دخيل).", fontSize = 12.sp)
    }
}

// ---------------------------------------------------------------- مختبر المتباينات
@Composable
fun IneqLab() {
    var mode by remember { mutableIntStateOf(0) }
    var op by remember { mutableIntStateOf(3) }
    var bnd by remember { mutableFloatStateOf(2f) }
    var lo by remember { mutableFloatStateOf(-2f) }
    var hi by remember { mutableFloatStateOf(3f) }
    var cl by remember { mutableStateOf(true) }
    var cr by remember { mutableStateOf(false) }
    val ops = listOf("<", "≤", ">", "≥")
    ExplorerCard("مختبر المتباينات على خط الأعداد") {
        ChoiceRow(listOf("x ؟ b", "a ؟ x ؟ b"), mode) { mode = it }
        val iv: Iv?
        val text: String
        if (mode == 0) {
            ChoiceRow(ops, op) { op = it }
            Param("b", bnd, -8f..8f, 31) { bnd = it }
            val b = bnd.toDouble()
            iv = when (op) {
                0 -> Iv(-INF, false, b, false)
                1 -> Iv(-INF, false, b, true)
                2 -> Iv(b, false, INF, false)
                else -> Iv(b, true, INF, false)
            }
            text = "x ${ops[op]} ${fm(b)}"
        } else {
            Param("a", lo, -8f..8f, 31) { lo = it }
            Param("b", hi, -8f..8f, 31) { hi = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(cl, { cl = it }); Text(" الطرف الأيسر مغلق")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(cr, { cr = it }); Text(" الطرف الأيمن مغلق")
            }
            val a = lo.toDouble(); val b = hi.toDouble()
            iv = if (a < b || (a == b && cl && cr)) Iv(a, cl, b, cr) else null
            text = "${fm(a)} ${if (cl) "≤" else "<"} x ${if (cr) "≤" else "<"} ${fm(b)}"
        }
        MathText(text, bold = true)
        if (iv == null) { Text("a يجب أن تكون أصغر من b ⟹ مجموعة الحل ∅"); NumberLine(emptyList()) }
        else {
            MathText("= {x: $text ، x∈R}")
            MathText("= ${Choices.fmtIvs(listOf(iv))}", bold = true)
            NumberLine(listOf(iv))
        }
        Text("دائرة مفتوحة للتباين الصارم (<, >) ومغلقة لـ (≤, ≥).", fontSize = 12.sp)
    }
}
