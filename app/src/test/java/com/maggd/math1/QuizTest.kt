package com.maggd.math1

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class QuizTest {
    @Test fun generatedQuestionsAcceptTheirOwnAnswer() {
        for (l in Quiz.generatedLessons) {
            val rnd = Random(42)
            repeat(300) {
                val q = Quiz.generate(l, rnd)
                val s = q.answers.joinToString(", ") { it.toString() }
                assertTrue("$l: ${q.prompt} / $s", Quiz.check(q, s))
            }
        }
    }
    @Test fun bankQuestionsAcceptTheirOwnAnswer() {
        assertTrue(Bank.all.size > 70)
        for (q in Bank.all) {
            val input = when {
                q.interval != null -> q.interval!!
                q.answers.isEmpty() -> "∅"
                else -> q.answers.joinToString(", ") { it.toString() }
            }
            assertTrue(q.prompt, Quiz.check(q, input))
            if (q.interval != null) assertNotNull(q.prompt, Quiz.parseIntervals(q.interval!!))
        }
    }
    @Test fun everyLessonHasBankQuestions() {
        for (l in Content.lessons) assertTrue(l.id, Bank.all.any { it.lesson == l.id })
    }
    @Test fun fractionsRootsAndOrder() {
        assertTrue(Quiz.check(Question("t", listOf(0.25), "1/4"), "1/4"))
        val r = Question("t", listOf(-4.0, -9.0), "")
        assertTrue(Quiz.check(r, "-9 , -4")); assertFalse(Quiz.check(r, "-4"))
        val o = Question("t", listOf(1.0, 2.0), "", ordered = true)
        assertFalse(Quiz.check(o, "2, 1"))
        val s5 = Bank.all.first { it.display.contains("√5") }
        assertTrue(Quiz.check(s5, "√5, -√5"))
    }
    @Test fun intervalsAndEmptySet() {
        val q = Question("t", display = "", interval = "(-∞,-2)∪(2,∞)")
        assertTrue(Quiz.check(q, "(-inf,-2) U (2,inf)"))
        assertFalse(Quiz.check(q, "(-inf,-2] U (2,inf)"))
        assertTrue(Quiz.check(Question("t", display = "", interval = "(-∞,∞)"), "R"))
        assertTrue(Quiz.check(Question("t", display = ""), "لا يوجد"))
        assertFalse(Quiz.check(Question("t", display = "", interval = "[2,∞)"), "(2,inf)"))
    }
}

class NumberLineTest {
    @org.junit.Test fun formatsNumbers() {
        org.junit.Assert.assertEquals("2", formatNum(2.0))
        org.junit.Assert.assertEquals("-3", formatNum(-3.0))
        org.junit.Assert.assertEquals("16/3", formatNum(16.0 / 3))
        org.junit.Assert.assertEquals("-1/3", formatNum(-1.0 / 3))
        org.junit.Assert.assertEquals("3/2", formatNum(1.5))
    }
}

class ChoicesTest {
    @org.junit.Test fun roundsAreWellFormed() {
        val rnd = Random(7)
        val qs = Bank.all + Quiz.generatedLessons.flatMap { l -> List(30) { Quiz.generate(l, rnd) } }
        for (q in qs) repeat(20) {
            val r = Choices.build(q, rnd)
            val correct = Choices.correctText(q)
            assertEquals(correct, r.solution)
            if (r.statement == null) {
                assertEquals(q.prompt, 4, r.options.size)
                assertEquals(q.prompt, 4, r.options.distinct().size)
                assertEquals(q.prompt, correct, r.options[r.correct])
            } else {
                assertEquals(listOf("صح", "غلط"), r.options)
                val shown = r.statement!!.removePrefix("الحل المقترح: ")
                assertEquals(q.prompt, shown == correct, r.correct == 0)
            }
        }
    }
    @org.junit.Test fun intervalFormatting() {
        assertEquals("(−∞, −2) ∪ (2, ∞)", Choices.fmtIvs(Quiz.parseIntervals("(-∞,-2)∪(2,∞)")!!))
        assertEquals("ℝ", Choices.fmtIvs(Quiz.parseIntervals("R")!!))
        assertEquals("∅", Choices.fmtIvs(emptyList()))
    }
}

class StepsAndGraphTest {
    @org.junit.Test fun everyBankQuestionHasSteps() {
        for (q in Bank.all) org.junit.Assert.assertTrue(q.prompt, q.steps.isNotEmpty())
        val rnd = Random(3)
        for (l in Quiz.generatedLessons) repeat(50) {
            val q = Quiz.generate(l, rnd)
            org.junit.Assert.assertTrue(q.prompt, q.steps.isNotEmpty() && q.steps.none { it.contains("NaN") })
        }
    }
    @org.junit.Test fun quadIneqCases() {
        fun s(a: Double, b: Double, c: Double, op: Int) = Choices.fmtIvs(quadIneq(a, b, c, op))
        org.junit.Assert.assertEquals("(1, 2)", s(1.0, -3.0, 2.0, 2))
        org.junit.Assert.assertEquals("(−∞, −2) ∪ (2, ∞)", s(1.0, 0.0, -4.0, 0))
        org.junit.Assert.assertEquals("[−2, 2]", s(-1.0, 0.0, 4.0, 1))
        org.junit.Assert.assertEquals("ℝ", s(1.0, -8.0, 16.0, 1))
        org.junit.Assert.assertEquals("∅", s(1.0, 0.0, 1.0, 2))
        org.junit.Assert.assertEquals("{4}", s(1.0, -8.0, 16.0, 3))
        org.junit.Assert.assertEquals("(−∞, 4) ∪ (4, ∞)", s(1.0, -8.0, 16.0, 0))
    }
}
