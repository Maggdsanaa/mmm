package com.uniatt.core

/** موعد محاضرة أسبوعي لمادة/شعبة. day: 0=الأحد … 6=السبت. الوقت بالدقائق من منتصف الليل. */
data class RSlot(val courseId: Long, val day: Int, val startMin: Int, val endMin: Int, val room: String = "")

/** أدوات الجدول الأسبوعي (يحدّده المسؤول وتصل للدكتور مع الكشف). */
object Schedule {
    val DAYS = listOf("الأحد", "الإثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت")

    /** يقبل 8:30 و08:30 و٨:٣٠ و8.30 و0830؛ يعيد الدقائق أو null. */
    fun parseTime(input: String): Int? {
        val t = buildString {
            for (c in input.trim()) append(when (c) {
                in '\u0660'..'\u0669' -> '0' + (c - '\u0660')      // أرقام عربية-هندية
                in '\u06F0'..'\u06F9' -> '0' + (c - '\u06F0')
                '.', '٫', '：' -> ':'
                else -> c
            })
        }
        val (h, m) = when {
            t.contains(':') -> t.split(':').let { if (it.size != 2) return null; it[0] to it[1] }
            t.length == 4 && t.all { it.isDigit() } -> t.substring(0, 2) to t.substring(2)
            else -> return null
        }
        val hh = h.trim().toIntOrNull() ?: return null
        val mm = m.trim().toIntOrNull() ?: return null
        if (hh !in 0..23 || mm !in 0..59 || m.trim().length != 2) return null
        return hh * 60 + mm
    }

    fun fmt(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

    fun describe(s: RSlot, withRoom: Boolean = true): String =
        "${DAYS.getOrElse(s.day) { "؟" }} ${fmt(s.startMin)}–${fmt(s.endMin)}" +
            if (withRoom && s.room.isNotBlank()) " (${s.room})" else ""

    /** وصف مواعيد مادة واحدة مرتبة بالأسبوع: «الأحد 08:00–09:30، الثلاثاء 10:00–11:30». */
    fun describeAll(slots: List<RSlot>, courseId: Long, withRoom: Boolean = true): String =
        slots.filter { it.courseId == courseId }.sortedWith(compareBy({ it.day }, { it.startMin }))
            .joinToString("، ") { describe(it, withRoom) }

    /** الموعد الجاري الآن (أو الذي يبدأ خلال [earlyMin] دقيقة)، وإلا null. */
    fun current(slots: List<RSlot>, day: Int, nowMin: Int, earlyMin: Int = 15): RSlot? =
        slots.filter { it.day == day && nowMin >= it.startMin - earlyMin && nowMin < it.endMin }.minByOrNull { it.startMin }

    /** هل يتقاطع موعدان في نفس اليوم؟ */
    fun overlaps(a: RSlot, b: RSlot) = a.day == b.day && a.startMin < b.endMin && b.startMin < a.endMin

    fun valid(s: RSlot) = s.day in 0..6 && s.startMin in 0..1439 && s.endMin in 1..1440 && s.endMin > s.startMin

    // ---- تخزين نصي بسيط (يحفظه هاتف الدكتور) ----
    fun encode(slots: List<RSlot>): String =
        slots.joinToString("\n") { "${it.courseId}\t${it.day}\t${it.startMin}\t${it.endMin}\t${it.room.replace('\t', ' ').replace('\n', ' ')}" }

    fun decode(text: String?): List<RSlot> = (text ?: "").split('\n').filter { it.isNotBlank() }.mapNotNull { l ->
        val f = l.split('\t')
        if (f.size < 4) null else runCatching { RSlot(f[0].toLong(), f[1].toInt(), f[2].toInt(), f[3].toInt(), f.getOrElse(4) { "" }) }
            .getOrNull()?.takeIf { valid(it) }
    }
}
