package com.uniatt.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** نتائج التسجيل (تُرسل للطالب أيضًا). */
object Status {
    const val OK = 0
    const val DUPLICATE = 1
    const val NOT_ENROLLED = 2
    const val BAD_SIGNATURE = 3
    const val EXPIRED = 4
    const val UNKNOWN_STUDENT = 5
    const val KEY_MISMATCH = 6
    const val BAD_PROOF = 7

    fun ok(code: Int) = code == OK || code == DUPLICATE

    fun arabic(code: Int): String = when (code) {
        OK -> "✓ تم تسجيل حضورك"
        DUPLICATE -> "تم تسجيل حضورك مسبقًا"
        NOT_ENROLLED -> "✗ أنت غير مسجل في هذه المادة"
        BAD_SIGNATURE -> "✗ فشل التحقق من الجهاز"
        EXPIRED -> "✗ انتهت جلسة الحضور"
        UNKNOWN_STUDENT -> "✗ كودك غير موجود في كشف هذا الدكتور"
        KEY_MISMATCH -> "✗ هذا الكود مرتبط بجهاز آخر — راجع الإدارة"
        BAD_PROOF -> "✗ الكود غير صحيح — أعد إدخاله"
        else -> "✗ خطأ غير معروف"
    }
}

/**
 * بروتوكول APDU (كله بين الهاتفين، بلا إنترنت):
 *  1) SELECT AID                                         => 9000
 *  2) ATTEND  80 10 00 00 28 [sessionId16|nonce16|time8]
 *        => [tag4 | pub65 | proof32 | sig(DER)] 9000
 *        tag   = وسم الطالب (أول 4 خانات من كوده)
 *        pub   = المفتاح العام للجهاز (Keystore) كنقطة خام
 *        proof = HMAC-SHA256(مفتاح مشتق من الكود, "UA-BIND"|tag|session|nonce|time|pub)   ← إثبات معرفة الكود
 *        sig   = ECDSA(مفتاح Keystore, "UA-SIGN"|tag|pub|session|nonce|time)               ← إثبات حيازة الجهاز
 *  3) RESULT  80 20 00 00 01 [status]                    => 9000
 *  4) PROFILE 80 30 00 00 Lc [idx|total|bytes≤200] ...   => 9000   (تُرسل فقط عند النجاح: بيانات الطالب)
 * الـ nonce جديد لكل لمسة والوقت من ساعة الدكتور => لا يمكن إعادة إرسال رد قديم.
 */
