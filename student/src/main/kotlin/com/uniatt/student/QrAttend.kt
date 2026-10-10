package com.uniatt.student

import android.content.Context
import com.uniatt.core.BleProtocol
import com.uniatt.core.Hmac
import com.uniatt.core.Protocol
import com.uniatt.core.QrProtocol

/** بديل NFC: يجهّز إجابة الطالب على تحدّي QR الذي يعرضه الدكتور (الإثباتات نفسها المستخدمة في NFC). */
object StudentQr {
    private fun attendReply(ctx: Context, req: Protocol.AttendRequest): Protocol.AttendReply {
        val c = StudentStore(ctx).credentials() ?: throw IllegalStateException("التطبيق غير مُفعَّل")
        val pub = DeviceKey.publicRaw()
        val proof = Hmac.sha256(c.key, Protocol.bindMessage(c.tag, pub, req))          // إثبات معرفة الكود
        val sig = DeviceKey.sign(Protocol.signedBytes(c.tag, pub, req))                // إثبات حيازة الجهاز
        return Protocol.AttendReply(c.tag, pub, proof, sig)
    }

    fun reply(ctx: Context, req: Protocol.AttendRequest): String = QrProtocol.replyText(req.nonce, attendReply(ctx, req))

    /** حمولة الإجابة للبلوتوث (قبل التجزئة). */
    fun payload(ctx: Context, req: Protocol.AttendRequest): ByteArray = BleProtocol.replyPayload(req.nonce, attendReply(ctx, req))
}
