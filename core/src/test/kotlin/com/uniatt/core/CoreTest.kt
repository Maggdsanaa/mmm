package com.uniatt.core

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

class CoreTest {
    private fun req() = Protocol.AttendRequest(Protocol.uuidToBytes(UUID.randomUUID()), ByteArray(16) { it.toByte() }, 1234567890L)
    private fun ec() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private fun sign(k: java.security.PrivateKey, d: ByteArray) =
        Signature.getInstance("SHA256withECDSA").run { initSign(k); update(d); sign() }

    // ---------- الأكواد ----------
    @Test fun codeRoundTripAndNormalization() {
        val c = Codes.random()
        assertEquals(c, Codes.normalize(Codes.format(c).lowercase()))
        assertEquals(c, Codes.normalize(c.map { ch -> if (ch.isDigit()) ('٠' + (ch - '0')) else ch }.joinToString("")))
        assertNull(Codes.normalize("ABC"))
        assertNull(Codes.normalize("UUUU-UUUU-UUUU"))
    }

    @Test fun anySingleCharTypoIsDetected() {
        repeat(50) {
            val c = Codes.random()
            for (i in c.indices) for (a in Codes.ALPHABET) {
                if (a == c[i]) continue
                assertNull(Codes.normalize(c.substring(0, i) + a + c.substring(i + 1)))
            }
        }
    }

    @Test fun newUniqueAvoidsExistingTags() {
        val used = HashSet<String>()
        repeat(300) { val c = Codes.newUnique(used); assertTrue(used.add(Codes.tag(c))) }
    }

    // ---------- الصندوق المشفّر ----------
    @Test fun boxRoundTripWrongCodeAndTamper() {
        val code = Codes.random(); val plain = "كشف تجريبي".toByteArray()
        val blob = Box.seal(Box.MAGIC_ROSTER, "D1", plain, code)
        assertEquals("D1", Box.hint(Box.MAGIC_ROSTER, blob))
        assertArrayEquals(plain, Box.open(Box.MAGIC_ROSTER, blob, code))
        assertNull(Box.open(Box.MAGIC_ROSTER, blob, Codes.random()))
        assertNull(Box.open(Box.MAGIC_ATTEND, blob, code))
        for (i in listOf(0, 4, 5, 10, blob.size - 1)) {
            val b = blob.clone(); b[i] = (b[i].toInt() xor 1).toByte()
            assertNull(Box.open(Box.MAGIC_ROSTER, b, code))
        }
        assertNull(Box.open(Box.MAGIC_ROSTER, blob.copyOf(10), code))
    }

    // ---------- المفاتيح والتواقيع ----------
    @Test fun rawPointRoundTripAndSignature() {
        val kp = ec(); val enc = kp.public.encoded
        val raw = EcKey.rawFromX509(enc)!!
        assertEquals(65, raw.size)
        assertArrayEquals(enc, EcKey.toX509(raw))
        val data = "hello".toByteArray(); val sig = sign(kp.private, data)
        assertTrue(SigVerifier.verify(raw, data, sig))
        assertFalse(SigVerifier.verify(raw, "hellp".toByteArray(), sig))
        val bad = raw.clone(); bad[10] = (bad[10].toInt() xor 1).toByte()
        assertFalse(SigVerifier.verify(bad, data, sig))
    }

    // ---------- البروتوكول ----------
    @Test fun attendCommandAndResponseRoundTrip() {
        val r = req()
        val p = Protocol.parseAttendCommand(Protocol.buildAttendCommand(r))!!
        assertArrayEquals(r.sessionId, p.sessionId); assertArrayEquals(r.nonce, p.nonce); assertEquals(r.timeMs, p.timeMs)

        val reply = Protocol.AttendReply("ABCD", ByteArray(65) { 4 }, ByteArray(32) { 1 }, ByteArray(70) { 7 })
        val resp = Protocol.buildAttendResponse(reply)
        assertTrue(resp.size <= 255)
        val back = Protocol.parseAttendResponse(resp)!!
        assertEquals("ABCD", back.tag); assertEquals(70, back.sig.size)
        assertNull(Protocol.parseAttendResponse(Protocol.SW_ERR))
    }

