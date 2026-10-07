import com.uniatt.core.*
import java.security.SecureRandom

var passed = 0; var failed = 0
fun ok(c: Boolean, name: String) { if (c) { passed++; println("  PASS  $name") } else { failed++; println("  FAIL  $name") } }

fun main(args: Array<String>) {
    val url = args[0]
    val rnd = SecureRandom()
    println("[1] رمز الاقتران (QR)")
    val code = Codes.random(rnd); val mbox = Pairing.newMailbox(rnd)
    val info = Pairing.Info("https://demo-default-rtdb.firebaseio.com", mbox, "D1", code)
    val txt = Pairing.format(info)
    ok(Pairing.parse(txt) == info, "format ثم parse يعيد نفس البيانات (" + txt.length + " حرفًا في الـQR)")
    ok(Pairing.parse(txt.replace(code, Codes.format(code).lowercase()))?.code == code, "الكود بشرطات وأحرف صغيرة يُقبل ويُطبَّع")
    ok(Pairing.parse("XXX|a") == null && Pairing.parse(txt.replace("UAQ1", "UAQ2")) == null, "بادئة/عدد أجزاء خاطئ مرفوض")
    ok(Pairing.parse(txt.replace("https://", "http://")) == null, "عنوان http مرفوض (https فقط)")
    ok(Pairing.parse(txt.replace(mbox, mbox.substring(1))) == null, "صندوق بريد بطول خاطئ مرفوض")
    val badCode = code.substring(0, 11) + (if (code[11] == '0') '1' else '0')
    ok(Pairing.parse(txt.replace(code, badCode)) == null, "كود بخانة تحقق خاطئة مرفوض")
    ok(Pairing.normalizeUrl(" https://x.firebaseio.com/ ") == "https://x.firebaseio.com", "تنظيف المسافات والشرطة الأخيرة")
    ok(Pairing.newMailbox(rnd) != Pairing.newMailbox(rnd), "صناديق البريد عشوائية")

    println("[2] الترحيل (Relay) مقابل خادم يحاكي Firebase")
    val relay = Relay(url)
    ok(relay.getString("m/$mbox/none") == null, "مسار غير موجود => null")
    val weird = "سطر 1\nسطر \"2\"\t\\ end \u0001"
    relay.putString("m/$mbox/t", weird)
    ok(relay.getString("m/$mbox/t") == weird, "نص عربي مع علامات اقتباس وتحكّم يعود كما هو")
    val bytes = ByteArray(5000).also { rnd.nextBytes(it) }
    relay.putBytes("m/$mbox/b", bytes)
    ok(relay.getBytes("m/$mbox/b")!!.contentEquals(bytes), "5000 بايت عشوائي عبر Base64")
    ok(runCatching { relay.getString("zzz/forbidden") }.exceptionOrNull() is Relay.RelayException, "رفض الخادم (401) => RelayException")
    ok(runCatching { Relay("https://127.0.0.1:1").getString("m/a") }.isFailure, "خادم غير متاح => استثناء (لا تعليق)")

    println("[3] سيناريو كامل مسؤول <-> دكتور عبر الترحيل")
    val box = Mailbox(relay, mbox)
    val s1 = Codes.random(rnd)
    val students = listOf(RStudent("1001", "محمد أحمد", "كلية", "تخصص", "3", "A", Codes.tag(s1), Hex.enc(Kdf.studentKey(s1)), listOf(1L)))
    val r1 = Roster(1, "D1", "د. خالد", listOf(RCourse(1, "CS101", "برمجة", "A")), students)
    ok(box.rosterEpoch() == null && box.fetchRoster() == null, "قبل أي نشر: لا كشف ولا إصدار")
    box.publishRoster(r1.seal(code), 1)
    ok(box.rosterEpoch() == 1L, "إصدار الكشف يُقرأ كرقم")
    val got = Roster.open(box.fetchRoster()!!, code)
    ok(got != null && got.doctorName == "د. خالد" && got.courses[0].section == "A" && got.students[0].name == "محمد أحمد", "الدكتور يفتح الكشف: اسمه وشعبته وطلابه")
    ok(Roster.open(box.fetchRoster()!!, Codes.random(rnd)) == null, "كود غير الكود المقروء من الـQR لا يفتح الكشف")
    val r2 = r1.copy(epoch = 2, doctorName = "د. خالد الشهري", courses = r1.courses + RCourse(2, "MATH1", "رياضيات", "B"))
    box.publishRoster(r2.seal(code), 2)
    val got2 = Roster.open(box.fetchRoster()!!, code)!!
    ok(box.rosterEpoch() == 2L && got2.epoch == 2L && got2.courses.size == 2 && got2.doctorName == "د. خالد الشهري", "تحديث لاحق (اسم + شعبة جديدة) يصل تلقائيًا")
    val att = AttendanceFile("D1", 2, listOf(ASession("s-1", 1, 1000, 60)), listOf(ARecord("s-1", "1001", 1500)), listOf(ABinding("1001", Codes.tag(s1), "04" + "ab".repeat(64))))
    box.publishAttendance(att.seal(code))
    val back = AttendanceFile.open(box.fetchAttendance()!!, code)
    ok(back != null && back.records.size == 1 && back.records[0].studentId == "1001" && back.bindings.size == 1, "المسؤول يفتح الحضور المرفوع من الدكتور")
    ok(AttendanceFile.open(box.fetchAttendance()!!, Codes.random(rnd)) == null, "الحضور لا يُفتح بكود آخر")
    val other = Mailbox(relay, Pairing.newMailbox(rnd))
    ok(other.fetchRoster() == null && other.fetchAttendance() == null, "صندوق دكتور آخر معزول")
    box.wipe()
    ok(box.fetchRoster() == null && box.rosterEpoch() == null && box.fetchAttendance() == null, "wipe يمحو كل شيء (إلغاء ربط الدكتور)")

    println("[4] اكتشاف عنوان القاعدة الصحيح (خطأ 404 = منطقة/عنوان خاطئ)")
    val dead = args[1]
    ok(Relay(dead).probe() == 404, "عنوان خاطئ => probe يعيد 404")
    ok(Relay(url).probe() == 401, "قاعدة موجودة والقواعد تمنع الجذر => 401 (أي موجودة)")
    ok(runCatching { Relay(dead).getString("m/x/y") }.exceptionOrNull().let { it is Relay.RelayException && it.code == 404 }, "قراءة من عنوان خاطئ => RelayException(404)")
    ok(Relay.RelayException(404, "x").friendly().contains("عنوانها غير صحيح") && Relay.RelayException(401, "x").friendly().contains("Rules"), "رسائل عربية مفهومة لـ404 و401")
    ok(RelayLocator.dbName("https://maggd-141d1-default-rtdb.europe-west1.firebasedatabase.app/") == "maggd-141d1-default-rtdb" &&
        RelayLocator.dbName("https://maggd-141d1-default-rtdb.firebaseio.com") == "maggd-141d1-default-rtdb", "استخراج اسم القاعدة من العنوانين")
    val real = RelayLocator.candidatesProvider
    RelayLocator.candidatesProvider = { listOf(dead, url) }
    ok(RelayLocator.find(dead) == url, "يبدأ بالعنوان الخاطئ ويجد الصحيح تلقائيًا")
    ok(RelayLocator.find(url) == url, "العنوان الصحيح يبقى كما هو")
    RelayLocator.candidatesProvider = { listOf(dead) }
    ok(RelayLocator.find(dead) == null, "لا قاعدة بهذا الاسم في أي منطقة => null")
    RelayLocator.candidatesProvider = real
    ok(real("db").any { it == "https://db.europe-west1.firebasedatabase.app" } && real("db").any { it == "https://db.firebaseio.com" }, "المرشحون الحقيقيون يشملون أوروبا وأمريكا")

    println("\nالنتيجة: نجح $passed، فشل $failed")
    if (failed > 0) System.exit(1)
}
