// نسخة مرجعية بجافا من منطق وحدة core (الأكواد/التشفير/البروتوكول/الكشف) للتحقق منه بدون أندرويد.
// التشغيل:  java tools/CoreReference.java
// ملاحظة: هذه ليست نفس شيفرة Kotlin، بل مطابقة لها دالة بدالة وتستخدم نفس واجهات JCA (PBKDF2/HMAC/AES-GCM/ECDSA).
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.*;

public class CoreReference {
    // ---------- Codes ----------
    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    static int val(char c) { return ALPHABET.indexOf(c); }
    static char check(String body) { int s = 0; for (int i = 0; i < body.length(); i++) s += (2 * i + 1) * val(body.charAt(i)); return ALPHABET.charAt(s % 32); }
    static String randomCode(SecureRandom r) { StringBuilder b = new StringBuilder(); for (int i = 0; i < 11; i++) b.append(ALPHABET.charAt(r.nextInt(32))); return b.toString() + check(b.toString()); }
    static String tag(String code) { return code.substring(0, 4); }
    static String normalize(String in) {
        StringBuilder sb = new StringBuilder();
        for (char ch : in.toCharArray()) {
            int d = Character.digit(ch, 10); char c;
            if (d >= 0) c = (char) ('0' + d); else if (Character.isLetter(ch)) c = Character.toUpperCase(ch); else continue;
            sb.append(c == 'O' ? '0' : (c == 'I' || c == 'L') ? '1' : c);
        }
        String s = sb.toString();
        if (s.length() != 12) return null;
        for (char c : s.toCharArray()) if (val(c) < 0) return null;
        return check(s.substring(0, 11)) == s.charAt(11) ? s : null;
    }
    // ---------- Crypto ----------
    static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }
    static byte[] unhex(String s) { byte[] o = new byte[s.length() / 2]; for (int i = 0; i < o.length; i++) o[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16); return o; }
    static byte[] cat(byte[]... p) { ByteArrayOutputStream o = new ByteArrayOutputStream(); for (byte[] x : p) o.writeBytes(x); return o.toByteArray(); }
    static byte[] ascii(String s) { return s.getBytes(StandardCharsets.US_ASCII); }
    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static byte[] hmac(byte[] key, byte[] data) throws Exception { Mac m = Mac.getInstance("HmacSHA256"); m.init(new SecretKeySpec(key, "HmacSHA256")); return m.doFinal(data); }
    static byte[] pbkdf2(String pw, byte[] salt, int it) throws Exception { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(pw.toCharArray(), salt, it, 256)).getEncoded(); }
    static final int STUDENT_ITER = 10_000, DOCTOR_ITER = 100_000;
    static byte[] studentKey(String code) throws Exception { return pbkdf2(code, utf8("uniatt-student|" + tag(code)), STUDENT_ITER); }
    static final byte[] M_ROSTER = ascii("UAR1"), M_ATTEND = ascii("UAT1");
    static byte[] seal(byte[] magic, String hint, byte[] plain, String code) throws Exception {
        SecureRandom r = new SecureRandom(); byte[] h = utf8(hint); byte[] salt = new byte[16], iv = new byte[12]; r.nextBytes(salt); r.nextBytes(iv);
        byte[] header = cat(magic, new byte[]{(byte) h.length}, h);
        return cat(header, salt, iv, cipher(Cipher.ENCRYPT_MODE, code, salt, iv, header).doFinal(plain));
    }
    static String hint(byte[] magic, byte[] blob) {
        if (blob.length < 5 || !Arrays.equals(Arrays.copyOfRange(blob, 0, 4), magic)) return null;
        int n = blob[4] & 0xFF; if (blob.length < 5 + n + 16 + 12 + 16) return null;
        return new String(blob, 5, n, StandardCharsets.UTF_8);
    }
    static byte[] open(byte[] magic, byte[] blob, String code) {
        try {
            if (hint(magic, blob) == null) return null;
            int n = blob[4] & 0xFF, he = 5 + n;
            byte[] header = Arrays.copyOfRange(blob, 0, he), salt = Arrays.copyOfRange(blob, he, he + 16), iv = Arrays.copyOfRange(blob, he + 16, he + 28), ct = Arrays.copyOfRange(blob, he + 28, blob.length);
            return cipher(Cipher.DECRYPT_MODE, code, salt, iv, header).doFinal(ct);
        } catch (Exception e) { return null; }
    }
    static Cipher cipher(int mode, String code, byte[] salt, byte[] iv, byte[] aad) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(mode, new SecretKeySpec(pbkdf2(code, salt, DOCTOR_ITER), "AES"), new GCMParameterSpec(128, iv)); c.updateAAD(aad); return c;
    }
    static final byte[] X509H = unhex("3059301306072a8648ce3d020106082a8648ce3d030107034200");
    static byte[] rawFromX509(byte[] e) { return e.length == 91 && Arrays.equals(Arrays.copyOfRange(e, 0, 26), X509H) ? Arrays.copyOfRange(e, 26, 91) : null; }
    static byte[] toX509(byte[] raw) { return cat(X509H, raw); }
    static boolean verifySig(byte[] raw, byte[] data, byte[] sig) {
        try {
            if (raw.length != 65 || raw[0] != 4) return false;
            PublicKey p = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(toX509(raw)));
            Signature s = Signature.getInstance("SHA256withECDSA"); s.initVerify(p); s.update(data); return s.verify(sig);
        } catch (Exception e) { return false; }
    }
    static byte[] sign(PrivateKey k, byte[] data) throws Exception { Signature s = Signature.getInstance("SHA256withECDSA"); s.initSign(k); s.update(data); return s.sign(); }
    static KeyPair newEc() throws Exception { KeyPairGenerator g = KeyPairGenerator.getInstance("EC"); g.initialize(new ECGenParameterSpec("secp256r1")); return g.generateKeyPair(); }
    // ---------- Protocol ----------
    record Req(byte[] session, byte[] nonce, long time) {}
    record Reply(String tag, byte[] pub, byte[] proof, byte[] sig) {}
    static byte[] longBytes(long v) { return ByteBuffer.allocate(8).putLong(v).array(); }
    static byte[] core(String tag, byte[] pub, Req r) { return cat(ascii(tag), pub, r.session, r.nonce, longBytes(r.time)); }
    static byte[] bindMsg(String t, byte[] pub, Req r) { return cat(ascii("UA-BIND"), core(t, pub, r)); }
    static byte[] signedBytes(String t, byte[] pub, Req r) { return cat(ascii("UA-SIGN"), core(t, pub, r)); }
    static final byte[] SW_OK = {(byte) 0x90, 0}, SW_ERR = {0x6F, 0};
    static byte[] attendCmd(Req r) { byte[] d = cat(r.session, r.nonce, longBytes(r.time)); return cat(new byte[]{(byte) 0x80, 0x10, 0, 0, (byte) d.length}, d); }
    static Req parseAttendCmd(byte[] a) { if (a.length != 45 || a[0] != (byte) 0x80 || a[1] != 0x10) return null; ByteBuffer b = ByteBuffer.wrap(a, 5, 40); byte[] s = new byte[16], n = new byte[16]; b.get(s); b.get(n); return new Req(s, n, b.getLong()); }
    static byte[] attendResp(Reply r) { return cat(ascii(r.tag), r.pub, r.proof, r.sig, SW_OK); }
    static Reply parseAttendResp(byte[] resp) {
        int fixed = 4 + 65 + 32; if (resp.length < fixed + 8 + 2 || !isOk(resp)) return null;
        byte[] body = Arrays.copyOfRange(resp, 0, resp.length - 2); byte[] sig = Arrays.copyOfRange(body, fixed, body.length); if (sig.length > 80) return null;
        return new Reply(new String(body, 0, 4, StandardCharsets.US_ASCII), Arrays.copyOfRange(body, 4, 69), Arrays.copyOfRange(body, 69, fixed), sig);
    }
    static boolean isOk(byte[] r) { return r.length >= 2 && r[r.length - 2] == (byte) 0x90 && r[r.length - 1] == 0; }
    static List<byte[]> profileCmds(byte[] p) {
        int total = (p.length + 199) / 200; List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < total; i++) { byte[] d = Arrays.copyOfRange(p, i * 200, Math.min(p.length, (i + 1) * 200)); out.add(cat(new byte[]{(byte) 0x80, 0x30, 0, 0, (byte) (d.length + 2), (byte) i, (byte) total}, d)); }
        return out;
    }
    record Chunk(int idx, int total, byte[] data) {}
    static Chunk parseProfileCmd(byte[] a) {
        if (a.length < 8 || a[0] != (byte) 0x80 || a[1] != 0x30) return null; int lc = a[4] & 0xFF; if (lc < 3 || a.length != 5 + lc) return null;
        int idx = a[5] & 0xFF, total = a[6] & 0xFF; if (total == 0 || idx >= total) return null; return new Chunk(idx, total, Arrays.copyOfRange(a, 7, a.length));
    }
    static class Assembler {
        int next, expected; ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] add(Chunk c) {
            if (c.idx == 0) { buf.reset(); next = 0; expected = c.total; }
            if (c.idx != next || c.total != expected) { reset(); return null; }
            buf.write(c.data, 0, c.data.length); next++;
            if (buf.size() > 4096) { reset(); return null; }
            if (next == expected) { byte[] o = buf.toByteArray(); reset(); return o; } return null;
        }
        void reset() { buf.reset(); next = 0; expected = 0; }
    }
    // ---------- Profile ----------
    static final char US = '\u001F', RS = '\u001E';
    static String cl(String s) { return s.replace(US, ' ').replace(RS, ' '); }
    static byte[] encProfile(String[] f6, List<String> courses) {
        List<String> f = new ArrayList<>(); for (String s : f6) f.add(cl(s));
        StringBuilder cs = new StringBuilder(); for (int i = 0; i < courses.size(); i++) { if (i > 0) cs.append(RS); cs.append(cl(courses.get(i))); }
        f.add(cs.toString()); return utf8(String.join(String.valueOf(US), f));
    }
    // ---------- Roster text ----------
    record RS_(String id, String name, String tag, String keyHex, List<Long> courseIds) {}
    static String tabClean(String s) { return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim(); }
    static String rosterText(long epoch, String did, String dname, List<String[]> courses, List<RS_> students) {
        StringBuilder b = new StringBuilder("UAR1\nE\t" + epoch + "\nD\t" + tabClean(did) + "\t" + tabClean(dname) + "\n");
        for (String[] c : courses) b.append("C\t").append(c[0]).append('\t').append(tabClean(c[1])).append('\t').append(tabClean(c[2])).append('\t').append(tabClean(c[3])).append('\n');
        for (RS_ s : students) { StringBuilder ids = new StringBuilder(); for (int i = 0; i < s.courseIds.size(); i++) { if (i > 0) ids.append(';'); ids.append(s.courseIds.get(i)); }
            b.append("S\t").append(tabClean(s.id)).append('\t').append(tabClean(s.name)).append("\tF\tM\tL\tA\t").append(s.tag).append('\t').append(s.keyHex).append('\t').append(ids).append('\n'); }
        return b.toString();
    }
    static Map<String, RS_> parseRoster(String text) {
        String[] lines = text.split("\n"); if (!lines[0].equals("UAR1")) return null; Map<String, RS_> out = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) { if (lines[i].isEmpty()) continue; String[] f = lines[i].split("\t", -1);
            if (f[0].equals("S")) { if (f.length != 10 || f[7].length() != 4 || f[8].length() != 64) return null; List<Long> ids = new ArrayList<>(); for (String x : f[9].split(";")) if (!x.isEmpty()) ids.add(Long.parseLong(x)); out.put(f[7], new RS_(f[1], f[2], f[7], f[8], ids)); } }
        return out;
    }

    // ---------- test harness ----------
    static int passed = 0, failed = 0;
    static void ok(boolean c, String name) { if (c) { passed++; System.out.println("  PASS  " + name); } else { failed++; System.out.println("  FAIL  " + name); } }

    public static void main(String[] args) throws Exception {
        SecureRandom rnd = new SecureRandom();

        System.out.println("[1] الأكواد");
        String code = randomCode(rnd);
        ok(code.length() == 12 && normalize(code).equals(code), "كود عشوائي صالح ويمرّ من normalize: " + code);
        ok(normalize(code.toLowerCase().substring(0, 4) + "-" + code.substring(4, 8) + " " + code.substring(8)).equals(code), "يقبل حروفًا صغيرة وشرطات ومسافات");
        String ar = code.chars().mapToObj(c -> { int d = Character.digit(c, 10); return d >= 0 ? String.valueOf((char) ('٠' + d)) : String.valueOf((char) c); }).reduce("", String::concat);
        ok(normalize(ar).equals(code), "يقبل الأرقام العربية ٠-٩");
        int undetected = 0, total = 0;
        for (int t = 0; t < 300; t++) { String c = randomCode(rnd);
            for (int i = 0; i < 12; i++) for (char a : ALPHABET.toCharArray()) { if (a == c.charAt(i)) continue; String m = c.substring(0, i) + a + c.substring(i + 1); total++; if (normalize(m) != null) undetected++; } }
        ok(undetected == 0, "كل خطأ بخانة واحدة يُكتشف (" + total + " حالة، غير مكتشف=" + undetected + ")");
        int tr = 0, trUndet = 0; for (int t = 0; t < 2000; t++) { String c = randomCode(rnd); int i = rnd.nextInt(11); if (c.charAt(i) == c.charAt(i + 1)) continue;
            char[] a = c.toCharArray(); char x = a[i]; a[i] = a[i + 1]; a[i + 1] = x; tr++; if (normalize(new String(a)) != null) trUndet++; }
        System.out.println("  INFO  تبديل خانتين متجاورتين: غير مكتشف " + trUndet + " من " + tr + String.format(" (%.1f%%)", 100.0 * trUndet / tr));
        ok(normalize("ABC") == null && normalize("") == null && normalize("UUUU-UUUU-UUUU") == null, "رفض الطول الخاطئ والحرف U");
        Set<String> tags = new HashSet<>(); int dup = 0; for (int i = 0; i < 5000; i++) { String t = tag(randomCode(rnd)); if (!tags.add(t)) dup++; }
        System.out.println("  INFO  تصادم الوسوم العشوائي عند 5000 طالب: " + dup + " (الإدارة تعيد التوليد عند أي تصادم عبر newUnique)");

        System.out.println("[2] الصندوق المشفّر (AES-GCM)");
        String dcode = randomCode(rnd); byte[] plain = utf8("كشف تجريبي\nسطر ثانٍ");
        long t0 = System.nanoTime(); byte[] blob = seal(M_ROSTER, "D1", plain, dcode); long sealMs = (System.nanoTime() - t0) / 1_000_000;
        ok(Arrays.equals(open(M_ROSTER, blob, dcode), plain), "فتح بالكود الصحيح يعيد النص (زمن التشفير " + sealMs + " ms على هذا الجهاز)");
        ok(hint(M_ROSTER, blob).equals("D1"), "قراءة معرّف الدكتور بدون فتح");
        ok(open(M_ROSTER, blob, randomCode(rnd)) == null, "كود خاطئ => null");
        boolean allTamper = true; for (int i : new int[]{0, 4, 5, 6, 10, blob.length - 1, blob.length - 20}) { byte[] b = blob.clone(); b[i] ^= 1; if (open(M_ROSTER, b, dcode) != null) allTamper = false; }
        ok(allTamper, "أي تعديل بايت (magic/hint/salt/iv/نص/وسم) => رفض");
        ok(open(M_ATTEND, blob, dcode) == null, "ملف كشف لا يُفتح كملف حضور");
        ok(open(M_ROSTER, Arrays.copyOf(blob, 10), dcode) == null, "ملف مبتور => null بلا استثناء");

        System.out.println("[3] مفتاح EC والتواقيع");
        KeyPair kp = newEc(); byte[] enc = kp.getPublic().getEncoded(); byte[] raw = rawFromX509(enc);
        ok(enc.length == 91 && raw != null && raw.length == 65 && raw[0] == 4, "X.509 = 91 بايت وترويسته مطابقة للثابت، والنقطة الخام 65");
        ok(Arrays.equals(toX509(raw), enc), "toX509(raw) يعيد نفس الترميز");
        byte[] data = utf8("hello"); byte[] sig = sign(kp.getPrivate(), data);
        ok(verifySig(raw, data, sig), "توقيع صحيح"); ok(!verifySig(raw, utf8("hellp"), sig), "بيانات معدّلة مرفوضة");
        byte[] badPt = raw.clone(); badPt[10] ^= 1; ok(!verifySig(badPt, data, sig), "نقطة ليست على المنحنى => false بلا استثناء");
        ok(!verifySig(newEc().getPublic().getEncoded().length == 91 ? rawFromX509(newEc().getPublic().getEncoded()) : raw, data, sig), "مفتاح عام آخر مرفوض");
        int maxSig = 0; for (int i = 0; i < 300; i++) maxSig = Math.max(maxSig, sign(kp.getPrivate(), data).length);
        System.out.println("  INFO  أكبر طول DER لتوقيع ECDSA في 300 تجربة: " + maxSig + " بايت");

        System.out.println("[4] سيناريو كامل: مسؤول -> دكتور -> طالب (بدون خادم)");
        String s1 = "ABCD1234567".substring(0, 11); String s1code = s1 + check(s1);
        String s2 = "WXYZ9876543".substring(0, 11); String s2code = s2 + check(s2);
        ok(normalize(s1code) != null && normalize(s2code) != null, "أكواد الطلاب صالحة: " + s1code + " / " + s2code);
        List<RS_> sts = List.of(new RS_("1001", "محمد أحمد", tag(s1code), hex(studentKey(s1code)), List.of(1L)),
                                new RS_("1002", "سارة علي", tag(s2code), hex(studentKey(s2code)), List.of(2L)));
        String dc = randomCode(rnd);
        byte[] file = seal(M_ROSTER, "D1", utf8(rosterText(5, "D1", "د. خالد", List.of(new String[]{"1", "CS101", "برمجة", "A"}, new String[]{"2", "MATH1", "رياضيات", "B"}), sts)), dc);
        ok(open(M_ROSTER, file, "0000-0000-0000".replace("-", "")) == null, "كود دكتور خاطئ لا يفتح الملف");
        Map<String, RS_> roster = parseRoster(new String(open(M_ROSTER, file, dc), StandardCharsets.UTF_8));
        ok(roster != null && roster.size() == 2 && roster.get(tag(s1code)).name.equals("محمد أحمد"), "الدكتور يفتح الكشف ويقرأ الطلاب (عربي سليم)");

        // هاتف الطالب (جهاز حقيقي = Keystore؛ هنا KeyPair عادي)
        KeyPair phone1 = newEc(); byte[] pub1 = rawFromX509(phone1.getPublic().getEncoded()); byte[] key1 = studentKey(s1code);
        Map<String, byte[]> boundPub = new HashMap<>(); Set<String> seen = new HashSet<>(); byte[] session = new byte[16]; rnd.nextBytes(session);
        // دالة تقييم الدكتور (تطابق Reader.evaluate)
        java.util.function.BiFunction<Reply, Req, Integer> evaluate = (rep, req) -> {
            RS_ st = roster.get(rep.tag); if (st == null) return 5;
            try { if (!MessageDigest.isEqual(hmac(unhex(st.keyHex), bindMsg(rep.tag, rep.pub, req)), rep.proof)) return 7; } catch (Exception e) { return 7; }
            if (!verifySig(rep.pub, signedBytes(rep.tag, rep.pub, req), rep.sig)) return 3;
            if (!st.courseIds.contains(1L)) return 2;
            byte[] b = boundPub.get(st.id); if (b != null && !Arrays.equals(b, rep.pub)) return 6; if (b == null) boundPub.put(st.id, rep.pub);
            return seen.add(st.id) ? 0 : 1;
        };
        java.util.function.Function<Object[], byte[]> studentReply = a -> { try { // {tag,key,pubRaw,privKey,req}
            String tg = (String) a[0]; byte[] k = (byte[]) a[1]; byte[] pb = (byte[]) a[2]; PrivateKey pk = (PrivateKey) a[3]; Req rq = (Req) a[4];
            return attendResp(new Reply(tg, pb, hmac(k, bindMsg(tg, pb, rq)), sign(pk, signedBytes(tg, pb, rq)))); } catch (Exception e) { throw new RuntimeException(e); } };
        java.util.function.Supplier<Req> newReq = () -> { byte[] n = new byte[16]; rnd.nextBytes(n); return new Req(session, n, System.currentTimeMillis()); };

        Req rq = newReq.get(); byte[] cmd = attendCmd(rq); Req parsedReq = parseAttendCmd(cmd);
        ok(cmd.length == 45 && Arrays.equals(parsedReq.nonce, rq.nonce) && parsedReq.time == rq.time, "أمر ATTEND: 45 بايت ويُعاد تحليله");
        byte[] resp = studentReply.apply(new Object[]{tag(s1code), key1, pub1, phone1.getPrivate(), parsedReq});
        System.out.println("  INFO  حجم رد الطالب: " + resp.length + " بايت (الحد الآمن للإطار القصير 255)");
        ok(resp.length <= 255, "رد الطالب أقل من 256 بايت");
        Reply rep = parseAttendResp(resp);
        ok(rep != null && rep.tag.equals(tag(s1code)) && Arrays.equals(rep.pub, pub1), "الدكتور يحلّل الرد");
        ok(evaluate.apply(rep, rq) == 0, "أول لمسة: OK + ربط الجهاز بالهوية (TOFU مع إثبات الكود)");
        ok(evaluate.apply(rep, rq) == 1, "لمسة ثانية في نفس الجلسة: DUPLICATE");

        Req rq2 = newReq.get();
        ok(evaluate.apply(rep, rq2) == 7, "إعادة إرسال رد قديم مع nonce جديد: مرفوضة (BAD_PROOF)");
        // مهاجم يعرف الكود فقط على هاتف آخر بعد ربط الهاتف الأول
        KeyPair phoneX = newEc(); byte[] pubX = rawFromX509(phoneX.getPublic().getEncoded());
        Req rq3 = newReq.get(); Reply repX = parseAttendResp(studentReply.apply(new Object[]{tag(s1code), key1, pubX, phoneX.getPrivate(), rq3}));
        ok(evaluate.apply(repX, rq3) == 6, "نفس الكود على جهاز آخر بعد الربط: KEY_MISMATCH");
        // مهاجم بلا الكود
        Req rq4 = newReq.get(); Reply repW = parseAttendResp(studentReply.apply(new Object[]{tag(s1code), studentKey(s2code), pub1, phone1.getPrivate(), rq4}));
        ok(evaluate.apply(repW, rq4) == 7, "جهاز بلا الكود الصحيح: BAD_PROOF");
        // انتحال طالب آخر بنفس الجهاز: وسم الطالب 2 بمفتاح الطالب 1
        Req rq5 = newReq.get(); Reply repI = parseAttendResp(studentReply.apply(new Object[]{tag(s2code), key1, pub1, phone1.getPrivate(), rq5}));
        ok(evaluate.apply(repI, rq5) == 7, "انتحال وسم طالب آخر: BAD_PROOF");
        // وسم غير موجود
        Req rq6 = newReq.get(); Reply repU = parseAttendResp(studentReply.apply(new Object[]{"ZZZZ", key1, pub1, phone1.getPrivate(), rq6}));
        ok(evaluate.apply(repU, rq6) == 5, "وسم غير موجود بالكشف: UNKNOWN_STUDENT");
        // الطالب 2 غير مسجل في مادة هذه الجلسة
        KeyPair phone2 = newEc(); Req rq7 = newReq.get(); Reply rep2 = parseAttendResp(studentReply.apply(new Object[]{tag(s2code), studentKey(s2code), rawFromX509(phone2.getPublic().getEncoded()), phone2.getPrivate(), rq7}));
        ok(evaluate.apply(rep2, rq7) == 2, "طالب بكشف الدكتور لكنه ليس في مادة الجلسة: NOT_ENROLLED (ولا يُربط جهازه)");
        ok(!boundPub.containsKey("1002"), "لم يُربط جهاز الطالب غير المسجل");
        // تعديل توقيع
        Req rq8 = newReq.get(); byte[] r8 = studentReply.apply(new Object[]{tag(s1code), key1, pub1, phone1.getPrivate(), rq8}); r8[80] ^= 1; Reply rep8 = parseAttendResp(r8);
        ok(evaluate.apply(rep8, rq8) == 7, "تعديل أي بايت داخل الرد يُرفض");
        Req rq9 = newReq.get(); Reply good9 = parseAttendResp(studentReply.apply(new Object[]{tag(s1code), key1, pub1, phone1.getPrivate(), rq9}));
        Reply badSig = new Reply(good9.tag, good9.pub, good9.proof, good9.sig.clone()); badSig.sig[10] ^= 1;
        ok(evaluate.apply(badSig, rq9) == 3, "تعديل التوقيع فقط (الإثبات سليم): BAD_SIGNATURE");
        ok(parseAttendResp(SW_ERR) == null, "رد 6F00 (الطالب لم يُدخل كوده) => null");

        System.out.println("[5] بيانات الطالب (PROFILE) وتقسيمها");
        List<String> courses = List.of("CS101 برمجة (A)", "MATH1 رياضيات (B)", "PHY2 فيزياء عامة وتطبيقية (C)");
        byte[] prof = encProfile(new String[]{"1001", "محمد أحمد عبدالرحمن الشهري", "كلية الحاسبات وتقنية المعلومات", "علوم الحاسب", "3", "A"}, courses);
        List<byte[]> cmds = profileCmds(prof);
        ok(cmds.stream().allMatch(c -> c.length <= 255), "كل أجزاء PROFILE ضمن 255 بايت (" + cmds.size() + " جزء لحمولة " + prof.length + " بايت)");
        Assembler asm = new Assembler(); byte[] outp = null; for (byte[] c : cmds) outp = asm.add(parseProfileCmd(c));
        ok(outp != null && Arrays.equals(outp, prof), "إعادة التجميع تطابق الأصل");
        String[] back = new String(outp, StandardCharsets.UTF_8).split(String.valueOf(US), -1);
        ok(back.length == 7 && back[0].equals("1001") && back[6].split(String.valueOf(RS)).length == 3, "فكّ الحقول والمواد الثلاث (الفاصل RS محفوظ)");
        byte[] big = new byte[450]; Arrays.fill(big, (byte) 'a'); List<byte[]> bc = profileCmds(big); Assembler a2 = new Assembler();
        ok(a2.add(parseProfileCmd(bc.get(1))) == null, "جزء خارج الترتيب يُرفض");
        Assembler a3 = new Assembler(); byte[] o3 = null; for (byte[] c : bc) o3 = a3.add(parseProfileCmd(c)); ok(o3 != null && o3.length == 450, "حمولة 450 بايت => 3 أجزاء وتتجمع");
        ok(parseProfileCmd(new byte[]{(byte) 0x80, 0x30, 0, 0, 3, 0, 1, 1, 9}) == null, "Lc لا يطابق الطول => null");

        System.out.println("[6] زمن اشتقاق المفاتيح");
        t0 = System.nanoTime(); for (int i = 0; i < 20; i++) studentKey(randomCode(rnd)); double perStudent = (System.nanoTime() - t0) / 20e6;
        System.out.println(String.format("  INFO  اشتقاق مفتاح طالب (10k تكرار): %.1f ms لكل طالب على هذا الجهاز؛ 2000 طالب ≈ %.0f ثانية متسلسلة (أبطأ غالبًا على الهواتف)", perStudent, perStudent * 2));

        System.out.println("\nالنتيجة: نجح " + passed + "، فشل " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
