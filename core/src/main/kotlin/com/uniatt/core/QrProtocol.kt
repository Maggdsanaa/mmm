package com.uniatt.core

import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * بديل NFC: تحدٍّ وإجابة عبر رمزَي QR (بلا إنترنت وبالإثباتات نفسها: HMAC بكود الطالب + توقيع Keystore):
 *  1) الدكتور يعرض رمز تحدٍّ يتغيّر كل ~10 ثوانٍ:   UAC1|base64url(sessionId16 | nonce16 | time8)
 *  2) الطالب يمسحه بتطبيقه، فيعرض رمز إجابة:        UAR1|base64url(nonce16)|base64url(tag|pub|proof|sig|9000)
 *  3) الدكتور يمسح إجابة الطالب ويتحقق منها كما في NFC (يبحث عن التحدّي بالـnonce، وصلاحيته 60 ثانية).
 * بما أن الإجابة مربوطة بالجلسة والـnonce والوقت لا يُقبل ردّ قديم، ولا تُقبل إجابة بلا تحدٍّ صدر من هاتف الدكتور.
 */
object QrProtocol {
    const val CHALLENGE = "UAC1"
    const val REPLY = "UAR1"
    const val TTL_MS = 60_000L

    private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun unb64(s: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(s) }.getOrNull()

    fun challengeText(r: Protocol.AttendRequest): String =
        "$CHALLENGE|" + b64(r.sessionId + r.nonce + Protocol.longBytes(r.timeMs))

    fun parseChallenge(text: String): Protocol.AttendRequest? {
        val p = text.trim().split('|')
        if (p.size != 2 || p[0] != CHALLENGE) return null
        val d = unb64(p[1]) ?: return null
        if (d.size != 40) return null
        return Protocol.AttendRequest(d.copyOfRange(0, 16), d.copyOfRange(16, 32), java.nio.ByteBuffer.wrap(d, 32, 8).long)
    }

    fun replyText(nonce: ByteArray, reply: Protocol.AttendReply): String =
        "$REPLY|" + b64(nonce) + "|" + b64(Protocol.buildAttendResponse(reply))

    class ParsedReply(val nonce: ByteArray, val reply: Protocol.AttendReply)

    fun parseReply(text: String): ParsedReply? {
        val p = text.trim().split('|')
        if (p.size != 3 || p[0] != REPLY) return null
        val nonce = unb64(p[1])?.takeIf { it.size == 16 } ?: return null
        val body = unb64(p[2]) ?: return null
        val reply = Protocol.parseAttendResponse(body) ?: return null
        return ParsedReply(nonce, reply)
    }
}

/** تحدّيات صدرت من هاتف الدكتور (للمطابقة عند استلام الإجابات). */
class QrChallenges(
    private val ttlMs: Long = QrProtocol.TTL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val rnd: SecureRandom = SecureRandom()
) {
    private val issued = LinkedHashMap<String, Pair<Protocol.AttendRequest, Long>>()

    @Synchronized fun issue(sessionId: UUID): Protocol.AttendRequest {
        val now = clock()
        purge(now)
        val req = Protocol.AttendRequest(Protocol.uuidToBytes(sessionId), ByteArray(16).also { rnd.nextBytes(it) }, now)
        issued[Hex.enc(req.nonce)] = req to now
        return req
    }

    /** التحدّي المطابق للـnonce إن كان صادرًا عنا وغير منتهٍ، وإلا null. */
    @Synchronized fun find(nonce: ByteArray): Protocol.AttendRequest? {
        val now = clock()
        purge(now)
        return issued[Hex.enc(nonce)]?.first
    }

    @Synchronized fun clear() = issued.clear()

    private fun purge(now: Long) {
        val it = issued.entries.iterator()
        while (it.hasNext()) if (now - it.next().value.second > ttlMs) it.remove()
    }
}
