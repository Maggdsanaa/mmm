package com.uniatt.student

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import com.uniatt.core.Hmac
import com.uniatt.core.ProfileAssembler
import com.uniatt.core.Protocol
import com.uniatt.core.Status
import com.uniatt.core.StudentProfile

class AttendanceApduService : HostApduService() {

    override fun processCommandApdu(apdu: ByteArray, extras: Bundle?): ByteArray {
        try {
            if (apdu.contentEquals(Protocol.SELECT_APDU)) {
                assembler.reset(); lastStatus = -1
                return Protocol.SW_OK
            }

            Protocol.parseAttendCommand(apdu)?.let { req ->
                // بلا كود محفوظ => 6F00 فيعرف الدكتور أن الطالب لم يفعّل تطبيقه
                val c = StudentStore(this).credentials() ?: return Protocol.SW_ERR
                val pub = DeviceKey.publicRaw()
                pendingSession = Protocol.bytesToUuid(req.sessionId).toString()
                val proof = Hmac.sha256(c.key, Protocol.bindMessage(c.tag, pub, req))
                val sig = DeviceKey.sign(Protocol.signedBytes(c.tag, pub, req))
                return Protocol.buildAttendResponse(Protocol.AttendReply(c.tag, pub, proof, sig))
            }

            Protocol.parseResultCommand(apdu)?.let { status ->
                lastStatus = status
                StudentStore(this).addHistory(HistoryItem(System.currentTimeMillis(), pendingSession ?: "-", status))
                pendingSession = null
                return Protocol.SW_OK
            }

            Protocol.parseProfileCommand(apdu)?.let { chunk ->
                // لا تُقبل بيانات إلا بعد نتيجة ناجحة في نفس اللمسة
                if (!Status.ok(lastStatus)) return Protocol.SW_ERR
                assembler.add(chunk)?.let { bytes ->
                    StudentProfile.decode(bytes)?.let { StudentStore(this).mergeProfile(it) }
                }
                return Protocol.SW_OK
            }
        } catch (e: Exception) { /* fallthrough */ }
        return Protocol.SW_ERR
    }

    override fun onDeactivated(reason: Int) {}

    companion object {
        @Volatile var pendingSession: String? = null
        @Volatile var lastStatus: Int = -1
        private val assembler = ProfileAssembler()
    }
}
