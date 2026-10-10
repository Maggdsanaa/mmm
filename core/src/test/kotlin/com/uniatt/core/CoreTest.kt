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

    // ---- الاقتران عبر QR والترحيل ----
    @Test fun pairingRoundTrip() {
        val code = Codes.random(); val mbox = Pairing.newMailbox()
        val info = Pairing.Info("https://demo-default-rtdb.firebaseio.com", mbox, "D1", code)
        assertEquals(info, Pairing.parse(Pairing.format(info)))
        assertEquals(code, Pairing.parse(Pairing.format(info).replace(code, Codes.format(code).lowercase()))?.code)
    }

    @Test fun pairingRejectsBadInput() {
        val code = Codes.random(); val mbox = Pairing.newMailbox()
        val txt = Pairing.format(Pairing.Info("https://x.firebaseio.com", mbox, "D1", code))
        assertNull(Pairing.parse("UAQ1|a"))
        assertNull(Pairing.parse(txt.replace("UAQ1", "UAQ2")))
        assertNull(Pairing.parse(txt.replace("https://", "http://")))
        assertNull(Pairing.parse(txt.replace(mbox, mbox.substring(1))))
        val bad = code.substring(0, 11) + (if (code[11] == '0') '1' else '0')
        assertNull(Pairing.parse(txt.replace(code, bad)))
    }

    @Test fun jsonStringCodec() {
        val weird = "سطر 1\nسطر \"2\"\t\\ end \u0001"
        assertEquals(weird, Json.unquote(Json.quote(weird)))
        assertNull(Json.unquote("123"))
        assertNull(Json.unquote("null"))
    }

    // ---- الجدول ----
    @Test fun scheduleTimeParsing() {
        assertEquals(8 * 60 + 30, Schedule.parseTime("8:30"))
        assertEquals(8 * 60 + 30, Schedule.parseTime("08:30"))
        assertEquals(8 * 60 + 30, Schedule.parseTime("\u0668:\u0663\u0660"))     // ٨:٣٠
        assertEquals(8 * 60 + 30, Schedule.parseTime("0830"))
        assertNull(Schedule.parseTime("25:00")); assertNull(Schedule.parseTime("8:5")); assertNull(Schedule.parseTime("abc"))
        assertEquals("08:05", Schedule.fmt(8 * 60 + 5))
    }

    @Test fun scheduleCurrentAndOverlap() {
        val a = RSlot(1, 0, 8 * 60, 9 * 60 + 30, "B1")
        val b = RSlot(2, 0, 9 * 60, 10 * 60)
        assertTrue(Schedule.overlaps(a, b))
        assertFalse(Schedule.overlaps(a, RSlot(2, 0, 9 * 60 + 30, 10 * 60)))   // تلاصق بلا تداخل
        assertFalse(Schedule.overlaps(a, RSlot(2, 1, 8 * 60, 9 * 60)))         // يوم آخر
        assertEquals(a, Schedule.current(listOf(a, b), 0, 7 * 60 + 50))        // قبل البدء بـ10 دقائق
        assertNull(Schedule.current(listOf(a), 0, 7 * 60 + 30))                // مبكر جدًا
        assertNull(Schedule.current(listOf(a), 0, 9 * 60 + 30))                // انتهى
        assertNull(Schedule.current(listOf(a), 3, 8 * 60 + 30))                // يوم آخر
    }

    @Test fun scheduleRosterAndStorageRoundTrip() {
        val slots = listOf(RSlot(1, 0, 480, 570, "قاعة 3"), RSlot(1, 2, 600, 690), RSlot(2, 4, 720, 810, "B1"))
        val r = Roster(7, "D1", "د. خالد", listOf(RCourse(1, "CS101", "برمجة", "A"), RCourse(2, "M1", "رياضيات", "B")), emptyList(), slots)
        assertEquals(slots, Roster.parse(r.toText())?.slots)
        val code = Codes.random()
        assertEquals(slots, Roster.open(r.seal(code), code)?.slots)
        assertEquals(listOf(RSlot(1, 0, 480, 570, "قاعة 3")), Schedule.decode(Schedule.encode(listOf(slots[0]))))
        assertEquals("الأحد 08:00–09:30 (قاعة 3)، الثلاثاء 10:00–11:30", Schedule.describeAll(slots, 1))
    }

    // ---- بديل QR (تحدٍّ وإجابة) ----
    @Test fun qrChallengeRoundTripAndRejects() {
        val r = req()
        val t = QrProtocol.challengeText(r)
        val back = QrProtocol.parseChallenge(t)!!
        assertArrayEquals(r.sessionId, back.sessionId); assertArrayEquals(r.nonce, back.nonce); assertEquals(r.timeMs, back.timeMs)
        assertNull(QrProtocol.parseChallenge(t.replace("UAC1", "UAC2")))
        assertNull(QrProtocol.parseChallenge(t.dropLast(4)))
        assertNull(QrProtocol.parseChallenge("hello"))
        assertNull(QrProtocol.parseChallenge("UAC1|!!!"))
    }

    @Test fun qrFullChallengeReplyFlow() {
        val code = Codes.random(); val tag = Codes.tag(code); val key = Kdf.studentKey(code)
        val kp = ec(); val pub = EcKey.rawFromX509(kp.public.encoded)!!
        var now = 1_000_000L
        val ch = QrChallenges(clock = { now })
        val sid = UUID.randomUUID()
        // الدكتور يصدر تحدّيًا ويعرضه
        val shown = QrProtocol.challengeText(ch.issue(sid))
        // الطالب يمسحه ويحسب الإجابة كما في خدمة NFC
        val req = QrProtocol.parseChallenge(shown)!!
        val proof = Hmac.sha256(key, Protocol.bindMessage(tag, pub, req))
        val sig = sign(kp.private, Protocol.signedBytes(tag, pub, req))
        val replyTxt = QrProtocol.replyText(req.nonce, Protocol.AttendReply(tag, pub, proof, sig))
        // الدكتور يمسح الإجابة ويتحقق
        val pr = QrProtocol.parseReply(replyTxt)!!
        val mine = ch.find(pr.nonce)!!
        assertEquals(tag, pr.reply.tag)
        assertTrue(Hmac.equal(Hmac.sha256(key, Protocol.bindMessage(pr.reply.tag, pr.reply.pub, mine)), pr.reply.proof))
        assertTrue(SigVerifier.verify(pr.reply.pub, Protocol.signedBytes(pr.reply.tag, pr.reply.pub, mine), pr.reply.sig))
        assertTrue(replyTxt.length < 400)                                    // يتّسع في QR مقروء
        // nonce لم يصدر عن هاتف الدكتور => لا تحدّي
        assertNull(ch.find(ByteArray(16) { 7 }))
        // انتهاء الصلاحية بعد 60 ثانية
        now += 61_000
        assertNull(ch.find(pr.nonce))
    }

    @Test fun qrReplyRejectsTamperAndGarbage() {
        val code = Codes.random(); val tag = Codes.tag(code); val key = Kdf.studentKey(code)
        val kp = ec(); val pub = EcKey.rawFromX509(kp.public.encoded)!!
        val r = req()
        val txt = QrProtocol.replyText(r.nonce, Protocol.AttendReply(tag, pub, Hmac.sha256(key, Protocol.bindMessage(tag, pub, r)), sign(kp.private, Protocol.signedBytes(tag, pub, r))))
        assertNull(QrProtocol.parseReply(txt.replace("UAR1", "UAC1")))
        assertNull(QrProtocol.parseReply("UAR1|abc"))
        assertNull(QrProtocol.parseReply(txt.substringBeforeLast('|') + "|AAAA"))
        // تعديل حرف داخل الإجابة => إمّا يفشل التحليل أو يفشل إثبات الكود/التوقيع
        val i = txt.lastIndexOf('|') + 20
        val tampered = txt.substring(0, i) + (if (txt[i] == 'A') 'B' else 'A') + txt.substring(i + 1)
        val pr = QrProtocol.parseReply(tampered)
        if (pr != null) assertFalse(Hmac.equal(Hmac.sha256(key, Protocol.bindMessage(pr.reply.tag, pr.reply.pub, r)), pr.reply.proof) &&
            SigVerifier.verify(pr.reply.pub, Protocol.signedBytes(pr.reply.tag, pr.reply.pub, r), pr.reply.sig))
    }

    // ---- بلوتوث BLE ----
    @Test fun bleChallengeAndChunkedReplyRoundTrip() {
        val code = Codes.random(); val tag = Codes.tag(code); val key = Kdf.studentKey(code)
        val kp = ec(); val pub = EcKey.rawFromX509(kp.public.encoded)!!
        val ch = QrChallenges()
        val req0 = ch.issue(UUID.randomUUID())
        val req = BleProtocol.parseChallenge(BleProtocol.challengeBytes(req0))!!
        val proof = Hmac.sha256(key, Protocol.bindMessage(tag, pub, req))
        val sig = sign(kp.private, Protocol.signedBytes(tag, pub, req))
        val payload = BleProtocol.replyPayload(req.nonce, Protocol.AttendReply(tag, pub, proof, sig))
        val chunks = BleProtocol.chunk(payload, 18)               // أسوأ حالة: MTU افتراضي (20 بايت)
        assertTrue(chunks.all { it.size <= 20 })
        val asm = ProfileAssembler()
        var got: ByteArray? = null
        chunks.forEach { got = BleProtocol.add(asm, it) ?: got }
        assertArrayEquals(payload, got!!)
        val pr = BleProtocol.parseReplyPayload(got!!)!!
        val mine = ch.find(pr.nonce)!!
        assertTrue(Hmac.equal(Hmac.sha256(key, Protocol.bindMessage(pr.reply.tag, pr.reply.pub, mine)), pr.reply.proof))
        assertTrue(SigVerifier.verify(pr.reply.pub, Protocol.signedBytes(pr.reply.tag, pr.reply.pub, mine), pr.reply.sig))
    }

    @Test fun bleChunkingRejectsDisorderAndBadInput() {
        val payload = ByteArray(100) { it.toByte() }
        val chunks = BleProtocol.chunk(payload, 30)
        assertNull(BleProtocol.add(ProfileAssembler(), chunks[1]))            // لا تبدأ إلا من القطعة 0
        val asm2 = ProfileAssembler()
        assertNull(BleProtocol.add(asm2, chunks[0])); assertNull(BleProtocol.add(asm2, chunks[2]))   // خارج الترتيب
        assertNull(BleProtocol.add(ProfileAssembler(), byteArrayOf(0, 0, 1)))
        assertNull(BleProtocol.add(ProfileAssembler(), byteArrayOf(5, 2, 1)))
        assertNull(BleProtocol.parseChallenge(ByteArray(39)))
        assertNull(BleProtocol.parseReplyPayload(ByteArray(10)))
        assertNull(BleProtocol.parseReplyPayload(ByteArray(60)))
    }

    @Test fun bleResultEncoding() {
        val prof = byteArrayOf(1, 2, 3)
        val ok = BleProtocol.decodeResult(BleProtocol.encodeResult(Status.OK, prof))!!
        assertEquals(Status.OK, ok.status); assertArrayEquals(prof, ok.profile!!)
        val bad = BleProtocol.decodeResult(BleProtocol.encodeResult(Status.NOT_ENROLLED, prof))!!
        assertEquals(Status.NOT_ENROLLED, bad.status); assertNull(bad.profile)
        assertNull(BleProtocol.decodeResult(byteArrayOf(BleProtocol.NO_RESULT)))
        assertNull(BleProtocol.decodeResult(ByteArray(0)))
    }
}
