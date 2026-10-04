package com.uniatt.doctor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uniatt.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class ReportRow(val studentId: String, val name: String, val attended: Int, val total: Int)

@OptIn(ExperimentalCoroutinesApi::class)
class DoctorVM(app: Application) : AndroidViewModel(app) {
    val dao = AppDb.get(app).dao()
    private val prefs = app.getSharedPreferences("doctor", 0)

    val loggedIn = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val courses = dao.courses().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val selectedCourseId = MutableStateFlow<Long?>(null)
    val active = MutableStateFlow<ActiveSession?>(null)
    val events = MutableStateFlow<List<ScanEvent>>(emptyList())
    val historySession = MutableStateFlow<String?>(null)
    val report = MutableStateFlow<List<ReportRow>>(emptyList())

    val liveAttendance = active.flatMapLatest { s ->
        if (s == null) flowOf(emptyList()) else dao.attendanceOf(s.sessionId.toString())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val students = selectedCourseId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else dao.studentsOf(id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val sessions = selectedCourseId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else dao.sessionsOf(id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val historyAttendance = historySession.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else dao.attendanceOf(id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        // بعد إعادة تشغيل التطبيق: اختر أول مادة تلقائيًا
        viewModelScope.launch {
            if (selectedCourseId.value == null) selectedCourseId.value = dao.coursesOnce().firstOrNull()?.id
        }
    }

    // ---- قفل محلي بالرمز السري (لا علاقة له بكود الدكتور) ----
    private fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    fun hasAccount() = prefs.getString("pin", null) != null
    fun register(pin: String) { prefs.edit().putString("pin", hash(pin)).apply(); loggedIn.value = true }
    fun login(pin: String) {
        if (hash(pin) == prefs.getString("pin", null)) loggedIn.value = true else message.value = "رمز الدخول خاطئ"
    }

    // ---- ملف الكشف القادم من المسؤول (واتساب/إيميل) ----
    val doctorId get() = prefs.getString("id", "") ?: ""
    val doctorName get() = prefs.getString("name", "") ?: ""
    val epoch get() = prefs.getLong("epoch", -1L)
    val linked = MutableStateFlow(prefs.getString("id", null) != null)
    val importing = MutableStateFlow(false)
    val pendingFile = MutableStateFlow<ByteArray?>(null)
    val pendingHint = MutableStateFlow<String?>(null)

    fun pickRoster(bytes: ByteArray?) {
        pendingFile.value = bytes
        pendingHint.value = bytes?.let { Box.hint(Box.MAGIC_ROSTER, it) }
        if (bytes != null && pendingHint.value == null) message.value = "هذا ليس ملف كشف صالحًا"
    }

    fun importRoster(codeInput: String) = viewModelScope.launch {
        val bytes = pendingFile.value ?: run { message.value = "اختر ملف الكشف أولًا"; return@launch }
        val code = Codes.normalize(codeInput) ?: run { message.value = "الكود غير صحيح — راجع الخانات (12 خانة)"; return@launch }
        importing.value = true
        val roster = withContext(Dispatchers.Default) { Roster.open(bytes, code) }
        if (roster == null) {
            importing.value = false
            message.value = "تعذّر فتح الملف: الكود خاطئ أو الملف تالف/معدَّل"
            return@launch
        }
        if (linked.value && roster.doctorId != doctorId) {
            importing.value = false
            message.value = "هذا الكشف يخص دكتورًا آخر (${roster.doctorName}) ولا يمكن دمجه هنا"
            return@launch
        }
        if (roster.epoch < epoch) {
            importing.value = false
            message.value = "هذا الكشف أقدم من الكشف المستورد سابقًا — اطلب الأحدث من الإدارة"
            return@launch
        }

        val ids = roster.courses.map { it.id }
        roster.courses.forEach { dao.upsertCourse(CourseSection(it.id, it.code, it.name, it.section)) }
        dao.clearEnrollments(ids)
        var resetCount = 0
        for (r in roster.students) {
            val old = dao.student(r.studentId)
            val keep = old != null && old.tag == r.tag          // وسم جديد = كود جديد = يُلغى ربط الجهاز القديم
            if (old != null && !keep && old.publicKeyHex != null) resetCount++
            dao.upsertStudent(StudentEntity(r.studentId, r.name, r.faculty, r.major, r.level, r.section,
                r.tag, r.keyHex, if (keep) old?.publicKeyHex else null))
            r.courseIds.filter { it in ids }.forEach { dao.enroll(Enrollment(r.studentId, it)) }
        }

        prefs.edit().putString("id", roster.doctorId).putString("name", roster.doctorName)
            .putLong("epoch", roster.epoch).putString("code", code).apply()
        linked.value = true
        if (selectedCourseId.value == null) selectedCourseId.value = ids.firstOrNull()
        pendingFile.value = null; pendingHint.value = null
        importing.value = false
        message.value = "تم استيراد الكشف: ${roster.students.size} طالب في ${roster.courses.size} مادة" +
            if (resetCount > 0) "\nأُلغي ربط $resetCount جهاز بسبب أكواد جديدة من الإدارة" else ""
    }

    // ---- إعادة الحضور للمسؤول (ملف مشفّر بكود الدكتور) ----
    /** يكتب الملف في مجلد الكاش ويعيده للمشاركة. */
    suspend fun buildAttendanceExport(): File? = withContext(Dispatchers.IO) {
        val code = prefs.getString("code", null) ?: return@withContext null
        val did = doctorId.ifBlank { return@withContext null }
        val sess = dao.allSessions().map { ASession(it.sessionId, it.courseSectionId, it.startedAt, it.durationMin) }
        val recs = dao.allAttendance().map { ARecord(it.sessionId, it.studentId, it.timestamp) }
        val binds = dao.boundStudents().map { ABinding(it.studentId, it.tag, it.publicKeyHex!!) }
        val blob = AttendanceFile(did, epoch, sess, recs, binds).seal(code)
        val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
        File(dir, "attendance_$did.uat").also { it.writeBytes(blob) }
    }

    // ---- الجلسات ----
    fun startSession(course: CourseSection, minutes: Int) = viewModelScope.launch {
        active.value?.let { dao.endSession(it.sessionId.toString(), System.currentTimeMillis()) }
        val s = ActiveSession(UUID.randomUUID(), course, doctorId, System.currentTimeMillis(), minutes)
        dao.addSession(SessionEntity(s.sessionId.toString(), course.id, doctorId, s.startedAt, minutes))
        events.value = emptyList()
        active.value = s
    }

    fun endSession() = viewModelScope.launch {
        active.value?.let { dao.endSession(it.sessionId.toString(), System.currentTimeMillis()) }
        active.value = null
    }

    fun onScan(e: ScanEvent) { events.update { (listOf(e) + it).take(50) } }

    fun loadReport() = viewModelScope.launch {
        val cid = selectedCourseId.value ?: run { report.value = emptyList(); return@launch }
        val att = dao.attendanceForCourse(cid).groupingBy { it.studentId }.eachCount()
        val total = dao.sessionCount(cid)
        report.value = dao.studentsOfOnce(cid).map { ReportRow(it.studentId, it.name, att[it.studentId] ?: 0, total) }
    }

    fun csv(rows: List<ReportRow>): String = buildString {
        append("studentId,name,attended,sessions,percent\n")
        rows.forEach {
            val pct = if (it.total == 0) 0 else it.attended * 100 / it.total
            append("${it.studentId},\"${it.name.replace("\"", "\"\"")}\",${it.attended},${it.total},$pct\n")
        }
    }
}
