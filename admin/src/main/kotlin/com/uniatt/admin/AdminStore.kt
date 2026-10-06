package com.uniatt.admin

import android.content.Context
import com.uniatt.core.Codes
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** إعدادات ثابتة داخل التطبيق: قاعدة البيانات الوسيطة (مخفية عن المستخدم) تُضمَّن أيضًا في QR الدكاترة. */
object AdminConfig {
    const val DEFAULT_RELAY = "https://maggd-141d1-default-rtdb.europe-west1.firebasedatabase.app"
}

data class Course(val id: Long, val code: String, val name: String, val section: String)

/** موعد أسبوعي لمادة/شعبة (يحدّده المسؤول): day 0=الأحد…6=السبت، والوقت بالدقائق. */
data class Slot(val id: Long, val courseId: Long, val day: Int, val startMin: Int, val endMin: Int, val room: String)

data class Student(
    val studentId: String, val name: String, val faculty: String, val major: String,
    val level: String, val section: String, val courseIds: List<Long>,
    val code: String, val keyHex: String
) { val tag get() = Codes.tag(code) }

/**
 * mailbox: صندوق بريده على الترحيل (يُولَّد تلقائيًا). rosterHash: بصمة آخر كشف نُشر (لا نعيد النشر إن لم يتغيّر شيء).
 * attHash: بصمة آخر ملف حضور استوردناه. ackEpoch: آخر إصدار كشف أكّد هاتف الدكتور استلامه.
 */
data class Doctor(
    val doctorId: String, val name: String, val courseIds: List<Long>, val code: String,
    val mailbox: String = "", val rosterHash: String = "", val attHash: String = "",
    val pushedEpoch: Long = 0, val ackEpoch: Long = 0, val lastPullAt: Long = 0
)

/** مما تعيده هواتف الدكاترة: ربط جهاز طالب، جلسات، وحضور. */
data class BindingRow(val studentId: String, val doctorId: String, val pubHex: String)
data class SessionRow(val sessionId: String, val courseId: Long, val doctorId: String, val startedAt: Long)
data class RecordRow(val sessionId: String, val studentId: String, val timestamp: Long)

data class AdminState(
    val seq: Long = 0,                // مولّد معرّفات المواد
    val epoch: Long = 0,              // يزيد مع كل تصدير كشف (يرفض الدكتور الأقدم)
    val courses: List<Course> = emptyList(),
    val slots: List<Slot> = emptyList(),
    val students: List<Student> = emptyList(),
    val doctors: List<Doctor> = emptyList(),
    val bindings: List<BindingRow> = emptyList(),
    val sessions: List<SessionRow> = emptyList(),
    val records: List<RecordRow> = emptyList(),
    val relayUrl: String = "",        // اختياري (للتوافق مع النسخ الاحتياطية)؛ الفارغ = AdminConfig.DEFAULT_RELAY
    val majors: List<String> = emptyList(),    // التخصصات (من الإعدادات)
    val sectionNames: List<String> = emptyList() // أسماء الشعب (من الإعدادات)
) {
    val effectiveRelay: String get() = relayUrl.ifBlank { AdminConfig.DEFAULT_RELAY }
}

private fun <T> JSONArray.mapObj(f: (JSONObject) -> T): List<T> = (0 until length()).map { f(getJSONObject(it)) }
private fun longs(a: JSONArray): List<Long> = (0 until a.length()).map { a.getLong(it) }

