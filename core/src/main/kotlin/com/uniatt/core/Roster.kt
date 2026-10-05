package com.uniatt.core

/**
 * ملف الكشف الذي يصدّره المسؤول لكل دكتور (نص مفصول بـ TAB ثم يُشفَّر بـ Box).
 * يحتوي مواد الدكتور وطلابها، ولكل طالب «وسمه» ومفتاحه المشتق من كوده (وليس الكود نفسه).
 */
private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

data class RCourse(val id: Long, val code: String, val name: String, val section: String)

data class RStudent(
    val studentId: String, val name: String, val faculty: String, val major: String,
    val level: String, val section: String, val tag: String, val keyHex: String, val courseIds: List<Long>
)

data class Roster(
    val epoch: Long, val doctorId: String, val doctorName: String,
    val courses: List<RCourse>, val students: List<RStudent>,
    val slots: List<RSlot> = emptyList()              // جدول المحاضرات (يحدّده المسؤول)
) {
    fun toText(): String = buildString {
        append("UAR1\n")
        append("E\t$epoch\n")
        append("D\t${clean(doctorId)}\t${clean(doctorName)}\n")
        courses.forEach { append("C\t${it.id}\t${clean(it.code)}\t${clean(it.name)}\t${clean(it.section)}\n") }
        slots.forEach { append("T\t${it.courseId}\t${it.day}\t${it.startMin}\t${it.endMin}\t${clean(it.room)}\n") }
        students.forEach {
            append("S\t${clean(it.studentId)}\t${clean(it.name)}\t${clean(it.faculty)}\t${clean(it.major)}\t")
            append("${clean(it.level)}\t${clean(it.section)}\t${it.tag}\t${it.keyHex}\t${it.courseIds.joinToString(";")}\n")
        }
    }

    fun seal(doctorCode: String): ByteArray =
        Box.seal(Box.MAGIC_ROSTER, doctorId, toText().toByteArray(Charsets.UTF_8), doctorCode)

    companion object {
        fun parse(text: String): Roster? = try {
            val lines = text.split('\n').filter { it.isNotEmpty() }
            if (lines.firstOrNull() != "UAR1") null
            else {
                var epoch = -1L; var did = ""; var dname = ""; var haveDoctor = false
                val courses = ArrayList<RCourse>(); val students = ArrayList<RStudent>(); val slots = ArrayList<RSlot>()
                var bad = false
                for (line in lines.drop(1)) {
                    val f = line.split('\t')
                    when (f[0]) {
                        "E" -> epoch = f[1].toLong()
                        "D" -> { did = f[1]; dname = f[2]; haveDoctor = true }
                        "C" -> if (f.size == 5) courses.add(RCourse(f[1].toLong(), f[2], f[3], f[4])) else bad = true
                        "T" -> if (f.size == 6) {
                            val sl = RSlot(f[1].toLong(), f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5])
                            if (Schedule.valid(sl)) slots.add(sl) else bad = true
                        } else bad = true
                        "S" -> if (f.size == 10 && f[7].length == Codes.TAG_LEN && f[8].length == 64)
                            students.add(RStudent(f[1], f[2], f[3], f[4], f[5], f[6], f[7], f[8],
                                f[9].split(';').filter { it.isNotEmpty() }.map { it.toLong() }))
                        else bad = true
                        else -> bad = true
                    }
                }
                if (bad || epoch < 0 || !haveDoctor || did.isBlank()) null else Roster(epoch, did, dname, courses, students, slots)
            }
        } catch (e: Exception) { null }

        /** null = كود خاطئ أو ملف تالف/معدَّل. */
        fun open(blob: ByteArray, doctorCode: String): Roster? =
            Box.open(Box.MAGIC_ROSTER, blob, doctorCode)?.let { parse(String(it, Charsets.UTF_8)) }
    }
}

/** ملف الحضور الذي يعيده الدكتور للمسؤول (مشفّر بنفس كود الدكتور، فهو موثَّق أيضًا). */
data class ASession(val sessionId: String, val courseId: Long, val startedAt: Long, val durationMin: Int)
data class ARecord(val sessionId: String, val studentId: String, val timestamp: Long)
data class ABinding(val studentId: String, val tag: String, val pubHex: String)

data class AttendanceFile(
    val doctorId: String, val epoch: Long,
    val sessions: List<ASession>, val records: List<ARecord>, val bindings: List<ABinding>
) {
    fun toText(): String = buildString {
        append("UAT1\n")
        append("D\t${clean(doctorId)}\t$epoch\n")
        sessions.forEach { append("S\t${it.sessionId}\t${it.courseId}\t${it.startedAt}\t${it.durationMin}\n") }
        records.forEach { append("A\t${it.sessionId}\t${clean(it.studentId)}\t${it.timestamp}\n") }
        bindings.forEach { append("B\t${clean(it.studentId)}\t${it.tag}\t${it.pubHex}\n") }
    }

    fun seal(doctorCode: String): ByteArray =
        Box.seal(Box.MAGIC_ATTEND, doctorId, toText().toByteArray(Charsets.UTF_8), doctorCode)

    companion object {
        fun parse(text: String): AttendanceFile? = try {
            val lines = text.split('\n').filter { it.isNotEmpty() }
            if (lines.firstOrNull() != "UAT1") null
            else {
                var did = ""; var epoch = 0L; var bad = false
                val ss = ArrayList<ASession>(); val rs = ArrayList<ARecord>(); val bs = ArrayList<ABinding>()
                for (line in lines.drop(1)) {
                    val f = line.split('\t')
                    when (f[0]) {
                        "D" -> { did = f[1]; epoch = f[2].toLong() }
                        "S" -> if (f.size == 5) ss.add(ASession(f[1], f[2].toLong(), f[3].toLong(), f[4].toInt())) else bad = true
                        "A" -> if (f.size == 4) rs.add(ARecord(f[1], f[2], f[3].toLong())) else bad = true
                        "B" -> if (f.size == 4) bs.add(ABinding(f[1], f[2], f[3])) else bad = true
                        else -> bad = true
                    }
                }
                if (bad || did.isBlank()) null else AttendanceFile(did, epoch, ss, rs, bs)
            }
        } catch (e: Exception) { null }

        fun open(blob: ByteArray, doctorCode: String): AttendanceFile? =
            Box.open(Box.MAGIC_ATTEND, blob, doctorCode)?.let { parse(String(it, Charsets.UTF_8)) }
    }
}