    @Test fun proofAndSignatureBindToKeyAndNonce() {
        val code = Codes.random(); val tag = Codes.tag(code); val key = Kdf.studentKey(code)
        val kp = ec(); val pub = EcKey.rawFromX509(kp.public.encoded)!!
        val r = req(); val other = Protocol.AttendRequest(r.sessionId, ByteArray(16) { 9 }, r.timeMs)
        val proof = Hmac.sha256(key, Protocol.bindMessage(tag, pub, r))
        assertTrue(Hmac.equal(proof, Hmac.sha256(key, Protocol.bindMessage(tag, pub, r))))
        assertFalse(Hmac.equal(proof, Hmac.sha256(key, Protocol.bindMessage(tag, pub, other))))      // nonce مختلف
        assertFalse(Hmac.equal(proof, Hmac.sha256(Kdf.studentKey(Codes.random()), Protocol.bindMessage(tag, pub, r)))) // كود مختلف
        val sig = sign(kp.private, Protocol.signedBytes(tag, pub, r))
        assertTrue(SigVerifier.verify(pub, Protocol.signedBytes(tag, pub, r), sig))
        assertFalse(SigVerifier.verify(pub, Protocol.signedBytes(tag, pub, other), sig))
    }

    @Test fun profileChunksReassemble() {
        val p = StudentProfile("1001", "محمد أحمد عبدالرحمن الشهري", "كلية الحاسبات", "علوم الحاسب", "3", "A",
            listOf("CS101 برمجة (A)", "MATH1 رياضيات (B)", "PHY2 فيزياء (C)"))
        val payload = p.encode()
        val cmds = Protocol.buildProfileCommands(payload)
        assertTrue(cmds.all { it.size <= 255 })
        val asm = ProfileAssembler(); var out: ByteArray? = null
        cmds.forEach { out = asm.add(Protocol.parseProfileCommand(it)!!) }
        assertEquals(p, StudentProfile.decode(out!!))
        // جزء خارج الترتيب يُرفض
        val big = Protocol.buildProfileCommands(ByteArray(450) { 1 })
        assertNull(ProfileAssembler().add(Protocol.parseProfileCommand(big[1])!!))
    }

    @Test fun resultCommandRoundTrip() {
        assertEquals(Status.KEY_MISMATCH, Protocol.parseResultCommand(Protocol.buildResultCommand(Status.KEY_MISMATCH)))
    }

    // ---------- ملفات الكشف والحضور ----------
    @Test fun rosterSealOpen() {
        val code = Codes.random()
        val key = Hex.enc(Kdf.studentKey(Codes.random()))
        val r = Roster(5, "D1", "د. خالد", listOf(RCourse(1, "CS101", "برمجة", "A")),
            listOf(RStudent("1001", "محمد\tأحمد", "كلية", "تخصص", "3", "A", "ABCD", key, listOf(1L))))
        val back = Roster.open(r.seal(code), code)!!
        assertEquals(5L, back.epoch); assertEquals("D1", back.doctorId)
        assertEquals("محمد أحمد", back.students[0].name)        // TAB داخل الحقل يُستبدل بمسافة
        assertEquals(listOf(1L), back.students[0].courseIds)
        assertNull(Roster.open(r.seal(code), Codes.random()))
        assertNull(Roster.parse("garbage"))
    }

    @Test fun attendanceFileSealOpen() {
        val code = Codes.random()
        val f = AttendanceFile("D1", 5, listOf(ASession("s1", 1, 1000, 15)), listOf(ARecord("s1", "1001", 1500)),
            listOf(ABinding("1001", "ABCD", "04" + "ab".repeat(64))))
        val back = AttendanceFile.open(f.seal(code), code)!!
        assertEquals(f, back)
        assertNull(AttendanceFile.open(f.seal(code), Codes.random()))
    }
}
