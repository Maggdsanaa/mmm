package com.uniatt.doctor

import android.nfc.NfcAdapter
import android.nfc.TagLostException
import android.nfc.Tag
import android.nfc.tech.IsoDep
import com.uniatt.core.*
import kotlinx.coroutines.runBlocking
import java.security.SecureRandom
import java.util.UUID

data class ActiveSession(
    val sessionId: UUID, val course: CourseSection, val doctorId: String,
    val startedAt: Long, val durationMin: Int
) { val endsAt get() = startedAt + durationMin * 60_000L }

data class ScanEvent(val ok: Boolean, val text: String, val time: Long = System.currentTimeMillis())

class Outcome(val status: Int, val student: StudentEntity?, val profile: StudentProfile? = null)

/** قارئ NFC: يتحقق من إثبات الكود والتوقيع والتسجيل والتكرار، يربط جهاز الطالب أول مرة، ثم يرسل النتيجة والبيانات. */
class AttendanceReader(
    private val dao: AttDao,
    private val onEvent: (ScanEvent) -> Unit,
    private val session: () -> ActiveSession?
) : NfcAdapter.ReaderCallback {
    /** يُضبط من MainActivity: نص مواعيد المادة (من جدول المسؤول) ليصل للطالب مع بياناته. */
    @Volatile var scheduleOf: (Long) -> String = { "" }

    /** وضع «اختبار NFC»: يفحص الاتصال بهاتف الطالب بلا جلسة ولا تسجيل حضور. */
    @Volatile var testMode: () -> Boolean = { false }
    /** يُستدعى عند اكتشاف أي جهاز (حتى لو فشلت القراءة)، لعرض عدّاد اللمسات. */
    @Volatile var onContact: () -> Unit = {}

    private val rnd = SecureRandom()
    private fun hex(b: ByteArray?) = if (b == null) "—" else b.joinToString("") { "%02X".format(it) }
    @Volatile private var cooldownUntil = 0L

    override fun onTagDiscovered(tag: Tag) {
        val s = session()
        val test = s == null && testMode()
        if (s == null && !test) return
        if (System.currentTimeMillis() < cooldownUntil) return
        onContact()
        val iso = IsoDep.get(tag)
        if (iso == null) {
            val techs = tag.techList.joinToString("، ") { it.substringAfterLast('.') }
            onEvent(ScanEvent(false, "اكتُشف جهاز لكنه لا يردّ كبطاقة ISO-DEP ($techs). على هاتف الطالب: افتح التطبيق، أبقِ الشاشة مضاءة ومفتوحة القفل، ثم أعد التقريب."))
            cooldownUntil = System.currentTimeMillis() + 1500
            return
        }
        try {
            iso.connect(); iso.timeout = 5000
            val sel = iso.transceive(Protocol.SELECT_APDU)
            if (!Protocol.isOk(sel)) {
                onEvent(ScanEvent(false, "اكتُشف جهاز لكنه ليس تطبيق الطالب (رد ${hex(sel)}). افتح تطبيق الطالب على هاتفه وأبقِ الشاشة مضاءة ثم أعد التقريب."))
                cooldownUntil = System.currentTimeMillis() + 1500
                return
            }
            val sessionUuid = s?.sessionId ?: UUID.randomUUID()
            val req = Protocol.AttendRequest(
                Protocol.uuidToBytes(sessionUuid),
                ByteArray(16).also { rnd.nextBytes(it) },
                System.currentTimeMillis()
            )
            val reply = Protocol.parseAttendResponse(iso.transceive(Protocol.buildAttendCommand(req)))
            if (reply == null) { onEvent(ScanEvent(false, "الاتصال يعمل، لكن الطالب لم يُدخل كوده في تطبيقه بعد")); cooldownUntil = System.currentTimeMillis() + 1500; return }

            if (test) {
                val st = runBlocking { dao.studentByTag(reply.tag) }
                onEvent(ScanEvent(st != null,
                    if (st != null) "✓ اختبار NFC ناجح — ردّ هاتف ${st.name} (اختبار فقط: لم يُسجَّل حضور)"
                    else "✓ الاتصال يعمل، لكن كود الطالب (${reply.tag}) غير موجود في كشفك — اضغط «مزامنة الآن» أو تأكد أنه في موادك عند المسؤول"))
                cooldownUntil = System.currentTimeMillis() + 2000
                return
            }
            val sess = s ?: return

            val out = runBlocking { evaluate(sess, reply, req) }
            try { iso.transceive(Protocol.buildResultCommand(out.status)) } catch (_: Exception) {}
            if (Status.ok(out.status) && out.profile != null) {
                try { Protocol.buildProfileCommands(out.profile.encode()).forEach { iso.transceive(it) } } catch (_: Exception) {}
            }
            val name = out.student?.name ?: ""
            onEvent(ScanEvent(out.status == Status.OK, when (out.status) {
                Status.OK -> "✓ تم تسجيل حضور $name"
                Status.DUPLICATE -> "⚠ $name — سُجّل حضوره مسبقًا"
                Status.NOT_ENROLLED -> "✗ الطالب غير مسجل في هذه المادة ($name)"
                Status.UNKNOWN_STUDENT -> "✗ كود غير موجود في الكشف (${reply.tag}) — اضغط «مزامنة الآن» في تبويب المواد"
                Status.BAD_PROOF -> "✗ كود الطالب غير صحيح"
                Status.KEY_MISMATCH -> "✗ $name: الكود مرتبط بجهاز آخر — يحتاج كودًا جديدًا من الإدارة"
                Status.BAD_SIGNATURE -> "✗ فشل التحقق من جهاز الطالب"
                else -> "✗ انتهت مدة الجلسة"
            }))
            cooldownUntil = System.currentTimeMillis() + 2000
        } catch (e: Exception) {
            onEvent(ScanEvent(false,
                if (e is TagLostException) "حُرّك الهاتف قبل اكتمال القراءة — أبقِ الهاتفين ملتصقين ظهرًا لظهر لثانيتين"
                else "خطأ في القراءة (${e.javaClass.simpleName}) — أعد التقريب وأبقِ الهاتفين ثابتين"))
        } finally { try { iso.close() } catch (_: Exception) {} }
    }

    private suspend fun evaluate(s: ActiveSession, r: Protocol.AttendReply, req: Protocol.AttendRequest): Outcome {
        val now = System.currentTimeMillis()
        if (now > s.endsAt) return Outcome(Status.EXPIRED, null)
        val st = dao.studentByTag(r.tag) ?: return Outcome(Status.UNKNOWN_STUDENT, null)

        // 1) إثبات معرفة الكود (يمنع انتحال طالب بلا كوده)
        val expected = Hmac.sha256(Hex.dec(st.keyHex), Protocol.bindMessage(r.tag, r.pub, req))
        if (!Hmac.equal(expected, r.proof)) return Outcome(Status.BAD_PROOF, st)
        // 2) إثبات حيازة مفتاح الجهاز (Keystore)
        if (!SigVerifier.verify(r.pub, Protocol.signedBytes(r.tag, r.pub, req), r.sig)) return Outcome(Status.BAD_SIGNATURE, st)
        // 3) مسجل في مادة هذه الجلسة؟ (قبل الربط، فلا يُربط جهاز طالب من مادة أخرى)
        if (dao.enrolled(st.studentId, s.course.id) == 0) return Outcome(Status.NOT_ENROLLED, st)
        // 4) ربط الجهاز عند أول مرة، وبعدها لا يُقبل جهاز مختلف
        val pubHex = Hex.enc(r.pub)
        if (st.publicKeyHex == null) dao.bindKey(st.studentId, pubHex)
        else if (st.publicKeyHex != pubHex) return Outcome(Status.KEY_MISMATCH, st)

        val rec = AttendanceRecord(
            sessionId = s.sessionId.toString(), studentId = st.studentId, studentName = st.name,
            courseSectionId = s.course.id, courseName = "${s.course.code} ${s.course.name} (${s.course.section})",
            date = fmtDate(req.timeMs), timestamp = req.timeMs, status = "PRESENT"
        )
        val status = if (dao.addAttendance(rec) == -1L) Status.DUPLICATE else Status.OK
        val courses = dao.coursesOfStudent(st.studentId).map {
            val sched = try { scheduleOf(it.id) } catch (_: Exception) { "" }
            "${it.code} ${it.name} (${it.section})" + if (sched.isNotBlank()) " — $sched" else ""
        }
        val profile = StudentProfile(st.studentId, st.name, st.faculty, st.major, st.level, st.section, courses)
        return Outcome(status, st, profile)
    }
}