object Protocol {
    const val AID_HEX = "F0554E49415454"
    val AID: ByteArray = AID_HEX.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val SELECT_APDU: ByteArray = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, AID.size.toByte()) + AID
    val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
    val SW_ERR = byteArrayOf(0x6F, 0x00)
    val CLA: Byte = 0x80.toByte()
    const val INS_ATTEND: Byte = 0x10
    const val INS_RESULT: Byte = 0x20
    const val INS_PROFILE: Byte = 0x30
    const val CHUNK = 200
    const val MAX_PROFILE = 4096
    private const val PROOF_SIZE = 32
    private const val MAX_SIG = 80
    private val LABEL_BIND = "UA-BIND".toByteArray(Charsets.US_ASCII)
    private val LABEL_SIGN = "UA-SIGN".toByteArray(Charsets.US_ASCII)

    class AttendRequest(val sessionId: ByteArray, val nonce: ByteArray, val timeMs: Long)
    class AttendReply(val tag: String, val pub: ByteArray, val proof: ByteArray, val sig: ByteArray)
    class ProfileChunk(val index: Int, val total: Int, val data: ByteArray)

    fun longBytes(v: Long): ByteArray = ByteBuffer.allocate(8).putLong(v).array()
    fun uuidToBytes(u: UUID): ByteArray =
        ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()
    fun bytesToUuid(b: ByteArray): UUID = ByteBuffer.wrap(b).let { UUID(it.long, it.long) }

    // ---- ATTEND ----
    fun buildAttendCommand(r: AttendRequest): ByteArray {
        val data = r.sessionId + r.nonce + longBytes(r.timeMs)
        return byteArrayOf(CLA, INS_ATTEND, 0, 0, data.size.toByte()) + data
    }

    fun parseAttendCommand(apdu: ByteArray): AttendRequest? {
        if (apdu.size != 45 || apdu[0] != CLA || apdu[1] != INS_ATTEND) return null
        val d = apdu.copyOfRange(5, 45)
        return AttendRequest(d.copyOfRange(0, 16), d.copyOfRange(16, 32), ByteBuffer.wrap(d, 32, 8).long)
    }

    private fun core(tag: String, pub: ByteArray, r: AttendRequest): ByteArray =
        tag.toByteArray(Charsets.US_ASCII) + pub + r.sessionId + r.nonce + longBytes(r.timeMs)

    fun bindMessage(tag: String, pub: ByteArray, r: AttendRequest): ByteArray = LABEL_BIND + core(tag, pub, r)
    fun signedBytes(tag: String, pub: ByteArray, r: AttendRequest): ByteArray = LABEL_SIGN + core(tag, pub, r)

    fun buildAttendResponse(reply: AttendReply): ByteArray =
        reply.tag.toByteArray(Charsets.US_ASCII) + reply.pub + reply.proof + reply.sig + SW_OK

    fun parseAttendResponse(resp: ByteArray): AttendReply? {
        val fixed = Codes.TAG_LEN + EcKey.RAW_SIZE + PROOF_SIZE
        if (resp.size < fixed + 8 + 2 || !isOk(resp)) return null
        val body = resp.copyOfRange(0, resp.size - 2)
        val sig = body.copyOfRange(fixed, body.size)
        if (sig.size > MAX_SIG) return null
        return AttendReply(
            String(body, 0, Codes.TAG_LEN, Charsets.US_ASCII),
            body.copyOfRange(Codes.TAG_LEN, Codes.TAG_LEN + EcKey.RAW_SIZE),
            body.copyOfRange(Codes.TAG_LEN + EcKey.RAW_SIZE, fixed),
            sig
        )
    }

    // ---- RESULT ----
    fun buildResultCommand(status: Int): ByteArray = byteArrayOf(CLA, INS_RESULT, 0, 0, 1, status.toByte())
    fun parseResultCommand(apdu: ByteArray): Int? =
        if (apdu.size == 6 && apdu[0] == CLA && apdu[1] == INS_RESULT && apdu[4] == 1.toByte()) apdu[5].toInt() else null

    // ---- PROFILE (مقسَّمة لأن APDU القصير لا يتجاوز 255 بايت) ----
    fun buildProfileCommands(payload: ByteArray): List<ByteArray> {
        val total = (payload.size + CHUNK - 1) / CHUNK
        require(total in 1..255) { "bad profile size" }
        return (0 until total).map { i ->
            val d = payload.copyOfRange(i * CHUNK, minOf(payload.size, (i + 1) * CHUNK))
            byteArrayOf(CLA, INS_PROFILE, 0, 0, (d.size + 2).toByte(), i.toByte(), total.toByte()) + d
        }
    }

    fun parseProfileCommand(apdu: ByteArray): ProfileChunk? {
        if (apdu.size < 8 || apdu[0] != CLA || apdu[1] != INS_PROFILE) return null
        val lc = apdu[4].toInt() and 0xFF
        if (lc < 3 || apdu.size != 5 + lc) return null
        val idx = apdu[5].toInt() and 0xFF
        val total = apdu[6].toInt() and 0xFF
        if (total == 0 || idx >= total) return null
        return ProfileChunk(idx, total, apdu.copyOfRange(7, apdu.size))
    }

    fun isOk(resp: ByteArray) = resp.size >= 2 && resp[resp.size - 2] == 0x90.toByte() && resp[resp.size - 1] == 0.toByte()
}

/** تجميع أجزاء PROFILE على هاتف الطالب. */
class ProfileAssembler {
    private var next = 0
    private var expected = 0
    private val buf = ByteArrayOutputStream()

    @Synchronized fun add(c: Protocol.ProfileChunk): ByteArray? {
        if (c.index == 0) { buf.reset(); next = 0; expected = c.total }
        if (c.index != next || c.total != expected) { reset(); return null }
        buf.write(c.data, 0, c.data.size)
        next++
        if (buf.size() > Protocol.MAX_PROFILE) { reset(); return null }
        if (next == expected) { val out = buf.toByteArray(); reset(); return out }
        return null
    }

    @Synchronized fun reset() { buf.reset(); next = 0; expected = 0 }
}

fun fmtTime(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
fun fmtDate(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))
