package com.uniatt.core

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.Base64

/**
 * رمز الاقتران الذي يعرضه المسؤول كـ QR ويمسحه الدكتور مرة واحدة فقط:
 *   UAQ1|عنوان الترحيل|صندوق البريد|معرّف الدكتور|كود الدكتور
 * الكود هنا يُمسح ولا يُكتب، ويُستخدم لتشفير كل ما يمرّ عبر الترحيل (فالترحيل لا يرى إلا بايتات مشفّرة).
 */
object Pairing {
    const val PREFIX = "UAQ1"

    data class Info(val relayUrl: String, val mailbox: String, val doctorId: String, val code: String)

    fun newMailbox(rnd: SecureRandom = SecureRandom()): String =
        Hex.enc(ByteArray(16).also { rnd.nextBytes(it) })

    /** يوحّد شكل العنوان: بدون مسافات ولا شرطة أخيرة، ويجب أن يكون https. */
    fun normalizeUrl(u: String): String? {
        val t = u.trim().trimEnd('/')
        if (t.contains('|') || t.contains(' ')) return null
        // https فقط؛ ويُسمح بـ http على الجهاز نفسه (127.0.0.1/localhost) لأغراض التطوير والاختبار
        val local = t.startsWith("http://127.0.0.1") || t.startsWith("http://localhost")
        return if ((t.startsWith("https://") && t.length > 12) || local) t else null
    }

    fun format(i: Info): String = listOf(PREFIX, i.relayUrl, i.mailbox, i.doctorId, i.code).joinToString("|")

    fun parse(text: String): Info? {
        val p = text.trim().split('|')
        if (p.size != 5 || p[0] != PREFIX) return null
        val url = normalizeUrl(p[1]) ?: return null
        val mbox = p[2]
        if (mbox.length != 32 || !mbox.all { it in '0'..'9' || it in 'a'..'f' }) return null
        if (p[3].isBlank()) return null
        val code = Codes.normalize(p[4]) ?: return null
        return Info(url, mbox, p[3], code)
    }
}

/**
 * ترحيل بلا خادم تملكه: Firebase Realtime Database عبر REST (لا SDK).
 * المسارات: m/{mailbox}/roster  m/{mailbox}/rv  m/{mailbox}/att  — القيم نصوص Base64 لبايتات مشفّرة.
 * صندوق البريد رقم عشوائي بطول 128 بت، فمعرفته هي صلاحية الوصول (والمحتوى مشفّر أصلًا).
 */
class Relay(baseUrl: String, private val timeoutMs: Int = 20_000) {
    private val base = baseUrl.trim().trimEnd('/')

    class RelayException(msg: String) : Exception(msg)

    private fun open(path: String, method: String, query: String = ""): HttpURLConnection {
        val c = URL("$base/$path.json$query").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        return c
    }

    private fun readBody(c: HttpURLConnection): String {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.use { s ->
            val out = ByteArrayOutputStream(); s.copyTo(out); String(out.toByteArray(), Charsets.UTF_8)
        } ?: ""
        if (code !in 200..299) throw RelayException("HTTP $code")
        return text
    }

    /** يعيد النص المخزَّن أو null إن لم يوجد. */
    fun getString(path: String): String? {
        val c = open(path, "GET")
        try {
            val body = readBody(c).trim()
            if (body == "null" || body.isEmpty()) return null
            return Json.unquote(body)
        } finally { c.disconnect() }
    }

    fun getLong(path: String): Long? = getString(path)?.toLongOrNull()

    fun putString(path: String, value: String) {
        val c = open(path, "PUT")
        try {
            c.doOutput = true
            c.outputStream.use { it.write(Json.quote(value).toByteArray(Charsets.UTF_8)) }
            readBody(c)
        } finally { c.disconnect() }
    }

    fun delete(path: String) {
        val c = open(path, "DELETE")
        try { readBody(c) } finally { c.disconnect() }
    }

    fun putBytes(path: String, bytes: ByteArray) = putString(path, Base64.getEncoder().encodeToString(bytes))
    fun getBytes(path: String): ByteArray? = getString(path)?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
}

/** صندوق بريد دكتور واحد على الترحيل: الكشف (من المسؤول) والحضور (من الدكتور). */
class Mailbox(private val relay: Relay, val id: String) {
    /** الكشف أولًا ثم رقم إصداره، فلا يرى الدكتور رقمًا أحدث من محتوى موجود. */
    fun publishRoster(blob: ByteArray, epoch: Long) {
        relay.putBytes("m/$id/roster", blob)
        relay.putString("m/$id/rv", epoch.toString())
    }
    fun rosterEpoch(): Long? = relay.getLong("m/$id/rv")
    fun fetchRoster(): ByteArray? = relay.getBytes("m/$id/roster")

    fun publishAttendance(blob: ByteArray) = relay.putBytes("m/$id/att", blob)
    fun fetchAttendance(): ByteArray? = relay.getBytes("m/$id/att")

    /** هاتف الدكتور يكتب رقم آخر كشف فتحه، فيعرف المسؤول أن الربط تمّ. */
    fun publishAck(epoch: Long) = relay.putString("m/$id/ack", epoch.toString())
    fun fetchAck(): Long? = relay.getLong("m/$id/ack")

    /** عند إلغاء ربط دكتور (كود جديد). */
    fun wipe() = relay.delete("m/$id")
}

/** ترميز/فكّ نص JSON واحد (بلا اعتماد على org.json كي يعمل core على JVM العادي أيضًا). */
object Json {
    fun quote(v: String): String = buildString {
        append('"')
        for (ch in v) when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch == '\n' -> append("\\n")
            ch == '\r' -> append("\\r")
            ch == '\t' -> append("\\t")
            ch < ' ' -> append("\\u%04x".format(ch.code))
            else -> append(ch)
        }
        append('"')
    }

    /** null إن لم يكن النص قيمة نصية JSON صالحة (مثل رقم أو كائن). */
    fun unquote(t: String): String? {
        if (t.length < 2 || t.first() != '"' || t.last() != '"') return null
        val sb = StringBuilder(); var i = 1
        while (i < t.length - 1) {
            val c = t[i]
            if (c != '\\') { sb.append(c); i++; continue }
            if (i + 1 >= t.length - 1) return null
            when (t[i + 1]) {
                '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                'u' -> { if (i + 5 >= t.length) return null; sb.append(t.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                else -> return null
            }
            i += 2
        }
        return sb.toString()
    }
}
