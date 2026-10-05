package com.uniatt.admin

import com.uniatt.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest

/**
 * مزامنة المسؤول مع الدكاترة عبر الترحيل، تلقائيًا كلما توفّر الإنترنت:
 *  - ينشر لكل دكتور كشفه (اسمه + مواده + شعبه + طلابه) مشفّرًا بكوده، وفقط إذا تغيّر شيء.
 *  - ينزّل ملف الحضور الذي رفعه الدكتور ويدمجه في الحالة.
 *  - يقرأ إشعار الاستلام (ack) ليعرف أن هاتف الدكتور ارتبط.
 * تستدعيه الواجهة (AdminVM) وعامل الخلفية (SyncWorker)؛ القفل أدناه يمنع تشغيلين معًا.
 */
object AdminSync {
    private val mutex = Mutex()

    data class Result(
        val noRelay: Boolean = false, val offline: Boolean = false,
        val pushed: Int = 0, val newRecords: Int = 0, val errors: List<String> = emptyList()
    )

    suspend fun run(store: AdminStore): Result = mutex.withLock { withContext(Dispatchers.IO) { runLocked(store) } }

    private fun runLocked(store: AdminStore): Result {
        val url = Pairing.normalizeUrl(store.load().relayUrl) ?: return Result(noRelay = true)
        val relay = Relay(url)
        // كل دكتور يحتاج صندوق بريد؛ يُولَّد مرة واحدة ويثبت (إلا عند «كود جديد»).
        store.update { s -> s.copy(doctors = s.doctors.map { if (it.mailbox.isBlank()) it.copy(mailbox = Pairing.newMailbox()) else it }) }

        var pushed = 0; var newRecs = 0
        val errors = ArrayList<String>()
        for (id in store.load().doctors.map { it.doctorId }) {
            try {
                val (p, n) = syncDoctor(store, relay, id)
                if (p) pushed++
                newRecs += n
            } catch (e: IOException) {
                return Result(offline = true, pushed = pushed, newRecords = newRecs, errors = errors)  // لا اتصال: توقّف بهدوء
            } catch (e: Exception) {
                errors.add("$id: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        return Result(pushed = pushed, newRecords = newRecs, errors = errors)
    }

    private fun syncDoctor(store: AdminStore, relay: Relay, id: String): Pair<Boolean, Int> {
        val d = store.load().doctors.firstOrNull { it.doctorId == id } ?: return false to 0
        val box = Mailbox(relay, d.mailbox)
        var pushed = false

        // ---- 1) نشر الكشف إن تغيّر ----
        if (d.courseIds.isNotEmpty()) {
            val roster = buildRoster(store.load(), d, 0)
            val hash = sha256Hex(roster.toText().toByteArray(Charsets.UTF_8))
            if (hash != d.rosterHash) {
                var epoch = 0L
                store.update { s -> epoch = s.epoch + 1; s.copy(epoch = epoch) }
                box.publishRoster(roster.copy(epoch = epoch).seal(d.code), epoch)
                // لو تغيّر كود الدكتور/صندوقه أثناء النشر (كود جديد) فلا نسجّل بصمة قديمة
                store.update { s -> s.copy(doctors = s.doctors.map {
                    if (it.doctorId == id && it.mailbox == d.mailbox && it.code == d.code) it.copy(rosterHash = hash, pushedEpoch = epoch) else it
                }) }
                pushed = true
            }
        }

        // ---- 2) إشعار الاستلام ----
        box.fetchAck()?.let { ack ->
            store.update { s -> s.copy(doctors = s.doctors.map {
                if (it.doctorId == id && it.mailbox == d.mailbox && it.ackEpoch != ack) it.copy(ackEpoch = ack) else it
            }) }
        }

        // ---- 3) الحضور ----
        var newRecs = 0
        val blob = box.fetchAttendance()
        if (blob != null) {
            val cur = store.load().doctors.firstOrNull { it.doctorId == id }
            if (cur != null && cur.mailbox == d.mailbox && cur.code == d.code) {
                val h = sha256Hex(blob)
                if (h != cur.attHash) {
                    val f = AttendanceFile.open(blob, d.code)
                    if (f == null || f.doctorId != id) throw IllegalStateException("ملف حضور ${d.name} لم يُفتح (تالف أو بكود مختلف)")
                    store.update { s ->
                        val (merged, n) = mergeAttendance(s, f)
                        newRecs = n
                        merged.copy(doctors = merged.doctors.map {
                            if (it.doctorId == id) it.copy(attHash = h, lastPullAt = System.currentTimeMillis()) else it
                        })
                    }
                }
            }
        }
        return pushed to newRecs
    }

    fun buildRoster(cur: AdminState, d: Doctor, epoch: Long): Roster {
        val mine = d.courseIds.toSet()
        return Roster(
            epoch, d.doctorId, d.name,
            cur.courses.filter { it.id in mine }.map { RCourse(it.id, it.code, it.name, it.section) },
            cur.students.filter { s -> s.courseIds.any { it in mine } }.map { s ->
                RStudent(s.studentId, s.name, s.faculty, s.major, s.level, s.section, s.tag, s.keyHex,
                    s.courseIds.filter { it in mine })
            },
            cur.slots.filter { it.courseId in mine }.sortedWith(compareBy({ it.courseId }, { it.day }, { it.startMin }))
                .map { RSlot(it.courseId, it.day, it.startMin, it.endMin, it.room) }
        )
    }

    /** يدمج ملف حضور دكتور في الحالة (idempotent: إعادة دمج نفس الملف لا تضيف شيئًا). يعيد الحالة وعدد التسجيلات الجديدة. */
    fun mergeAttendance(s: AdminState, f: AttendanceFile): Pair<AdminState, Int> {
        var newRecs = 0
        val sess = LinkedHashMap(s.sessions.associateBy { it.sessionId })
        f.sessions.forEach { sess[it.sessionId] = SessionRow(it.sessionId, it.courseId, f.doctorId, it.startedAt) }
        val recKeys = s.records.map { it.sessionId to it.studentId }.toHashSet()
        val recs = ArrayList(s.records)
        f.records.forEach { if (recKeys.add(it.sessionId to it.studentId)) { recs.add(RecordRow(it.sessionId, it.studentId, it.timestamp)); newRecs++ } }
        val binds = LinkedHashMap(s.bindings.associateBy { it.studentId to it.doctorId })
        f.bindings.forEach { binds[it.studentId to f.doctorId] = BindingRow(it.studentId, f.doctorId, it.pubHex) }
        return s.copy(sessions = sess.values.toList(), records = recs, bindings = binds.values.toList()) to newRecs
    }

    private fun sha256Hex(b: ByteArray) = Hex.enc(MessageDigest.getInstance("SHA-256").digest(b))
}
