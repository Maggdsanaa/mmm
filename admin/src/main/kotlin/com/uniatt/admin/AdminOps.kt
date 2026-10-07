package com.uniatt.admin

/**
 * عمليات الإدارة كدوال نقية على الحالة (قابلة للاختبار): التخصصات والشعب (الإعدادات)، الدكاترة، الطلاب.
 * الأخطاء تُعاد نصًا عربيًا مفهومًا للمستخدم.
 */
object AdminOps {
    class Err(val msg: String)

    private fun List<String>.has(x: String) = any { it.equals(x, ignoreCase = true) }

    // ---- الإعدادات: التخصصات والشعب ----
    fun addMajor(s: AdminState, name: String): Pair<AdminState, Err?> {
        val n = name.trim()
        if (n.isEmpty()) return s to Err("اكتب اسم التخصص")
        if (s.majors.has(n)) return s to Err("التخصص موجود")
        return s.copy(majors = s.majors + n) to null
    }
    fun removeMajor(s: AdminState, name: String) = s.copy(majors = s.majors.filter { it != name })

    fun addSectionName(s: AdminState, name: String): Pair<AdminState, Err?> {
        val n = name.trim()
        if (n.isEmpty()) return s to Err("اكتب اسم الشعبة")
        if (n.contains('-') || n.contains(';') || n.contains('؛') || n.contains(',')) return s to Err("اسم الشعبة بلا - ; ،")
        if (s.sectionNames.has(n)) return s to Err("الشعبة موجودة")
        return s.copy(sectionNames = s.sectionNames + n) to null
    }
    fun removeSectionName(s: AdminState, name: String) = s.copy(sectionNames = s.sectionNames.filter { it != name })

    // ---- الدكاترة: بالاسم فقط (المعرّف يُولَّد)، والمواد تُختار من قائمة ----
    /** [id] = null لدكتور جديد. يعيد الحالة والدكتور. */
    fun saveDoctor(s: AdminState, id: String?, name: String, courseIds: List<Long>, newCode: () -> String, newMailbox: () -> String): Pair<AdminState, Doctor?> {
        val n = name.trim()
        if (n.isEmpty()) return s to null
        val valid = courseIds.filter { c -> s.courses.any { it.id == c } }.distinct()
        if (id != null) {
            val old = s.doctors.firstOrNull { it.doctorId == id } ?: return s to null
            val d = old.copy(name = n, courseIds = valid)
            return s.copy(doctors = s.doctors.map { if (it.doctorId == id) d else it }) to d
        }
        var seq = s.seq
        var newId: String
        do { seq++; newId = "D$seq" } while (s.doctors.any { it.doctorId == newId })
        val d = Doctor(newId, n, valid, newCode(), mailbox = newMailbox())
        return s.copy(seq = seq, doctors = s.doctors + d) to d
    }

    // ---- الطلاب: بالاسم والتخصص والشعبة والمواد ----
    /** طالب جديد. [idInput] فارغ = رقم داخلي يُولَّد. [code]/[keyHex] يُحسبان خارجًا (PBKDF2 بطيء). */
    fun addStudent(
        s: AdminState, idInput: String, name: String, major: String, section: String, level: String,
        courseIds: List<Long>, code: String, keyHex: String
    ): Pair<AdminState, Err?> {
        val n = name.trim()
        if (n.isEmpty()) return s to Err("اكتب اسم الطالب")
        var seq = s.seq
        var id = idInput.trim()
        if (id.contains(',') || id.contains('\t') || id.contains('|')) return s to Err("الرقم الجامعي لا يحتوي , أو |")
        if (id.isEmpty()) { do { seq++; id = "S$seq" } while (s.students.any { it.studentId == id }) }
        else if (s.students.any { it.studentId == id }) return s to Err("الرقم الجامعي $id موجود لطالب آخر")
        val valid = courseIds.filter { c -> s.courses.any { it.id == c } }.distinct()
        val st = Student(id, n, "", major, level.trim(), section, valid, code, keyHex)
        return s.copy(seq = seq, students = s.students + st) to null
    }

    fun editStudent(
        s: AdminState, id: String, name: String, major: String, section: String, level: String, courseIds: List<Long>
    ): Pair<AdminState, Err?> {
        val n = name.trim()
        if (n.isEmpty()) return s to Err("اكتب اسم الطالب")
        if (s.students.none { it.studentId == id }) return s to Err("الطالب غير موجود")
        val valid = courseIds.filter { c -> s.courses.any { it.id == c } }.distinct()
        return s.copy(students = s.students.map {
            if (it.studentId == id) it.copy(name = n, major = major, section = section, level = level.trim(), courseIds = valid) else it
        }) to null
    }

    /** مادة جديدة بشعبة من قائمة الإعدادات. */
    fun addCourse(s: AdminState, code: String, name: String, section: String): Pair<AdminState, Err?> {
        val c = code.trim(); val n = name.trim()
        if (c.isEmpty() || n.isEmpty()) return s to Err("اكتب رمز المادة واسمها")
        if (section.isBlank()) return s to Err("اختر الشعبة (تُنشأ من الإعدادات)")
        if (c.contains('-') || c.contains(';') || c.contains('؛') || c.contains(',')) return s to Err("رمز المادة بلا - ; ،")
        val dup = s.courses.firstOrNull { it.code.equals(c, true) && it.section.equals(section, true) }
        if (dup != null) {
            if (dup.name == n) return s to Err("المادة/الشعبة موجودة")
            return s.copy(courses = s.courses.map { if (it.id == dup.id) it.copy(name = n) else it }) to null
        }
        val id = s.seq + 1
        return s.copy(seq = id, courses = s.courses + Course(id, c, n, section)) to null
    }
}
