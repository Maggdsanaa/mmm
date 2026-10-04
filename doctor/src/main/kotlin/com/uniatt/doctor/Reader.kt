package com.uniatt.doctor

import android.nfc.NfcAdapter
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

    private val rnd = SecureRandom()
    @Volatile private var cooldownUntil = 0L

    override fun onTagDiscovered(tag: Tag) {
        val s = session() ?: return
        if (System.currentTimeMillis() < cooldownUntil) return
        val iso = IsoDep.get(tag) ?: return
        try {
            iso.connect(); iso.timeout = 3000
            if (!Protocol.isOk(iso.transceive(Protocol.SELECT_APDU))) {
                onEvent(ScanEvent(false, "الجهاز المقرَّب ليس تطبيق الطالب")); return
            }
            val req = Protocol.AttendRequest(
                Protocol.uuidToBytes(s.sessionId),
                ByteArray(16).also { rnd.nextBytes(it) },
                System.currentTimeMillis()
            )
            val reply = Protocol.parseAttendResponse(iso.transceive(Protocol.buildAttendCommand(req)))
            if (reply == null) { onEvent(ScanEvent(false, "الطالب لم يُدخل كوده في تطبيقه بعد")); return }

            val out = runBlocking { evaluate(s, reply, req) }
            try { iso.transceive(Protocol.buildResultCommand(out.status)) } catch (_: Exception) {}
            if (Status.ok(out.status) && out.profile != null) {
                try { Protocol.buildProfileCommands(out.profile.encode()).forEach { iso.transceive(it) } } catch (_: Exception) {}
            }
            val name = out.student?.name ?: ""
            onEvent(ScanEvent(out.status == Status.OK, when (out.status) {
                Status.OK -> "✓ تم تسجيل حضور $name"
                Status.DUPLICATE -> "⚠ $name — سُجّل حضوره مسبقًا"
                Status.NOT_ENROLLED -> "✗ الطالب غير مسجل في هذه المادة ($name)"
                Status.UNKNOWN_STUDENT -> "✗ كود غير موجود في الكشف (${reply.tag})"
                Status.BAD_PROOF -> "✗ كود الطالب غير صحيح"
                Status.KEY_MISMATCH -> "✗ $name: الكود مرتبط بجهاز آخر — يحتاج كودًا جديدًا من الإدارة"
                Status.BAD_SIGNATURE -> "✗ فشل التحقق من جهاز الطالب"
                else -> "✗ انتهت مدة الجلسة"
            }))
            cooldownUntil = System.currentTimeMillis() + 2000
        } catch (e: Exception) {
            onEvent(ScanEvent(false, "انقطع الاتصال — أعد التقريب وأبقِ الهاتفين ثابتين"))
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
        val courses = dao.coursesOfStudent(st.studentId).map { "${it.code} ${it.name} (${it.section})" }
        val profile = StudentProfile(st.studentId, st.name, st.faculty, st.major, st.level, st.section, courses)
        return Outcome(status, st, profile)
    }
}
