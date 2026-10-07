package com.maggd.math1

import kotlin.math.abs
import kotlin.math.sqrt

/** خطوات الحل بأسلوب الملزمة (❖ ... ∴). */
object Steps {
    fun f(d: Double) = formatNum(d).replace("-", "−")
    private fun i(n: Int) = if (n < 0) "−${-n}" else "$n"
    private fun p(n: Int) = if (n < 0) "(−${-n})" else "$n"
    private fun term(a: Int, v: String, first: Boolean): String {
        val s = if (abs(a) == 1) v else "${abs(a)}$v"
        return if (first) (if (a < 0) "−$s" else s) else (if (a < 0) "− $s" else "+ $s")
    }
    fun eq(a: Int, v1: String, b: Int, v2: String, c: Int) =
        "${term(a, v1, true)} ${term(b, v2, false)} = ${i(c)}"

    fun lin(a: Int, b: Int, c: Int, x: Int): List<String> {
        val l = mutableListOf("نضع المجاهيل في طرف والثوابت في الطرف الآخر:")
        val co = if (a == 1) "" else "$a"
        if (b != 0) l += "${co}x = ${i(c)} ${if (b >= 0) "−" else "+"} ${abs(b)}"
        l += "${co}x = ${i(c - b)}"
        if (a != 1) l += "نقسم الطرفين على $a:"
        l += "x = ${i(x)}"
        return l
    }

    fun quad(a: Double, b: Double, c: Double): List<String> {
        val l = mutableListOf("a = ${f(a)} ، b = ${f(b)} ، c = ${f(c)}")
        val d = b * b - 4 * a * c
        l += "Δ = b² − 4ac = (${f(b)})² − 4(${f(a)})(${f(c)}) = ${f(d)}"
        if (d < 0) { l += "Δ < 0 ⟹ لا توجد جذور حقيقية"; return l }
        val sq = sqrt(d)
        l += "x = (−b ± √Δ)/(2a) = (${f(-b)} ± ${f(sq)})/(${f(2 * a)})"
        val r1 = (-b + sq) / (2 * a); val r2 = (-b - sq) / (2 * a)
        l += if (d == 0.0) "x = ${f(r1)} (جذر مكرر)" else "x = ${f(r1)} ، x = ${f(r2)}"
        return l
    }

    /** طريقة الحذف (الملزمة 1.1.2.2). */
    fun sys(a1: Int, b1: Int, c1: Int, a2: Int, b2: Int, c2: Int, v1: String, v2: String): List<String> {
        val d = a1 * b2 - a2 * b1
        val dx = c1 * b2 - c2 * b1
        val x = dx.toDouble() / d
        val rhs = c1 - a1 * x
        val y = rhs / b1
        return listOf(
            "${eq(a1, v1, b1, v2, c1)}   (1)",
            "${eq(a2, v1, b2, v2, c2)}   (2)",
            "لحذف $v2: نضرب المعادلة (1) × ${p(b2)} والمعادلة (2) × ${p(b1)}",
            "${eq(a1 * b2, v1, b1 * b2, v2, c1 * b2)}   (3)",
            "${eq(a2 * b1, v1, b1 * b2, v2, c2 * b1)}   (4)",
            "نطرح (4) من (3): ${f(d.toDouble())}$v1 = ${i(dx)}",
            "∴ $v1 = ${f(x)}",
            "بالتعويض في (1): ${p(a1)}(${f(x)}) + ${p(b1)}$v2 = ${i(c1)}",
            "${p(b1)}$v2 = ${f(rhs)} ⟹ $v2 = ${f(y)}",
            "التحقق في (2): ${p(a2)}(${f(x)}) + ${p(b2)}(${f(y)}) = ${f(a2 * x + b2 * y)} = ${i(c2)} ✓"
        )
    }

    /** قاعدة كرامر لمجهولين (الملزمة 2.4.1). */
    fun cramer2(a1: Int, b1: Int, c1: Int, a2: Int, b2: Int, c2: Int, v1: String, v2: String): List<String> {
        val d = a1 * b2 - a2 * b1
        val dx = c1 * b2 - c2 * b1
        val dy = a1 * c2 - a2 * c1
        return listOf(
            "${eq(a1, v1, b1, v2, c1)}   ،   ${eq(a2, v1, b2, v2, c2)}",
            "Δ = (${p(a1)} × ${p(b2)}) − (${p(a2)} × ${p(b1)}) = ${i(d)}",
            "Δ₁ = (${p(c1)} × ${p(b2)}) − (${p(c2)} × ${p(b1)}) = ${i(dx)}",
            "Δ₂ = (${p(a1)} × ${p(c2)}) − (${p(a2)} × ${p(c1)}) = ${i(dy)}",
            "$v1 = Δ₁/Δ = (${i(dx)})/(${i(d)}) = ${f(dx.toDouble() / d)}",
            "$v2 = Δ₂/Δ = (${i(dy)})/(${i(d)}) = ${f(dy.toDouble() / d)}"
        )
    }
}