object StateJson {
    fun toJson(s: AdminState): String = JSONObject()
        .put("v", 2).put("seq", s.seq).put("epoch", s.epoch).put("relay", s.relayUrl)
        .put("majors", JSONArray(s.majors)).put("sectionNames", JSONArray(s.sectionNames))
        .put("courses", JSONArray(s.courses.map {
            JSONObject().put("id", it.id).put("code", it.code).put("name", it.name).put("section", it.section)
        }))
        .put("slots", JSONArray(s.slots.map {
            JSONObject().put("id", it.id).put("c", it.courseId).put("d", it.day).put("s", it.startMin).put("e", it.endMin).put("r", it.room)
        }))
        .put("students", JSONArray(s.students.map {
            JSONObject().put("id", it.studentId).put("name", it.name).put("faculty", it.faculty).put("major", it.major)
                .put("level", it.level).put("section", it.section).put("courses", JSONArray(it.courseIds))
                .put("code", it.code).put("key", it.keyHex)
        }))
        .put("doctors", JSONArray(s.doctors.map {
            JSONObject().put("id", it.doctorId).put("name", it.name).put("courses", JSONArray(it.courseIds)).put("code", it.code)
                .put("mbox", it.mailbox).put("rh", it.rosterHash).put("ah", it.attHash)
                .put("pe", it.pushedEpoch).put("ae", it.ackEpoch).put("lp", it.lastPullAt)
        }))
        .put("bindings", JSONArray(s.bindings.map {
            JSONObject().put("s", it.studentId).put("d", it.doctorId).put("k", it.pubHex)
        }))
        .put("sessions", JSONArray(s.sessions.map {
            JSONObject().put("id", it.sessionId).put("c", it.courseId).put("d", it.doctorId).put("t", it.startedAt)
        }))
        .put("records", JSONArray(s.records.map {
            JSONObject().put("s", it.sessionId).put("u", it.studentId).put("t", it.timestamp)
        }))
        .toString()

    fun fromJson(text: String): AdminState {
        val j = JSONObject(text)
        return AdminState(
            seq = j.getLong("seq"), epoch = j.getLong("epoch"), relayUrl = j.optString("relay", ""),
            majors = j.optJSONArray("majors")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            sectionNames = j.optJSONArray("sectionNames")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            courses = j.getJSONArray("courses").mapObj { Course(it.getLong("id"), it.getString("code"), it.getString("name"), it.getString("section")) },
            slots = j.optJSONArray("slots")?.mapObj { Slot(it.getLong("id"), it.getLong("c"), it.getInt("d"), it.getInt("s"), it.getInt("e"), it.optString("r", "")) } ?: emptyList(),
            students = j.getJSONArray("students").mapObj {
                Student(it.getString("id"), it.getString("name"), it.getString("faculty"), it.getString("major"),
                    it.getString("level"), it.getString("section"), longs(it.getJSONArray("courses")),
                    it.getString("code"), it.getString("key"))
            },
            doctors = j.getJSONArray("doctors").mapObj {
                Doctor(it.getString("id"), it.getString("name"), longs(it.getJSONArray("courses")), it.getString("code"),
                    it.optString("mbox", ""), it.optString("rh", ""), it.optString("ah", ""),
                    it.optLong("pe", 0), it.optLong("ae", 0), it.optLong("lp", 0))
            },
            bindings = j.getJSONArray("bindings").mapObj { BindingRow(it.getString("s"), it.getString("d"), it.getString("k")) },
            sessions = j.getJSONArray("sessions").mapObj { SessionRow(it.getString("id"), it.getLong("c"), it.getString("d"), it.getLong("t")) },
            records = j.getJSONArray("records").mapObj { RecordRow(it.getString("s"), it.getString("u"), it.getLong("t")) }
        )
    }
}

/**
 * حفظ حالة المسؤول في ملف داخل مساحة التطبيق (كتابة ذرّية).
 * يصل إليه الواجهة (AdminVM) وعامل الخلفية (SyncWorker) معًا، لذلك كل تعديل يمرّ عبر [update]
 * الذي يقرأ الأحدث من القرص داخل قفل واحد للعملية، فلا يضيع تعديل أحدهما بسبب الآخر.
 */
class AdminStore(private val dir: File) {
    constructor(ctx: Context) : this(ctx.filesDir)

    private val file = File(dir, "admin_state.json")

    private fun read(): AdminState {
        if (!file.exists()) return AdminState()
        return try { StateJson.fromJson(file.readText()) } catch (e: Exception) {
            // لا نُصفّر بصمت: نُبقي نسخة من الملف التالف قبل البدء من جديد
            try { file.copyTo(File(dir, "admin_state.corrupt.json"), overwrite = true) } catch (_: Exception) {}
            AdminState()
        }
    }

    private fun write(s: AdminState) {
        val tmp = File(dir, "admin_state.json.tmp")
        tmp.writeText(StateJson.toJson(s))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun load(): AdminState = synchronized(LOCK) { read() }

    fun save(s: AdminState) = synchronized(LOCK) { write(s) }

    /** يقرأ الأحدث، يطبّق [f]، يحفظ، ويعيد النتيجة. */
    fun update(f: (AdminState) -> AdminState): AdminState = synchronized(LOCK) {
        val s = f(read()); write(s); s
    }

    private companion object { val LOCK = Any() }
}
