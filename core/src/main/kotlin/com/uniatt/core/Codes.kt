package com.uniatt.core

import java.security.SecureRandom

/**
 * أكواد التفعيل: 12 خانة من أبجدية Crockford (بدون I L O U) تُعرض هكذا: XXXX-XXXX-XXXX
 *  - الطالب: أول 4 خانات = «وسم» عام يظهر في كشف الدكتور (للبحث فقط)، و7 خانات سرّية، وخانة تحقق أخيرة.
 *  - الدكتور: 11 خانة عشوائية + خانة تحقق، ويُستخدم كعبارة مرور لفتح ملف الكشف المشفّر.
 * خانة التحقق (أوزان فردية mod 32) تكشف أي خطأ في خانة واحدة قبل أن يرتبط الجهاز بكود خاطئ.
 */
object Codes {
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val LENGTH = 12
    const val TAG_LEN = 4

    private fun value(c: Char): Int = ALPHABET.indexOf(c)

    private fun check(body: String): Char {
        var sum = 0
        body.forEachIndexed { i, ch -> sum += (2 * i + 1) * value(ch) }
        return ALPHABET[sum % 32]
    }

    fun random(rnd: SecureRandom = SecureRandom()): String {
        val body = buildString { repeat(LENGTH - 1) { append(ALPHABET[rnd.nextInt(32)]) } }
        return body + check(body)
    }

    /** كود جديد وسمه غير موجود في [existingTags]. */
    fun newUnique(existingTags: Set<String>, rnd: SecureRandom = SecureRandom()): String {
        while (true) {
            val c = random(rnd)
            if (tag(c) !in existingTags) return c
        }
    }

    /** يوحّد ما كتبه المستخدم (حروف صغيرة، شرطات، مسافات، أرقام عربية، O/I/L) ويعيد null إن كان غير صالح. */
    fun normalize(input: String): String? {
        val sb = StringBuilder()
        for (ch in input) {
            val d = Character.digit(ch, 10)             // يشمل الأرقام العربية ٠-٩
            val c = when {
                d >= 0 -> ('0' + d)
                ch.isLetter() -> ch.uppercaseChar()
                else -> continue                        // يتجاهل الشرطات والمسافات
            }
            sb.append(when (c) { 'O' -> '0'; 'I', 'L' -> '1'; else -> c })
        }
        val s = sb.toString()
        if (s.length != LENGTH || s.any { value(it) < 0 }) return null
        return if (check(s.dropLast(1)) == s.last()) s else null
    }

    fun format(code: String): String = code.chunked(4).joinToString("-")
    fun tag(code: String): String = code.substring(0, TAG_LEN)
}
