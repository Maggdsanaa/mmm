package com.uniatt.core

/** بيانات الطالب التي تصله من هاتف الدكتور عند أول تقريب ناجح (وتُدمج موادُ كل دكتور). */
data class StudentProfile(
    val studentId: String, val name: String, val faculty: String, val major: String,
    val level: String, val section: String, val courses: List<String>
) {
    fun encode(): ByteArray {
        val fields = listOf(studentId, name, faculty, major, level, section).map { clean(it) } +
            courses.joinToString(RS.toString()) { clean(it) }   // لا يُنظَّف بعد الدمج حتى لا تضيع الفواصل
        return fields.joinToString(US.toString()).toByteArray(Charsets.UTF_8)
    }

    companion object {
        private const val US = '\u001F'
        private const val RS = '\u001E'
        private fun clean(s: String) = s.replace(US, ' ').replace(RS, ' ')

        fun decode(b: ByteArray): StudentProfile? {
            val f = String(b, Charsets.UTF_8).split(US)
            if (f.size != 7 || f[0].isBlank()) return null
            return StudentProfile(f[0], f[1], f[2], f[3], f[4], f[5], f[6].split(RS).filter { it.isNotBlank() })
        }
    }
}
