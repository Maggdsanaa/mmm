package com.maggd.math1

import kotlin.random.Random

/** جولة سؤال: اختيار من متعدد (4 خيارات) أو صح/غلط. */
data class Round(
    val src: String,
    val prompt: String,
    val statement: String?,
    val options: List<String>,
    val correct: Int,
    val solution: String,
    val line: List<Iv>?,
    val note: String,
    val steps: List<String> = emptyList()
)

object Choices {
    private fun s(d: Double) = formatNum(d).replace("-", "−")
    private fun fmtIv(iv: Iv): String {
        if (iv.lo == iv.hi) return "{${s(iv.lo)}}"
        val lo = if (iv.lo.isInfinite()) "−∞" else s(iv.lo)
        val hi = if (iv.hi.isInfinite()) "∞" else s(iv.hi)
        return "${if (iv.loC) "[" else "("}$lo, $hi${if (iv.hiC) "]" else ")"}"
    }
    fun fmtIvs(l: List<Iv>): String = when {
        l.isEmpty() -> "∅"
        l.size == 1 && l[0].lo.isInfinite() && l[0].hi.isInfinite() -> "ℝ"
        else -> l.joinToString(" ∪ ") { fmtIv(it) }
    }

    private fun numText(q: Question, a: List<Double>): String {
        val dbl = !q.ordered && q.answers.size == 2 && q.answers[0] == q.answers[1]
        if (a.size == 1) {
            val p = when { q.display.startsWith("Δ") -> "Δ = "; q.display.startsWith("x") -> "x = "; else -> "" }
            return p + s(a[0])
        }
        if (dbl && a.size == 2 && a[0] == a[1]) return "x = ${s(a[0])} (مكرر)"
        val body = a.joinToString(", ") { s(it) }
        return if (q.ordered) "($body)" else "{$body}"
    }

    private fun isIv(q: Question) = q.interval != null || (q.answers.isEmpty() && (q.lesson == "ineq" || q.lesson == "sign"))

    fun correctText(q: Question): String = when {
        q.interval != null -> fmtIvs(Quiz.parseIntervals(q.interval)!!)
        q.answers.isEmpty() -> if (isIv(q)) "∅" else "∅ (لا يوجد حل)"
        q.distractors.isNotEmpty() -> q.display
        else -> numText(q, q.answers)
    }

    // ---------- مشتتات رقمية ----------
    private fun numCands(a: List<Double>, ordered: Boolean, rnd: Random): List<List<Double>> {
        val c = mutableListOf<List<Double>>()
        c += a.map { -it }; c += a.map { it + 1 }; c += a.map { it - 1 }
        if (a.size == 1) { c += listOf(a[0] * 2); c += listOf(a[0] / 2) }
        if (a.size >= 2) {
            for (i in a.indices) {
                c += a.mapIndexed { j, v -> if (j == i) -v else v }
                c += a.mapIndexed { j, v -> if (j == i) v + 1 else v }
            }
            if (ordered) c += a.reversed()
            if (!ordered) c += a.take(a.size - 1)
        }
        repeat(6) { val k = rnd.nextInt(2, 6); c += a.map { it + k } }
        return c
    }

    // ---------- مشتتات فترات ----------
    private fun complement(l: List<Iv>): List<Iv> {
        val inf = Double.POSITIVE_INFINITY
        if (l.isEmpty()) return listOf(Iv(-inf, false, inf, false))
        val out = mutableListOf<Iv>()
        var lo = -inf; var loC = false
        for (iv in l.sortedBy { it.lo }) {
            if (lo < iv.lo) out += Iv(lo, loC, iv.lo, iv.lo.isFinite() && !iv.loC)
            lo = iv.hi; loC = iv.hi.isFinite() && !iv.hiC
        }
        if (lo < inf) out += Iv(lo, loC, inf, false)
        return out
    }
    private fun ivCands(l: List<Iv>): List<List<Iv>> {
        val inf = Double.POSITIVE_INFINITY
        val c = mutableListOf<List<Iv>>()
        c += complement(l)
        c += l.map { Iv(it.lo, it.lo.isFinite() && !it.loC, it.hi, it.hi.isFinite() && !it.hiC) }
        c += l.map { Iv(it.lo, it.lo.isFinite() && !it.loC, it.hi, it.hiC) }
        c += l.map { Iv(it.lo, it.loC, it.hi, it.hi.isFinite() && !it.hiC) }
        c += l.map { Iv(-it.hi, it.hiC, -it.lo, it.loC) }.sortedBy { it.lo }
        c += l.map { Iv(if (it.lo.isFinite()) -it.lo else it.lo, it.loC, it.hi, it.hiC) }.filter { it.lo < it.hi }.let { if (it.size == l.size) listOf(it) else emptyList() }
        c += l.map { Iv(it.lo, it.loC, if (it.hi.isFinite()) -it.hi else it.hi, it.hiC) }.filter { it.lo < it.hi }.let { if (it.size == l.size) listOf(it) else emptyList() }
        c += listOf(listOf(Iv(-inf, false, inf, false)))
        c += listOf(emptyList<Iv>())
        c += listOf(listOf(Iv(0.0, false, inf, false)))
        c += listOf(listOf(Iv(-inf, false, 0.0, false)))
        c += listOf(listOf(Iv(-1.0, true, 1.0, true)))
        return c
    }

    fun distractors(q: Question, correct: String, rnd: Random): List<String> {
        val pool: List<String> = when {
            q.distractors.isNotEmpty() -> q.distractors
            q.interval != null -> ivCands(Quiz.parseIntervals(q.interval)!!).map { fmtIvs(it) }
            q.answers.isEmpty() && isIv(q) -> ivCands(emptyList()).map { fmtIvs(it) }
            q.answers.isEmpty() -> listOf("x = 0", "x = 1", "{1, −1}", "جميع الأعداد الحقيقية")
            else -> numCands(q.answers, q.ordered, rnd).map { numText(q, it) }
        }
        val clean = pool.filter { it != correct }.distinct()
        val first = clean.take(0)
        val picked = clean.shuffled(rnd).take(3).toMutableList()
        var extra = 7
        while (picked.size < 3) {
            val t = if (q.answers.isNotEmpty()) numText(q, q.answers.map { it + extra }) else "x = $extra"
            if (t != correct && t !in picked) picked += t
            extra++
        }
        return first + picked
    }

    fun clean(p: String): String {
        var t = p.replace(Regex("\\s*\\([^)]*(بفاصلة|اكتب|يقبل)[^)]*\\)"), "")
        t = t.replace("وأجب بالصيغة: x, y", "")
        t = t.lines().filterNot { it.trim().startsWith("أجب بالصيغة") }.joinToString("\n")
        return t.replace(" :", ":").trim()
    }

    fun build(q: Question, rnd: Random): Round {
        val correct = correctText(q)
        val d = distractors(q, correct, rnd)
        val line: List<Iv>? = when {
            q.interval != null -> Quiz.parseIntervals(q.interval)
            q.answers.isEmpty() && isIv(q) -> emptyList()
            else -> null
        }
        val prompt = clean(q.prompt)
        return if (rnd.nextBoolean()) {
            val truth = rnd.nextBoolean()
            val shown = if (truth) correct else d[0]
            Round(q.prompt, prompt, "الحل المقترح: $shown", listOf("صح", "غلط"), if (truth) 0 else 1, correct, line, q.note, q.steps)
        } else {
            val opts = (d.take(3) + correct).shuffled(rnd)
            Round(q.prompt, prompt, null, opts, opts.indexOf(correct), correct, line, q.note, q.steps)
        }
    }
}
