import com.uniatt.admin.*
import com.uniatt.core.*
import kotlinx.coroutines.*
import java.io.File
import java.security.SecureRandom

var passed = 0; var failed = 0
fun ok(c: Boolean, name: String) { if (c) { passed++; println("  PASS  $name") } else { failed++; println("  FAIL  $name") } }

fun student(id: String, name: String, courseIds: List<Long>, rnd: SecureRandom): Student {
    val code = Codes.random(rnd)
    return Student(id, name, "كلية", "تخصص", "3", "A", courseIds, code, Hex.enc(Kdf.studentKey(code)))
}

fun main(args: Array<String>) = runBlocking {
    val url = args[0]; val deadUrl = args[1]; val rnd = SecureRandom()
    val dir = File.createTempFile("adm", "").also { it.delete(); it.mkdirs() }
    val store = AdminStore(dir)
    val dcode = Codes.random(rnd); val mbox = Pairing.newMailbox(rnd)
    val s1 = student("1001", "محمد أحمد", listOf(1L), rnd)
    store.save(AdminState(seq = 1, relayUrl = url,
        courses = listOf(Course(1, "CS101", "برمجة", "A")), students = listOf(s1),
        doctors = listOf(Doctor("D1", "د. خالد", listOf(1L), dcode, mailbox = mbox))))
    val box = Mailbox(Relay(url), mbox)

    println("[1] نشر الكشف")
    var r = AdminSync.run(store)
    var st = store.load(); var d = st.doctors[0]
    ok(r.pushed == 1 && !r.offline && !r.noRelay && r.errors.isEmpty(), "أول مزامنة: نُشر كشف واحد")
    ok(box.rosterEpoch() == 1L && st.epoch == 1L && d.pushedEpoch == 1L && d.rosterHash.isNotEmpty(), "الإصدار 1 في الترحيل والحالة")
    val ro = Roster.open(box.fetchRoster()!!, dcode)
    ok(ro != null && ro.doctorName == "د. خالد" && ro.courses[0].section == "A" && ro.students.size == 1 && ro.students[0].tag == s1.tag, "الدكتور يفتح: اسم + شعبة + طالب بوسمه")
    ok(ro!!.students[0].keyHex == s1.keyHex && !box.fetchRoster()!!.decodeToString().contains(s1.code), "المفتاح المشتق فقط في الكشف، لا كود الطالب")
    r = AdminSync.run(store)
    ok(r.pushed == 0 && box.rosterEpoch() == 1L && store.load().epoch == 1L, "مزامنة ثانية بلا تغيير: لا إعادة نشر (الإصدار ثابت)")

    println("[2] تعديلات المسؤول تصل تلقائيًا")
    store.update { it.copy(students = it.students.map { s -> s.copy(name = "محمد أحمد الشهري") } + student("1002", "سارة علي", listOf(1L), rnd)) }
    r = AdminSync.run(store)
    val ro2 = Roster.open(box.fetchRoster()!!, dcode)!!
    ok(r.pushed == 1 && box.rosterEpoch() == 2L && ro2.students.size == 2 && ro2.students.any { it.name == "محمد أحمد الشهري" }, "تعديل اسم + طالب جديد => إصدار 2")
    store.update { it.copy(courses = it.courses + Course(2, "MATH1", "رياضيات", "B"), doctors = it.doctors.map { x -> x.copy(courseIds = listOf(1L, 2L)) }) }
    AdminSync.run(store)
    val ro3 = Roster.open(box.fetchRoster()!!, dcode)!!
    ok(box.rosterEpoch() == 3L && ro3.courses.map { it.section } == listOf("A", "B"), "شعبة/مادة جديدة للدكتور => إصدار 3")

    println("[2b] الجدول الأسبوعي (يحدّده المسؤول)")
    store.update { it.copy(slots = listOf(Slot(10, 1, 0, 480, 570, "قاعة 3"), Slot(11, 1, 2, 600, 690, ""), Slot(12, 2, 4, 720, 810, "B1"))) }
    AdminSync.run(store)
    val roS = Roster.open(box.fetchRoster()!!, dcode)!!
    ok(box.rosterEpoch() == 4L && roS.slots.size == 3, "إضافة 3 مواعيد => إصدار جديد فيه المواعيد (4)")
    ok(roS.slots.contains(RSlot(1, 0, 480, 570, "قاعة 3")) && roS.slots.any { it.courseId == 2L && it.day == 4 }, "الموعد والقاعة والمادة الصحيحة")
    store.update { it.copy(doctors = it.doctors.map { x -> x.copy(courseIds = listOf(1L)) }) }
    AdminSync.run(store)
    ok(Roster.open(box.fetchRoster()!!, dcode)!!.slots.all { it.courseId == 1L }, "الدكتور يستلم مواعيد موادّه فقط")
    store.update { it.copy(doctors = it.doctors.map { x -> x.copy(courseIds = listOf(1L, 2L)) }) }
    AdminSync.run(store)
    store.update { it.copy(slots = it.slots.filter { s -> s.id != 11L }) }
    AdminSync.run(store)
    ok(Roster.open(box.fetchRoster()!!, dcode)!!.slots.size == 2, "حذف موعد عند المسؤول يصل للدكتور")
    val ep = box.rosterEpoch()
    AdminSync.run(store)
    ok(box.rosterEpoch() == ep, "بلا تغيير: لا إصدار جديد")

    println("[3] إشعار الاستلام")
    ok(store.load().doctors[0].ackEpoch == 0L, "قبل أن يمسح الدكتور: لا ack")
    box.publishAck(box.rosterEpoch()!!)
    AdminSync.run(store)
    ok(store.load().doctors[0].ackEpoch == box.rosterEpoch(), "بعد ack: المسؤول يعرف أن الكشف وصل")

    println("[4] الحضور القادم من الدكتور")
    fun att(recs: List<ARecord>) = AttendanceFile("D1", 3, listOf(ASession("sess-1", 1, 1000, 60)), recs, listOf(ABinding("1001", s1.tag, "04" + "cd".repeat(64))))
    val base = listOf(ARecord("sess-1", "1001", 1100), ARecord("sess-1", "1002", 1200))
    box.publishAttendance(att(base).seal(dcode))
    r = AdminSync.run(store); st = store.load()
    ok(r.newRecords == 2 && st.records.size == 2 && st.sessions.size == 1 && st.bindings.size == 1, "استيراد تلقائي: جلسة + سجلين + ربط جهاز")
    ok(st.sessions[0].doctorId == "D1" && st.bindings[0].doctorId == "D1", "نُسبت الجلسة والربط للدكتور الصحيح")
    r = AdminSync.run(store)
    ok(r.newRecords == 0 && store.load().records.size == 2, "نفس الملف مرة ثانية: لا يتكرر (بصمة)")
    box.publishAttendance(att(base).seal(dcode))   // نفس المحتوى بتشفير مختلف
    r = AdminSync.run(store)
    ok(r.newRecords == 0 && store.load().records.size == 2, "إعادة رفع بنفس المحتوى: لا تكرار (دمج idempotent)")
    box.publishAttendance(att(base + ARecord("sess-1", "1003", 1300)).seal(dcode))
    r = AdminSync.run(store)
    ok(r.newRecords == 1 && store.load().records.size == 3, "تسجيل جديد فقط يُضاف")
    box.publishAttendance(att(base).seal(Codes.random(rnd)))
    r = AdminSync.run(store)
    ok(r.errors.size == 1 && store.load().records.size == 3, "ملف مشفّر بكود آخر: خطأ مُبلَّغ، والبيانات سليمة")

    println("[5] حالات الاتصال")
    val offline = AdminSync.run(AdminStore(dir).also { it.update { s -> s.copy(relayUrl = "http://127.0.0.1:1") } })
    ok(offline.offline && offline.errors.isEmpty(), "لا اتصال => offline بلا استثناء ولا أخطاء مزعجة")
    store.update { it.copy(relayUrl = url) }
    ok(AdminState().effectiveRelay == "https://maggd-141d1-default-rtdb.europe-west1.firebasedatabase.app", "العنوان الافتراضي المخفي هو قاعدة maggd-141d1")
    ok(AdminState(relayUrl = "https://other.example").effectiveRelay == "https://other.example", "العنوان المخزَّن (نسخ قديمة) يُحترم إن وُجد")
    ok(StateJson.fromJson(StateJson.toJson(AdminState(majors = listOf("حاسب"), sectionNames = listOf("A")))).let { it.majors == listOf("حاسب") && it.sectionNames == listOf("A") }, "التخصصات والشعب تُحفظ وتُقرأ")
    ok(StateJson.fromJson("""{"v":1,"seq":0,"epoch":0,"courses":[],"students":[],"doctors":[],"bindings":[],"sessions":[],"records":[]}""").majors.isEmpty(), "حالة قديمة بلا تخصصات/شعب تُقرأ بسلام")

    println("[5b] عنوان خاطئ (404) => اكتشاف المنطقة تلقائيًا")
    val dead = args[1]
    RelayLocator.candidatesProvider = { _ -> listOf(deadUrl, url) }          // محاكاة: مناطق محتملة، واحدة ميتة وواحدة حيّة
    ok(Relay(dead).probe() == 404 && Relay(url).probe() == 401, "الخادم الميت 404 والحيّ 401 (القاعدة موجودة والقواعد تحمي الجذر)")
    ok(RelayLocator.find(dead) == url, "find: يتجاوز العنوان الميت ويجد الصحيح")
    ok(RelayLocator.dbName("https://maggd-141d1-default-rtdb.europe-west1.firebasedatabase.app/") == "maggd-141d1-default-rtdb" &&
        RelayLocator.dbName("https://maggd-141d1-default-rtdb.europe-west1.firebasedatabase.app") == "maggd-141d1-default-rtdb", "اسم القاعدة من أي صيغة عنوان")
    store.update { it.copy(relayUrl = deadUrl) }
    r = AdminSync.run(store)
    ok(r.relayError == null && !r.offline && store.load().relayUrl == url, "المسؤول: عنوان ميت => يكتشف الصحيح ويحفظه (${store.load().relayUrl == url})")
    ok(runCatching { Relay(dead).getString("m/x/y") }.exceptionOrNull().let { it is Relay.RelayException && it.code == 404 && it.friendly().contains("404") }, "خطأ 404 يُترجم لرسالة عربية مفهومة")
    ok(Relay.RelayException(401, "x").friendly().contains("Rules"), "خطأ 401 يشرح نشر Rules")
    RelayLocator.candidatesProvider = { _ -> listOf(deadUrl) }
    store.update { it.copy(relayUrl = deadUrl) }
    r = AdminSync.run(store)
    ok(r.relayError != null && r.relayError!!.contains("لا توجد قاعدة") && store.load().relayUrl == dead, "لا قاعدة في أي منطقة => رسالة واضحة واحدة بلا تلف")
    RelayLocator.candidatesProvider = { _ -> listOf(deadUrl, url) }
    store.update { it.copy(relayUrl = url) }
    AdminSync.run(store)


    println("[6] إلغاء ربط دكتور (كود جديد + صندوق جديد)")
    val oldBox = box; val newCode = Codes.random(rnd); val newMbox = Pairing.newMailbox(rnd)
    oldBox.wipe()
    store.update { s -> s.copy(doctors = s.doctors.map { it.copy(code = newCode, mailbox = newMbox, rosterHash = "", attHash = "", pushedEpoch = 0, ackEpoch = 0) }) }
    r = AdminSync.run(store)
    val nb = Mailbox(Relay(url), newMbox)
    ok(r.pushed == 1 && nb.rosterEpoch() == store.load().epoch, "يُنشر في الصندوق الجديد بإصدار أحدث (4)")
    ok(Roster.open(nb.fetchRoster()!!, newCode) != null && Roster.open(nb.fetchRoster()!!, dcode) == null, "يُفتح بالكود الجديد فقط")
    ok(oldBox.fetchRoster() == null, "الصندوق القديم فارغ: الهاتف القديم ينقطع")
    ok(store.load().records.size == 3, "سجلات الحضور السابقة محفوظة بعد إلغاء الربط")

    println("[7] تزامن الكتابة (واجهة + عامل خلفية) بلا ضياع تعديلات")
    val before = store.load().students.size
    val writer = launch(Dispatchers.Default) { repeat(30) { i -> store.update { s -> s.copy(students = s.students + student("9${i}00", "ط$i", listOf(1L), rnd)) } } }
    val syncs = (1..4).map { launch(Dispatchers.Default) { AdminSync.run(store) } }
    writer.join(); syncs.forEach { it.join() }
    AdminSync.run(store)
    val after = store.load()
    ok(after.students.size == before + 30, "30 طالبًا أُضيفوا أثناء 4 مزامنات متزامنة: لم يضع أي تعديل (${after.students.size - before}/30)")
    val last = Roster.open(nb.fetchRoster()!!, newCode)!!
    ok(last.students.size == after.students.size && nb.rosterEpoch() == after.doctors[0].pushedEpoch, "آخر كشف منشور يطابق الحالة النهائية")
    ok(File(dir, "admin_state.json").readText().let { runCatching { StateJson.fromJson(it) }.isSuccess }, "ملف الحالة سليم ويُقرأ")

    println("\nالنتيجة: نجح $passed، فشل $failed")
    if (failed > 0) System.exit(1)
}
