package com.uniatt.doctor

import android.content.Context
import com.uniatt.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest

/**
 * مزامنة الدكتور مع المسؤول عبر الترحيل، تلقائيًا كلما توفّر الإنترنت (بعد مسح QR مرة واحدة):
 *  - ينزّل الكشف الجديد (اسمه، مواده، شعبه، طلابه) إن وُجد إصدار أحدث، ويرسل إشعار استلام.
 *  - يرفع الحضور (مشفّرًا) إن تغيّر منذ آخر رفع.
 * تستدعيه الواجهة (DoctorVM) وعامل الخلفية (SyncWorker)؛ القفل يمنع تشغيلين معًا.
 */
object DoctorSync {
    private val mutex = Mutex()

    data class Result(
        val notPaired: Boolean = false, val offline: Boolean = false,
        val waitingAdmin: Boolean = false, val rosterUpdated: Boolean = false, val resetCount: Int = 0,
        val uploaded: Boolean = false, val error: String? = null
    )

    suspend fun run(ctx: Context): Result = mutex.withLock { withContext(Dispatchers.IO) { runLocked(ctx.applicationContext) } }

    private suspend fun runLocked(ctx: Context, retried: Boolean = false): Result {
        val prefs = ctx.getSharedPreferences("doctor", 0)
        val mbox = prefs.getString("mbox", null)
        val relayUrl = prefs.getString("relay", null)
        val code = prefs.getString("code", null)
        val did = prefs.getString("id", null)
        if (mbox == null || relayUrl == null || code == null || did == null) return Result(notPaired = true)

        val dao = AppDb.get(ctx).dao()
        val box = Mailbox(Relay(relayUrl), mbox)
        var updated = false; var resets = 0; var uploaded = false; var waiting = false
        try {
            // ---- 1) الكشف ----
            val rv = box.rosterEpoch()
            val epoch = prefs.getLong("epoch", -1L)
            if (rv == null && epoch < 0) waiting = true
            if (rv != null && rv > epoch) {
                val blob = box.fetchRoster()
                if (blob != null) {
                    val roster = Roster.open(blob, code)
                        ?: return Result(error = "تعذّر فتح الكشف: الكود تغيّر أو الملف تالف. اطلب QR جديدًا من المسؤول.")
                    if (roster.doctorId != did) return Result(error = "الكشف يخص دكتورًا آخر (${roster.doctorName})")
                    if (roster.epoch > epoch) {
                        resets = applyRoster(dao, roster)
                        prefs.edit().putString("name", roster.doctorName).putLong("epoch", roster.epoch)
                            .putString("slots", Schedule.encode(roster.slots)).apply()
                        updated = true
                    }
                }
            }

            // ---- 2) إشعار الاستلام (ليعرف المسؤول أن الربط تمّ) ----
            val cur = prefs.getLong("epoch", -1L)
            if (cur >= 0 && prefs.getLong("ackEpoch", -1L) != cur) {
                box.publishAck(cur)
                prefs.edit().putLong("ackEpoch", cur).apply()
            }

            // ---- 3) رفع الحضور إن تغيّر ----
            if (cur >= 0) {
                val sess = dao.allSessions().map { ASession(it.sessionId, it.courseSectionId, it.startedAt, it.durationMin) }
                if (sess.isNotEmpty()) {
                    val recs = dao.allAttendance().map { ARecord(it.sessionId, it.studentId, it.timestamp) }
                    val binds = dao.boundStudents().map { ABinding(it.studentId, it.tag, it.publicKeyHex!!) }
                    val file = AttendanceFile(did, cur, sess, recs, binds)
                    val h = Hex.enc(MessageDigest.getInstance("SHA-256").digest(file.toText().toByteArray(Charsets.UTF_8)))
                    if (h != prefs.getString("attHash", null)) {
                        box.publishAttendance(file.seal(code))
                        prefs.edit().putString("attHash", h).apply()
                        uploaded = true
                    }
                }
            }
            return Result(waitingAdmin = waiting, rosterUpdated = updated, resetCount = resets, uploaded = uploaded)
        } catch (e: IOException) {
            return Result(offline = true, rosterUpdated = updated, resetCount = resets, uploaded = uploaded)
        } catch (e: Relay.RelayException) {
            // 404 = عنوان القاعدة في الـQR قديم/خاطئ (مثلًا المنطقة): نبحث عن الصحيح ونعيد المحاولة مرة واحدة
            if (e.code == 404 && !retried) {
                val found = try { RelayLocator.find(relayUrl) } catch (_: IOException) { return Result(offline = true) }
                if (found != null && found != relayUrl) {
                    prefs.edit().putString("relay", found).apply()
                    return runLocked(ctx, retried = true)
                }
            }
            return Result(error = e.friendly(), rosterUpdated = updated, resetCount = resets, uploaded = uploaded)
        } catch (e: Exception) {
            return Result(error = e.message ?: e.javaClass.simpleName, rosterUpdated = updated, resetCount = resets, uploaded = uploaded)
        }
    }

    /** يحفظ الكشف في قاعدة البيانات المحلية؛ يعيد عدد الطلاب الذين أُلغي ربط أجهزتهم بسبب أكواد جديدة. */
    suspend fun applyRoster(dao: AttDao, roster: Roster): Int {
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
        return resetCount
    }
}
