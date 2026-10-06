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
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AdminVM(app: Application) : AndroidViewModel(app) {
    private val store = AdminStore(app)
    val state = MutableStateFlow(store.load())
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val syncing = MutableStateFlow(false)
    val syncInfo = MutableStateFlow("")
    private var syncAgain = false

    /** يطبّق تعديلًا على الأحدث من القرص (الواجهة وعامل الخلفية يكتبان معًا) ثم يحدّث الشاشة. */
    private suspend fun mutate(f: (AdminState) -> AdminState) {
        state.value = withContext(Dispatchers.IO) { store.update(f) }
    }

    /** عند الرجوع للتطبيق: قد يكون عامل الخلفية غيّر الحالة (حضور جديد مثلًا). */
    fun refresh() { state.value = store.load() }

    // ---- المزامنة التلقائية مع الدكاترة عبر الترحيل ----
    private fun fmtNow() = SimpleDateFormat("HH:mm", Locale.US).format(Date())

    private fun describe(r: AdminSync.Result): String = when {
        r.noRelay -> "عنوان القاعدة غير صالح"
        r.offline -> "لا اتصال بالإنترنت — تتم المزامنة تلقائيًا عند توفّره"
        else -> "آخر مزامنة ${fmtNow()}: نُشر ${r.pushed} كشف، ${r.newRecords} تسجيل حضور جديد" +
            if (r.errors.isNotEmpty()) "\n⚠ " + r.errors.joinToString("\n⚠ ") else ""
    }

    /** [manual] = المستخدم ضغط الزر، فنعرض النتيجة في نافذة؛ وإلا تبقى في سطر الحالة فقط. */
    fun syncNow(manual: Boolean = false) {
        viewModelScope.launch {
            if (syncing.value) { syncAgain = true; return@launch }
            syncing.value = true
            do {
                syncAgain = false
                val r = try { AdminSync.run(store) } catch (e: Exception) { AdminSync.Result(errors = listOf(e.message ?: "خطأ")) }
                refresh()
                syncInfo.value = describe(r)
                if (manual) message.value = syncInfo.value
            } while (syncAgain)
            syncing.value = false
        }
    }

    /** نص رمز الـQR الذي يمسحه الدكتور مرة واحدة. */
    fun pairingText(doctorId: String): String? {
        val st = state.value
        val d = st.doctors.firstOrNull { it.doctorId == doctorId } ?: return null
        val url = Pairing.normalizeUrl(st.effectiveRelay) ?: return null
        if (d.mailbox.isBlank()) return null
        return Pairing.format(Pairing.Info(url, d.mailbox, d.doctorId, d.code))
    }

    private fun courseKey(c: Course) = "${c.code}-${c.section}".lowercase()

    /** مادة جديدة بشعبة من قائمة الإعدادات؛ إن وُجدت (نفس الرمز والشعبة) يُحدَّث اسمها فقط. */
    fun addCourse(code: String, name: String, section: String) = viewModelScope.launch {
        var err: AdminOps.Err? = null; var renamed = false
        val before = state.value.courses.firstOrNull { it.code.equals(code.trim(), true) && it.section.equals(section, true) }
        mutate { cur -> AdminOps.addCourse(cur, code, name, section).let { (ns, e) -> err = e; ns } }
        if (err != null) { message.value = err!!.msg; return@launch }
        renamed = before != null
        syncNow()
        if (renamed) message.value = "تم تحديث اسم المادة إلى «${name.trim()}»"
    }

    // ---- الإعدادات: التخصصات والشعب ----
    private fun settingOp(f: (AdminState) -> Pair<AdminState, AdminOps.Err?>) = viewModelScope.launch {
        var err: AdminOps.Err? = null
        mutate { cur -> f(cur).let { (ns, e) -> err = e; ns } }
        err?.let { message.value = it.msg }
    }
    fun addMajor(name: String) = settingOp { AdminOps.addMajor(it, name) }
    fun removeMajor(name: String) = viewModelScope.launch { mutate { AdminOps.removeMajor(it, name) } }
    fun addSectionName(name: String) = settingOp { AdminOps.addSectionName(it, name) }
    fun removeSectionName(name: String) = viewModelScope.launch { mutate { AdminOps.removeSectionName(it, name) } }

    // ---- الدكاترة: بالاسم فقط، والمواد من قائمة ----
    fun saveDoctor(id: String?, name: String, courseIds: List<Long>) = viewModelScope.launch {
        var saved: Doctor? = null
        mutate { cur -> AdminOps.saveDoctor(cur, id, name, courseIds, { Codes.random() }, { Pairing.newMailbox() }).let { (ns, d) -> saved = d; ns } }
        if (saved == null) { message.value = "اكتب اسم الدكتور"; return@launch }
        syncNow()
    }

    // ---- الطلاب: بالاسم والتخصص والشعبة والمواد ----
    fun addStudentForm(idInput: String, name: String, major: String, section: String, level: String, courseIds: List<Long>) = viewModelScope.launch {
        if (name.isBlank()) { message.value = "اكتب اسم الطالب"; return@launch }
        busy.value = true
        val tags = state.value.students.map { it.tag }.toHashSet()
        val code = Codes.newUnique(tags)
        val key = withContext(Dispatchers.Default) { Hex.enc(Kdf.studentKey(code)) }
        var err: AdminOps.Err? = null
        mutate { cur -> AdminOps.addStudent(cur, idInput, name, major, section, level, courseIds, code, key).let { (ns, e) -> err = e; ns } }
        busy.value = false
        if (err != null) { message.value = err!!.msg; return@launch }
        syncNow()
        message.value = "تمت إضافة ${name.trim()}\nكوده: ${Codes.format(code)}\n(سلّمه الكود ليكتبه مرة واحدة في تطبيقه)"
    }

    fun editStudentForm(id: String, name: String, major: String, section: String, level: String, courseIds: List<Long>) = viewModelScope.launch {
        var err: AdminOps.Err? = null
        mutate { cur -> AdminOps.editStudent(cur, id, name, major, section, level, courseIds).let { (ns, e) -> err = e; ns } }
        if (err != null) { message.value = err!!.msg; return@launch }
        syncNow()
    }

    /** يحذف المادة مع مواعيدها ويشيلها من الطلاب والدكاترة. */
    fun deleteCourse(id: Long) = viewModelScope.launch {
        mutate { s -> s.copy(
            courses = s.courses.filter { it.id != id },
            slots = s.slots.filter { it.courseId != id },
            students = s.students.map { it.copy(courseIds = it.courseIds - id) },
            doctors = s.doctors.map { it.copy(courseIds = it.courseIds - id) }) }
        syncNow()
    }

    // ---- الجدول الأسبوعي ----
    fun addSlot(courseId: Long, day: Int, start: String, end: String, room: String) = viewModelScope.launch {
        val a = Schedule.parseTime(start); val b = Schedule.parseTime(end)
        if (a == null || b == null) { message.value = "الوقت بصيغة 08:30 (ساعتان:دقيقتان)"; return@launch }
        val slot = RSlot(courseId, day, a, if (b == 0) 1440 else b, room.trim())
        if (!Schedule.valid(slot)) { message.value = "وقت النهاية يجب أن يكون بعد البداية"; return@launch }
        val cur = state.value
        if (cur.slots.any { it.courseId == courseId && it.day == day && it.startMin == a && it.endMin == slot.endMin }) {
            message.value = "هذا الموعد موجود"; return@launch
        }
        // تنبيه (لا منع): دكتور عنده محاضرتان متداخلتان، أو نفس القاعة مشغولة
        val warn = ArrayList<String>()
        val docs = cur.doctors.filter { courseId in it.courseIds }
        for (d in docs) for (o in cur.slots.filter { it.courseId in d.courseIds && it.courseId != courseId })
            if (Schedule.overlaps(slot, RSlot(o.courseId, o.day, o.startMin, o.endMin)))
                warn.add("⚠ ${d.name} عنده محاضرة أخرى متداخلة: ${Schedule.describe(RSlot(o.courseId, o.day, o.startMin, o.endMin), false)}")
        if (slot.room.isNotBlank()) for (o in cur.slots.filter { it.room.equals(slot.room, true) && it.courseId != courseId })
            if (Schedule.overlaps(slot, RSlot(o.courseId, o.day, o.startMin, o.endMin)))
                warn.add("⚠ القاعة ${slot.room} مشغولة في هذا الوقت")
        mutate { s -> val id = s.seq + 1; s.copy(seq = id, slots = s.slots + Slot(id, courseId, day, a, slot.endMin, slot.room)) }
        syncNow()
        if (warn.isNotEmpty()) message.value = "تمت إضافة الموعد.\n" + warn.distinct().joinToString("\n")
    }

    fun deleteSlot(id: Long) = viewModelScope.launch {
        mutate { s -> s.copy(slots = s.slots.filter { it.id != id }) }
        syncNow()
    }

    /** وصف جدول مادة (للعرض). */
    fun scheduleText(courseId: Long): String =
        Schedule.describeAll(state.value.slots.map { RSlot(it.courseId, it.day, it.startMin, it.endMin, it.room) }, courseId)

    // ---- حذف طالب/دكتور ----
    fun deleteStudent(id: String) = viewModelScope.launch {
        mutate { s -> s.copy(students = s.students.filter { it.studentId != id }, bindings = s.bindings.filter { it.studentId != id }) }
        syncNow()
    }

    fun deleteDoctor(id: String) = viewModelScope.launch {
        val cur = state.value
        val d0 = cur.doctors.firstOrNull { it.doctorId == id } ?: return@launch
        val url = Pairing.normalizeUrl(cur.effectiveRelay)
        if (url != null && d0.mailbox.isNotBlank())
            withContext(Dispatchers.IO) { runCatching { Mailbox(Relay(url), d0.mailbox).wipe() } }
        mutate { s -> s.copy(doctors = s.doctors.filter { it.doctorId != id }) }
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
        syncNow()
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
            if (id.contains('|')) { errors.add("$id: معرف الدكتور لا يحتوي على |"); return@forEachIndexed }
            val old = cur.doctors.firstOrNull { it.doctorId == id }
            made[id] = old?.copy(name = name, courseIds = ids)
                ?: Doctor(id, name, ids, Codes.random(), mailbox = Pairing.newMailbox())
        }
        mutate { s ->
            val byId = LinkedHashMap(s.doctors.associateBy { it.doctorId })
            byId.putAll(made)
            s.copy(doctors = byId.values.toList())
        }
        syncNow()
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
        syncNow()
        message.value = "الكود الجديد لـ ${s0.name}: ${Codes.format(code)}\nسيصل الكشف المحدَّث لدكاترته تلقائيًا فيُلغى ربط الجهاز القديم."
    }

    /** كود جديد + صندوق بريد جديد: ينقطع الهاتف القديم ويحتاج الدكتور مسح QR جديد. */
    fun resetDoctor(id: String) = viewModelScope.launch {
        val cur = state.value
        val d0 = cur.doctors.firstOrNull { it.doctorId == id } ?: return@launch
        val url = Pairing.normalizeUrl(cur.effectiveRelay)
        if (url != null && d0.mailbox.isNotBlank()) {
            withContext(Dispatchers.IO) { runCatching { Mailbox(Relay(url), d0.mailbox).wipe() } }   // محاولة أفضل جهد لمحو القديم
        }
        val code = Codes.random()
        mutate { s -> s.copy(doctors = s.doctors.map {
            if (it.doctorId == id) it.copy(code = code, mailbox = Pairing.newMailbox(), rosterHash = "", attHash = "", pushedEpoch = 0, ackEpoch = 0)
            else it
        }) }
        syncNow()
        message.value = "تم إلغاء ربط ${d0.name}. اعرض له QR جديدًا من تبويب الدكاترة ليمسحه."
    }

    private fun exportsDir() = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }

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
        syncNow()
        message.value = "تمت الاستعادة: ${restored.students.size} طالب، ${restored.doctors.size} دكتور"
    }
}
