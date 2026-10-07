package com.maggd.math1

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val FRAC = Regex("\\(([^()]+)\\)/\\(([^()]+)\\)|([−-]?\\d+)/(\\d+)")
private val ARABIC = Regex("[\\u0600-\\u06FF]")

/** نص رياضي: يرسم الكسور a/b مكدّسة (بسط فوق مقام) كما في الملزمة. */
@Composable
fun MathText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 16.sp,
    color: Color = Color.Unspecified,
    bold: Boolean = false
) {
    Column(modifier) {
        for (line in text.split("\n")) MathLine(line, fontSize, color, bold)
    }
}

@Composable
private fun MathLine(line: String, fontSize: TextUnit, color: Color, bold: Boolean) {
    val weight = if (bold) FontWeight.Bold else FontWeight.Normal
    val parts = mutableListOf<Any>()
    var last = 0
    for (m in FRAC.findAll(line)) {
        if (m.range.first > last) parts += line.substring(last, m.range.first)
        val num = m.groups[1]?.value ?: m.groups[3]!!.value
        val den = m.groups[2]?.value ?: m.groups[4]!!.value
        parts += Pair(num, den)
        last = m.range.last + 1
    }
    if (last < line.length) parts += line.substring(last)
    if (parts.isEmpty()) parts += ""
    val dir = if (ARABIC.containsMatchIn(line)) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides dir) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            for (p in parts) {
                if (p is String) Text(p, fontSize = fontSize, color = color, fontWeight = weight)
                else {
                    @Suppress("UNCHECKED_CAST")
                    val pr = p as Pair<String, String>
                    Frac(pr.first, pr.second, fontSize, color, weight)
                }
            }
        }
    }
}

@Composable
private fun Frac(num: String, den: String, fontSize: TextUnit, color: Color, weight: FontWeight) {
    val small = (fontSize.value * 0.85f).sp
    val lineColor = if (color == Color.Unspecified) LocalContentColor.current else color
    Column(
        Modifier.width(IntrinsicSize.Max).padding(horizontal = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(num, fontSize = small, color = color, fontWeight = weight)
        Box(Modifier.fillMaxWidth().height(1.dp).background(lineColor))
        Text(den, fontSize = small, color = color, fontWeight = weight)
    }
}
