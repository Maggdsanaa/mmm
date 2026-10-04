package com.uniatt.admin

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uniatt.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class AdminVM(app: Application) : AndroidViewModel(app) {
    private val store = AdminStore(app)
    private val lock = Mutex()
    val state = MutableStateFlow(store.load())
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    /** يطبّق تعديلًا على الحالة ويحفظها (تسلسليًا). */
    private suspend fun mutate(f: (AdminState) -> AdminState) = lock.withLock {
        val s = f(state.value)
        withContext(Dispatchers.IO) { store.save(s) }
        state.value = s
    }

    private fun courseKey(c: Course) = "${c.code}-${c.section}".lowercase()

    fun addCourse(code: String, name: String, section: String) = viewModelScope.launch {
        if (state.value.courses.any { it.code.equals(code, true) && it.section.equals(section, true) }) {
            message.value = "المادة/الشعبة موجودة"; return@launch
        }
        mutate { s ->
            val id = s.seq + 1
            s.copy(seq = id, courses = s.courses + Course(id, code, name, section))
        }
    }

    private fun lines(text: String) = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    private fun cols(line: String) = line.split(Regex("[,\t]")).map { it.trim() }
    private fun keys(s: String?) = (s ?: "").split(";", "؛").map { it.trim() }.filter { it.isNotEmpty() }

    /** يحوّل مفاتيح مثل CS101-A إلى معرّفات؛ المفقود يُعاد منفصلًا. */
    private fun resolve(courses: List<Course>, ks: List<String>): Pair<List<Long>, List<String>> {
        val ids = ArrayList<Long>(); val missing = ArrayList<String>()
        for (k in ks) {
            val c = courses.firstOrNull { courseKey(it) == k.lowercase() }
            if (c != null) ids.add(c.id) else missing.add(k)
        }
        return ids to missing
    }

    private class Row(val s: Student, val needsKey: Boolean)

    /** كل سطر: الرقم الجامعي,الاسم,الكلية,التخصص,المستوى,الشعبة,المواد(CS101-A;MATH1-B) */
    fun addStudents(text: String) = viewModelScope.launch {
        busy.value = true
        val cur = state.value
        val errors = ArrayList<String>()
        val rows = ArrayList<Row>()
        val tags = cur.students.map { it.tag }.toHashSet()
        lines(text).forEachIndexed { i, l ->
            val c = cols(l)
            val id = c.getOrElse(0) { "" }; val name = c.getOrElse(1) { "" }
            if (id.isBlank() || name.isBlank()) { errors.add("سطر ${i + 1}: الرقم الجامعي والاسم مطلوبان"); return@forEachIndexed }
            val (ids, missing) = resolve(cur.courses, keys(c.getOrNull(6)))
            if (missing.isNotEmpty()) { errors.add("$id: مواد غير موجودة: ${missing.joinToString(", ")}"); return@forEachIndexed }
            val old = cur.students.firstOrNull { it.studentId == id } ?: rows.firstOrNull { it.s.studentId == id }?.s
            val code = old?.code ?: Codes.newUnique(tags).also { tags.add(Codes.tag(it)) }
            rows.add(Row(Student(id, name, c.getOrElse(2) { "" }, c.getOrElse(3) { "" }, c.getOrElse(4) { "" },
                c.getOrElse(5) { "" }, ids, code, old?.keyHex ?: ""), needsKey = old == null || old.keyHex.isBlank()))
        }
        // اشتقاق مفاتيح الطلاب الجدد بالتوازي (PBKDF2 بطيء عمدًا)
        val done = withContext(Dispatchers.Default) {
            rows.map { r -> async { if (r.needsKey) r.s.copy(keyHex = Hex.enc(Kdf.studentKey(r.s.code))) else r.s } }.awaitAll()
        }
        mutate { s ->
            val byId = LinkedHashMap(s.students.associateBy { it.studentId })
            done.forEach { byId[it.studentId] = it }
            s.copy(students = byId.values.toList())
        }
        busy.value = false
        message.value = "تمت إضافة/تحديث ${done.size} طالب" + if (errors.isNotEmpty()) "\nأخطاء:\n" + errors.joinToString("\n") else ""
    }

    /** كل سطر: معرف الدكتور,الاسم,المواد(CS101-A;MATH1-B) */
    fun addDoctors(text: String) = viewModelScope.launch {
        val cur = state.value
        val errors = ArrayList<String>()
        val made = LinkedHashMap<String, Doctor>()
        lines(text).forEachIndexed { i, l ->
            val c = cols(l)
            val id = c.getOrElse(0) { "" }; val name = c.getOrElse(1) { "" }
            if (id.isBlank() || name.isBlank()) { errors.add("سطر ${i + 1}: معرف الدكتور والاسم مطلوبان"); return@forEachIndexed }
            val (ids, missing) = resolve(cur.courses, keys(c.getOrNull(2)))
            if (missing.isNotEmpty()) { errors.add("$id: مواد غير موجودة: ${missing.joinToString(", ")}"); return@forEachIndexed }
            val old = cur.doctors.firstOrNull { it.doctorId == id }
            made[id] = Doctor(id, name, ids, old?.code ?: Codes.random())
        }
        mutate { s ->
            val byId = LinkedHashMap(s.doctors.associateBy { it.doctorId })
            byId.putAll(made)
            s.copy(doctors = byId.values.toList())
        }
        message.value = "تمت إضافة/تحديث ${made.size} دكتور" + if (errors.isNotEmpty()) "\nأخطاء:\n" + errors.joinToString("\n") else ""
    }

    /** كود جديد لطالب (فقد هاتفه مثلًا): يُلغى القديم عند وصول كشف محدَّث للدكاترة. */
    fun resetStudent(id: String) = viewModelScope.launch {
        val s0 = state.value.students.firstOrNull { it.studentId == id } ?: return@launch
        busy.value = true
        val tags = state.value.students.map { it.tag }.toHashSet()
        val code = Codes.newUnique(tags)
        val key = withContext(Dispatchers.Default) { Hex.enc(Kdf.studentKey(code)) }
        mutate { s -> s.copy(
            students = s.students.map { if (it.studentId == id) it.copy(code = code, keyHex = key) else it },
            bindings = s.bindings.filter { it.studentId != id }) }
        busy.value = false
        message.value = "الكود الجديد لـ ${s0.name}: ${Codes.format(code)}\nصدّر كشف دكاترته من جديد وأرسله لهم ليُلغى ربط الجهاز القديم."
    }

    fun resetDoctor(id: String) = viewModelScope.launch {
        val code = Codes.random()
        mutate { s -> s.copy(doctors = s.doctors.map { if (it.doctorId == id) it.copy(code = code) else it }) }
        message.value = "كود الدكتور الجديد: ${Codes.format(code)}\nصدّر كشفه من جديد (الملفات القديمة لن تُفتح بالكود الجديد)."
    }

    // ---- التصدير: ملف مشفّر لكل دكتور ----
    private fun safeName(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_")
    private fun exportsDir() = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }

    suspend fun exportRoster(doctorId: String): File? {
        val cur = state.value
        val d = cur.doctors.firstOrNull { it.doctorId == doctorId } ?: return null
        if (d.courseIds.isEmpty()) { message.value = "لا توجد مواد لهذا الدكتور"; return null }
        busy.value = true
        val epoch = cur.epoch + 1
        mutate { it.copy(epoch = epoch) }
        val mine = d.courseIds.toSet()
        val roster = Roster(
            epoch, d.doctorId, d.name,
            cur.courses.filter { it.id in mine }.map { RCourse(it.id, it.code, it.name, it.section) },
            cur.students.filter { s -> s.courseIds.any { it in mine } }.map { s ->
                RStudent(s.studentId, s.name, s.faculty, s.major, s.level, s.section, s.tag, s.keyHex,
                    s.courseIds.filter { it in mine })
            }
        )
        val blob = withContext(Dispatchers.Default) { roster.seal(d.code) }
        val f = withContext(Dispatchers.IO) { File(exportsDir(), "roster_${safeName(d.doctorId)}.uar").also { it.writeBytes(blob) } }
        busy.value = false
        return f
    }

    // ---- استيراد الحضور القادم من الدكاترة ----
    fun importAttendance(bytes: ByteArray?) = viewModelScope.launch {
        if (bytes == null) { message.value = "تعذّرت قراءة الملف"; return@launch }
        val did = Box.hint(Box.MAGIC_ATTEND, bytes) ?: run { message.value = "هذا ليس ملف حضور صالحًا"; return@launch }
        val d = state.value.doctors.firstOrNull { it.doctorId == did } ?: run { message.value = "الدكتور ($did) غير موجود عندك"; return@launch }
        busy.value = true
        val f = withContext(Dispatchers.Default) { AttendanceFile.open(bytes, d.code) }
        if (f == null) {
            busy.value = false
            message.value = "تعذّر فتح الملف: قد يكون كود الدكتور تغيّر بعد إنشائه، أو الملف معدَّل"
            return@launch
        }
        var newRecs = 0
        mutate { s ->
            val sess = LinkedHashMap(s.sessions.associateBy { it.sessionId })
            f.sessions.forEach { sess[it.sessionId] = SessionRow(it.sessionId, it.courseId, f.doctorId, it.startedAt) }
            val recKeys = s.records.map { it.sessionId to it.studentId }.toHashSet()
            val recs = ArrayList(s.records)
            f.records.forEach { if (recKeys.add(it.sessionId to it.studentId)) { recs.add(RecordRow(it.sessionId, it.studentId, it.timestamp)); newRecs++ } }
            val binds = LinkedHashMap(s.bindings.associateBy { it.studentId to it.doctorId })
            f.bindings.forEach { binds[it.studentId to f.doctorId] = BindingRow(it.studentId, f.doctorId, it.pubHex) }
            s.copy(sessions = sess.values.toList(), records = recs, bindings = binds.values.toList())
        }
        busy.value = false
        val conflicts = conflicts().size
        message.value = "تم استيراد حضور ${d.name}: $newRecs تسجيل جديد" +
            if (conflicts > 0) "\n⚠ يوجد $conflicts طالب بجهازين مختلفين — راجع تبويب التقارير" else ""
    }

    /** طلاب ظهر لهم مفتاحا جهاز مختلفان عند دكاترة مختلفين (كود مشارَك أو مكرّر). */
    fun conflicts(): List<Pair<Student, List<String>>> {
        val s = state.value
        return s.bindings.groupBy { it.studentId }
            .filter { (_, v) -> v.map { it.pubHex }.distinct().size > 1 }
            .mapNotNull { (id, v) ->
                val st = s.students.firstOrNull { it.studentId == id } ?: return@mapNotNull null
                st to v.map { b -> s.doctors.firstOrNull { it.doctorId == b.doctorId }?.name ?: b.doctorId }
            }
    }

    // ---- التقارير ----
    fun courseSummary(): List<String> {
        val s = state.value
        return s.courses.map { c ->
            val sess = s.sessions.filter { it.courseId == c.id }
            val ids = sess.map { it.sessionId }.toHashSet()
            val n = s.records.count { it.sessionId in ids }
            "${c.code}-${c.section} ${c.name}: ${sess.size} محاضرة، $n تسجيل حضور"
        }
    }

    private fun q(x: String) = "\"" + x.replace("\"", "\"\"") + "\""

    fun studentsCsv(): String = "studentId,name,code,device_seen\n" + state.value.students.joinToString("\n") { st ->
        "${st.studentId},${q(st.name)},${Codes.format(st.code)},${state.value.bindings.any { it.studentId == st.studentId }}"
    }

    fun doctorsCsv(): String = "doctorId,name,code\n" + state.value.doctors.joinToString("\n") {
        "${it.doctorId},${q(it.name)},${Codes.format(it.code)}"
    }

    fun attendanceCsv(): String {
        val s = state.value
        val sessionsByCourse = s.sessions.groupBy { it.courseId }
        val sb = StringBuilder("studentId,name,course,attended,sessions,percent\n")
        for (st in s.students) for (cid in st.courseIds) {
            val c = s.courses.firstOrNull { it.id == cid } ?: continue
            val ids = (sessionsByCourse[cid] ?: emptyList()).map { it.sessionId }.toHashSet()
            val att = s.records.count { it.studentId == st.studentId && it.sessionId in ids }
            val pct = if (ids.isEmpty()) 0 else att * 100 / ids.size
            sb.append("${st.studentId},${q(st.name)},${c.code}-${c.section},$att,${ids.size},$pct\n")
        }
        return sb.toString()
    }

    // ---- نسخة احتياطية مشفّرة (المسؤول هو المرجع الوحيد، فضياع هاتفه = ضياع كل الأكواد) ----
    suspend fun backup(): Pair<File, String>? {
        val pass = Codes.random()
        busy.value = true
        val blob = withContext(Dispatchers.Default) {
            Box.seal(Box.MAGIC_BACKUP, "admin", StateJson.toJson(state.value).toByteArray(Charsets.UTF_8), pass)
        }
        val f = withContext(Dispatchers.IO) { File(exportsDir(), "uniatt_backup_${System.currentTimeMillis() / 1000}.uab").also { it.writeBytes(blob) } }
        busy.value = false
        return f to pass
    }

    fun restore(bytes: ByteArray?, passInput: String) = viewModelScope.launch {
        val pass = Codes.normalize(passInput) ?: run { message.value = "كود النسخة غير صحيح"; return@launch }
        if (bytes == null) { message.value = "تعذّرت قراءة الملف"; return@launch }
        busy.value = true
        val restored = withContext(Dispatchers.Default) {
            Box.open(Box.MAGIC_BACKUP, bytes, pass)?.let { runCatching { StateJson.fromJson(String(it, Charsets.UTF_8)) }.getOrNull() }
        }
        busy.value = false
        if (restored == null) { message.value = "تعذّرت الاستعادة: الكود خاطئ أو الملف تالف"; return@launch }
        mutate { restored }
        message.value = "تمت الاستعادة: ${restored.students.size} طالب، ${restored.doctors.size} دكتور"
    }
}
