package com.maggd.math1

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

data class Question(
    val prompt: String,
    val answers: List<Double> = emptyList(),
    val display: String,
    val lesson: String = "",
    val ordered: Boolean = false,
    val interval: String? = null,
    val needsReview: Boolean = false,
    val note: String = "",
    val tol: Double = 1e-6,
    val distractors: List<String> = emptyList(),
    val steps: List<String> = emptyList()
)

data class Iv(val lo: Double, val loC: Boolean, val hi: Double, val hiC: Boolean)

object Quiz {
    private val EMPTY = setOf("∅", "phi", "ф", "فاي", "لايوجد", "لا يوجد", "none", "{}", "empty")

    private fun norm(raw: String): String {
        val sb = StringBuilder()
        for (c in raw.trim()) sb.append(
            when {
                c in '٠'..'٩' -> '0' + (c - '٠')
                c == '−' || c == '–' -> '-'
                c == '٫' -> '.'
                else -> c
            }
        )
        return sb.toString()
    }

    fun parseNum(raw: String): Double? {
        val s = norm(raw).replace(" ", "")
        if (s.isEmpty()) return null
        var sign = 1.0
        var t = s
        if (t.startsWith("-")) { sign = -1.0; t = t.substring(1) }
        else if (t.startsWith("+")) t = t.substring(1)
        if (t.startsWith("√")) {
            val v = t.substring(1).toDoubleOrNull() ?: return null
            return if (v < 0) null else sign * sqrt(v)
        }
        if (t.contains("/")) {
            val p = t.split("/")
            if (p.size != 2) return null
            val a = p[0].toDoubleOrNull() ?: return null
            val b = p[1].toDoubleOrNull() ?: return null
            return if (b == 0.0) null else sign * a / b
        }
        return t.toDoubleOrNull()?.let { sign * it }
    }

    private fun parseBound(raw: String): Double? {
        val t = norm(raw).replace(" ", "").lowercase().replace("+", "")
        return when (t) {
            "∞", "inf", "oo", "infinity" -> Double.POSITIVE_INFINITY
            "-∞", "-inf", "-oo", "-infinity" -> Double.NEGATIVE_INFINITY
            else -> parseNum(t)
        }
    }

    /** null = invalid text; empty list = empty set. */
    fun parseIntervals(raw: String): List<Iv>? {
        val t = norm(raw).trim()
        if (t.lowercase() in EMPTY) return emptyList()
        if (t.lowercase() == "r" || t == "ℝ") return listOf(Iv(Double.NEGATIVE_INFINITY, false, Double.POSITIVE_INFINITY, false))
        val out = mutableListOf<Iv>()
        for (part in t.split(Regex("[∪Uu]"))) {
            val p = part.trim()
            if (p.length < 5) return null
            val lc = p.first(); val rc = p.last()
            if (lc !in "([" || rc !in ")]") return null
            val inner = p.substring(1, p.length - 1).split(Regex("[,،]"))
            if (inner.size != 2) return null
            val lo = parseBound(inner[0]) ?: return null
            val hi = parseBound(inner[1]) ?: return null
            out.add(Iv(lo, lc == '[' && lo.isFinite(), hi, rc == ']' && hi.isFinite()))
        }
        return out.sortedBy { it.lo }
    }

    private fun same(a: Double, b: Double) =
        (a.isInfinite() && b.isInfinite() && a == b) || (a.isFinite() && b.isFinite() && abs(a - b) < 1e-9)

    fun check(q: Question, input: String): Boolean {
        if (q.interval != null) {
            val exp = parseIntervals(q.interval) ?: return false
            val got = parseIntervals(input) ?: return false
            if (exp.size != got.size) return false
            return exp.indices.all {
                same(exp[it].lo, got[it].lo) && same(exp[it].hi, got[it].hi) &&
                    exp[it].loC == got[it].loC && exp[it].hiC == got[it].hiC
            }
        }
        if (q.answers.isEmpty()) return input.trim().lowercase() in EMPTY
        val toks = input.split(Regex("[,،;\\s]+")).filter { it.isNotEmpty() }
        val nums = toks.map { parseNum(it) ?: return false }
        if (nums.size != q.answers.size) return false
        val a = if (q.ordered) nums else nums.sorted()
        val b = if (q.ordered) q.answers else q.answers.sorted()
        return a.indices.all { abs(a[it] - b[it]) < q.tol }
    }

    // ---------- generators ----------
    private fun sgn(n: Int) = if (n >= 0) "+ $n" else "- ${-n}"
    private fun lin(a: Int, v: String) = if (a == 1) v else if (a == -1) "-$v" else "$a$v"

    val generatedLessons = listOf("linear", "system", "quad")

    fun generate(lesson: String, rnd: Random): Question = when (lesson) {
        "linear" -> {
            val a = rnd.nextInt(2, 7); val x = rnd.nextInt(1, 10); val b = rnd.nextInt(-9, 10)
            Question("حل: ${lin(a, "x")} ${sgn(b)} = ${a * x + b}", listOf(x.toDouble()), "x = $x", lesson,
                steps = Steps.lin(a, b, a * x + b, x))
        }
        "quad" -> {
            var p: Int; var q: Int
            do { p = rnd.nextInt(-6, 7); q = rnd.nextInt(-6, 7) } while (p == 0 || q == 0 || p == q)
            Question("أوجد جذري المعادلة (مفصولين بفاصلة): x² ${sgn(-(p + q))}x ${sgn(p * q)} = 0",
                listOf(p.toDouble(), q.toDouble()), "{$p, $q}", lesson,
                steps = Steps.quad(1.0, -(p + q).toDouble(), (p * q).toDouble()))
        }
        else -> {
            var a1: Int; var b1: Int; var a2: Int; var b2: Int
            do { a1 = rnd.nextInt(1, 5); b1 = rnd.nextInt(1, 5); a2 = rnd.nextInt(1, 5); b2 = rnd.nextInt(1, 5) }
            while (a1 * b2 - a2 * b1 == 0)
            val x = rnd.nextInt(1, 7); val y = rnd.nextInt(1, 7)
            Question("حل النظام وأجب بالصيغة: x, y\n${lin(a1, "x")} + ${lin(b1, "y")} = ${a1 * x + b1 * y}\n" +
                "${lin(a2, "x")} + ${lin(b2, "y")} = ${a2 * x + b2 * y}",
                listOf(x.toDouble(), y.toDouble()), "($x, $y)", "system", ordered = true,
                steps = Steps.sys(a1, b1, a1 * x + b1 * y, a2, b2, a2 * x + b2 * y, "x", "y"))
        }
    }

    /** lesson == null → comprehensive practice. */
    fun next(lesson: String?, rnd: Random, lastPrompt: String? = null): Question {
        repeat(4) {
            val pool = Bank.all.filter { lesson == null || it.lesson == lesson }
            val genLesson = if (lesson == null) generatedLessons[rnd.nextInt(generatedLessons.size)]
                            else if (lesson in generatedLessons) lesson else null
            val q = if (pool.isNotEmpty() && (genLesson == null || rnd.nextFloat() < 0.6f))
                pool[rnd.nextInt(pool.size)] else generate(genLesson ?: "linear", rnd)
            if (q.prompt != lastPrompt) return q
        }
        return Bank.all.first()
    }
}
