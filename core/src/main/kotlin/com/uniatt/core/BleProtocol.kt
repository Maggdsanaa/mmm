package com.uniatt.core

/**
 * الحضور التلقائي عبر Bluetooth LE (بلا إقران، بلا إنترنت)، بنفس الإثباتات المستخدمة في NFC وQR:
 *  - هاتف الدكتور (الجلسة نشطة) يُعلن خدمة GATT ثابتة ويخدم الطلاب.
 *  - هاتف الطالب (تلقائيًا في الخلفية) يلتقط الإعلان، يتصل، ثم:
 *      1) يقرأ CHALLENGE  = sessionId16 | nonce16 | time8   (جديد لكل قراءة، صالح 60 ثانية)
 *      2) يكتب REPLY مجزّأ = nonce16 | tag | pub65 | proof32 | sig | 9000   (قطعة = [idx][total][data])
 *      3) يقرأ RESULT = status1 | بيانات الطالب (عند النجاح)
 *    ثم يقطع الاتصال. التحقق نفسه: HMAC بكود الطالب + توقيع Keystore + عدم التكرار.
 */
object BleProtocol {
    const val SERVICE_UUID = "6e5d1a00-8c3b-4f2e-9a47-7d1f0c2b9e10"
    const val CHALLENGE_UUID = "6e5d1a01-8c3b-4f2e-9a47-7d1f0c2b9e10"
    const val REPLY_UUID = "6e5d1a02-8c3b-4f2e-9a47-7d1f0c2b9e10"
    const val RESULT_UUID = "6e5d1a03-8c3b-4f2e-9a47-7d1f0c2b9e10"

    const val NO_RESULT: Byte = 0xFF.toByte()
    const val MAX_CHUNK = 180

    fun challengeBytes(r: Protocol.AttendRequest): ByteArray = r.sessionId + r.nonce + Protocol.longBytes(r.timeMs)

    fun parseChallenge(d: ByteArray): Protocol.AttendRequest? =
        if (d.size != 40) null
        else Protocol.AttendRequest(d.copyOfRange(0, 16), d.copyOfRange(16, 32), java.nio.ByteBuffer.wrap(d, 32, 8).long)

    fun replyPayload(nonce: ByteArray, reply: Protocol.AttendReply): ByteArray = nonce + Protocol.buildAttendResponse(reply)

    fun parseReplyPayload(p: ByteArray): QrProtocol.ParsedReply? {
        if (p.size < 17) return null
        val reply = Protocol.parseAttendResponse(p.copyOfRange(16, p.size)) ?: return null
        return QrProtocol.ParsedReply(p.copyOfRange(0, 16), reply)
    }

    /** يقسم الحمولة إلى قطع [idx][total][data≤chunk] (لا نفترض MTU كبيرًا). */
    fun chunk(payload: ByteArray, chunkSize: Int = MAX_CHUNK): List<ByteArray> {
        require(chunkSize in 1..MAX_CHUNK)
        val total = (payload.size + chunkSize - 1) / chunkSize
        require(total in 1..255) { "payload too large" }
        return (0 until total).map { i ->
            byteArrayOf(i.toByte(), total.toByte()) + payload.copyOfRange(i * chunkSize, minOf(payload.size, (i + 1) * chunkSize))
        }
    }

    /** يجمّع قطعة؛ يعيد الحمولة كاملة عند وصول آخر قطعة بالترتيب، وإلا null. */
    fun add(asm: ProfileAssembler, c: ByteArray): ByteArray? {
        if (c.size < 3) return null
        val idx = c[0].toInt() and 0xFF; val total = c[1].toInt() and 0xFF
        if (total == 0 || idx >= total) return null
        return asm.add(Protocol.ProfileChunk(idx, total, c.copyOfRange(2, c.size)))
    }

    fun encodeResult(status: Int, profile: ByteArray?): ByteArray =
        byteArrayOf(status.toByte()) + (if (Status.ok(status) && profile != null) profile else ByteArray(0))

    class Result(val status: Int, val profile: ByteArray?)

    fun decodeResult(d: ByteArray): Result? {
        if (d.isEmpty() || d[0] == NO_RESULT) return null
        return Result(d[0].toInt(), if (d.size > 1) d.copyOfRange(1, d.size) else null)
    }
}
