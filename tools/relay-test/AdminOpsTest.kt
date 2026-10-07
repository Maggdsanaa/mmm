import com.uniatt.admin.*
import com.uniatt.core.*

var passed = 0; var failed = 0
fun ok(c: Boolean, name: String) { if (c) { passed++; println("  PASS  $name") } else { failed++; println("  FAIL  $name") } }

fun main() {
    var s = AdminState()
    println("[1] الإعدادات: التخصصات والشعب")
    s = AdminOps.addMajor(s, " علوم الحاسب ").first
    ok(s.majors == listOf("علوم الحاسب"), "إضافة تخصص مع تنظيف المسافات")
    ok(AdminOps.addMajor(s, "علوم الحاسب").second != null && AdminOps.addMajor(s, "").second != null, "رفض التكرار والفراغ")
    s = AdminOps.addMajor(s, "نظم المعلومات").first
    s = AdminOps.addSectionName(s, "A").first; s = AdminOps.addSectionName(s, "B").first
    ok(s.sectionNames == listOf("A", "B"), "إضافة شعبتين")
    ok(AdminOps.addSectionName(s, "a").second != null, "الشعبة a مكررة (لا فرق بين الحروف)")
    ok(AdminOps.addSectionName(s, "A-1").second != null, "الشعبة لا تحتوي على - (حتى لا تتلف صيغة CS101-A)")
    ok(AdminOps.removeMajor(s, "نظم المعلومات").majors == listOf("علوم الحاسب"), "حذف تخصص")

    println("[2] المواد بشعبة من القائمة")
    var r = AdminOps.addCourse(s, "CS101", "برمجة", "")
    ok(r.second != null, "رفض مادة بلا شعبة")
    r = AdminOps.addCourse(s, "CS101", "برمجة", "A"); s = r.first
    r = AdminOps.addCourse(s, "CS101", "برمجة", "B"); s = r.first
    r = AdminOps.addCourse(s, "MATH1", "رياضيات", "A"); s = r.first
    ok(s.courses.map { "${it.code}-${it.section}" } == listOf("CS101-A", "CS101-B", "MATH1-A"), "ثلاث مواد/شعب بمعرّفات مختلفة")
    ok(AdminOps.addCourse(s, "CS101", "برمجة", "A").second?.msg == "المادة/الشعبة موجودة", "تكرار نفس الاسم يُرفض")
    val renamed = AdminOps.addCourse(s, "CS101", "برمجة 1", "A")
    ok(renamed.second == null && renamed.first.courses[0].name == "برمجة 1" && renamed.first.courses.size == 3, "نفس الرمز والشعبة باسم جديد = تحديث الاسم")
    ok(AdminOps.addCourse(s, "CS-1", "x", "A").second != null, "رمز المادة بلا -")
    val cs101a = s.courses[0].id; val cs101b = s.courses[1].id; val math = s.courses[2].id

    println("[3] الدكاترة بالاسم + مواد من قائمة")
    var n = 0
    val mk = { n++; "CODE$n" }
    val (s1, d1) = AdminOps.saveDoctor(s, null, "  د. خالد  ", listOf(cs101a, math, math, 999L), mk, { "mb1" })
    ok(d1 != null && d1.name == "د. خالد" && d1.doctorId.startsWith("D"), "معرّف يُولَّد تلقائيًا ولا يكتبه المسؤول: ${d1?.doctorId}")
    ok(d1!!.courseIds == listOf(cs101a, math), "المواد من القائمة: مكرر وغير موجود يُهملان")
    ok(d1.mailbox == "mb1" && d1.code == "CODE1", "كود وصندوق بريد يُولَّدان")
    val (s2, d2) = AdminOps.saveDoctor(s1, null, "د. سارة", listOf(cs101b), mk, { "mb2" })
    ok(d2!!.doctorId != d1.doctorId && s2.doctors.size == 2, "دكتوران بمعرّفين مختلفين")
    val (s3, d1e) = AdminOps.saveDoctor(s2, d1.doctorId, "د. خالد الشهري", listOf(cs101a, cs101b, math), mk, { "zzz" })
    ok(d1e!!.name == "د. خالد الشهري" && d1e.courseIds.size == 3 && d1e.code == d1.code && d1e.mailbox == "mb1", "تعديل الاسم والمواد يُبقي كوده وصندوقه (لا حاجة لمسح QR من جديد)")
    ok(AdminOps.saveDoctor(s3, null, "   ", emptyList(), mk, { "x" }).second == null, "اسم فارغ مرفوض")
    ok(AdminOps.saveDoctor(s3, "D-none", "x", emptyList(), mk, { "x" }).second == null, "تعديل دكتور غير موجود")

    println("[4] الطلاب بالاسم والتخصص")
    val (s4, e1) = AdminOps.addStudent(s3, "", "محمد أحمد", "علوم الحاسب", "A", "", listOf(cs101a), "CODEA", "KEYA")
    ok(e1 == null && s4.students[0].studentId.startsWith("S") && s4.students[0].major == "علوم الحاسب" && s4.students[0].section == "A", "رقم داخلي يُولَّد + تخصص + شعبة")
    val (s5, e2) = AdminOps.addStudent(s4, "", "سارة علي", "علوم الحاسب", "B", "", listOf(cs101b), "CODEB", "KEYB")
    ok(e2 == null && s5.students[0].studentId != s5.students[1].studentId, "طالبان بلا رقم جامعي لهما رقمان داخليان مختلفان")
    val (s6, e3) = AdminOps.addStudent(s5, "20241001", "خالد", "", "A", "3", listOf(cs101a), "CODEC", "KEYC")
    ok(e3 == null && s6.students.last().studentId == "20241001" && s6.students.last().level == "3", "رقم جامعي مكتوب يُحترم")
    ok(AdminOps.addStudent(s6, "20241001", "آخر", "", "", "", emptyList(), "C", "K").second != null, "رقم جامعي مكرر مرفوض")
    ok(AdminOps.addStudent(s6, "", "  ", "", "", "", emptyList(), "C", "K").second != null, "اسم فارغ مرفوض")
    val sid = s6.students[0].studentId
    val (s7, e4) = AdminOps.editStudent(s6, sid, "محمد أحمد علي", "نظم المعلومات", "B", "2", listOf(cs101b, math))
    val ed = s7.students.first { it.studentId == sid }
    ok(e4 == null && ed.name == "محمد أحمد علي" && ed.major == "نظم المعلومات" && ed.section == "B" && ed.courseIds == listOf(cs101b, math), "تعديل الاسم والتخصص والشعبة والمواد")
    ok(ed.code == "CODEA" && ed.keyHex == "KEYA", "التعديل لا يغيّر كود الطالب (فلا يُلغى ربط جهازه)")
    ok(AdminOps.editStudent(s7, "nope", "x", "", "", "", emptyList()).second != null, "تعديل طالب غير موجود")

    println("[5] الكشف الذي يصل الدكتور يعكس ذلك")
    val roster = AdminSync.buildRoster(s7, s7.doctors[0], 1)
    ok(roster.doctorName == "د. خالد الشهري" && roster.courses.size == 3 && roster.students.size == 3, "اسم الدكتور وموادّه الثلاث وطلابه الثلاثة")
    val rs = roster.students.first { it.studentId == sid }
    ok(rs.name == "محمد أحمد علي" && rs.major == "نظم المعلومات" && rs.courseIds.toSet() == setOf(cs101b, math), "الطالب بتخصصه ومواده المحدَّثة")

    println("\nالنتيجة: نجح $passed، فشل $failed")
    if (failed > 0) System.exit(1)
}
