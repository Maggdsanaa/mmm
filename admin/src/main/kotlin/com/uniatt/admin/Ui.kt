package com.uniatt.admin

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.uniatt.core.Codes
import com.uniatt.core.Schedule
import kotlinx.coroutines.launch
import java.io.File

private val Green = Color(0xFF2E7D32)
private val Amber = Color(0xFFB26A00)

@Composable
private fun F(label: String, v: String, lines: Int = 1, on: (String) -> Unit) =
    OutlinedTextField(v, on, label = { Text(label) }, singleLine = lines == 1, minLines = lines,
        modifier = Modifier.fillMaxWidth())

private fun shareFile(ctx: Context, f: File, title: String) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(i, title))
}

private fun readUri(ctx: Context, uri: android.net.Uri): ByteArray? =
    try { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (_: Exception) { null }

@Composable
fun AdminApp(vm: AdminVM) {
    val msg by vm.message.collectAsState()
    MaterialTheme {
        AppBackground {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.fillMaxSize()) {
                    Main(vm)
                    msg?.let {
                        AlertDialog(onDismissRequest = { vm.message.value = null },
                            confirmButton = { TextButton(onClick = { vm.message.value = null }) { Text("حسنًا") } },
                            text = { SelectionContainer { Text(it) } })
                    }
                }
            }
        }
    }
}

@Composable
fun Main(vm: AdminVM) {
    var tab by remember { mutableIntStateOf(0) }
    val titles = listOf("المواد", "الطلاب", "الدكاترة", "التقارير")
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            NavigationBar {
                titles.forEachIndexed { i, t ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text("•") }, label = { Text(t) })
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {
            when (tab) { 0 -> CoursesTab(vm); 1 -> StudentsTab(vm); 2 -> DoctorsTab(vm); else -> ReportsTab(vm) }
        }
    }
}

@Composable
fun CoursesTab(vm: AdminVM) {
    val st by vm.state.collectAsState()
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var sec by remember { mutableStateOf("") }
    var slotFor by remember { mutableStateOf<Long?>(null) }
    var delCourse by remember { mutableStateOf<Course?>(null) }

    slotFor?.let { cid ->
        val c = st.courses.firstOrNull { it.id == cid }
        if (c != null) SlotDialog(c, onDismiss = { slotFor = null }) { day, a, b, room ->
            vm.addSlot(cid, day, a, b, room); slotFor = null
        }
    }
    delCourse?.let { c ->
        AlertDialog(
            onDismissRequest = { delCourse = null },
            confirmButton = { TextButton(onClick = { vm.deleteCourse(c.id); delCourse = null }) { Text("حذف", color = Color(0xFFC62828)) } },
            dismissButton = { TextButton(onClick = { delCourse = null }) { Text("إلغاء") } },
            text = { Text("حذف ${c.code}-${c.section} ${c.name} مع مواعيدها، وإزالتها من الطلاب والدكاترة؟ (سجلات الحضور السابقة تبقى)") }
        )
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("المواد والشعب والجدول", style = MaterialTheme.typography.headlineSmall)
            Text("أنت من يحدّد المواد والشعب ومواعيدها؛ تصل للدكتور تلقائيًا ولا يستطيع تعديلها من تطبيقه. أنشئ المواد أولًا قبل تسجيل الطلاب والدكاترة.",
                style = MaterialTheme.typography.bodySmall)
            F("رمز المادة (مثل CS101)", code) { code = it }
            F("اسم المادة (إعادة إدخال نفس الرمز والشعبة تحدّث الاسم)", name) { name = it }
            F("الشعبة (مثل A)", sec) { sec = it }
            Button(onClick = { vm.addCourse(code.trim(), name.trim(), sec.trim()); code = ""; name = ""; sec = "" },
                enabled = code.isNotBlank() && name.isNotBlank() && sec.isNotBlank()) { Text("إضافة") }
        }
        items(st.courses) { c ->
            val slots = st.slots.filter { it.courseId == c.id }.sortedWith(compareBy({ it.day }, { it.startMin }))
            val docs = st.doctors.filter { c.id in it.courseIds }.joinToString("، ") { it.name }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${c.code}-${c.section} — ${c.name}", style = MaterialTheme.typography.titleSmall)
                    Text(if (docs.isBlank()) "لا دكتور مسنَد بعد" else "الدكتور: $docs", style = MaterialTheme.typography.bodySmall)
                    if (slots.isEmpty()) Text("لا مواعيد بعد", style = MaterialTheme.typography.bodySmall, color = Amber)
                    slots.forEach { sl ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("• ${Schedule.DAYS[sl.day]} ${Schedule.fmt(sl.startMin)}–${Schedule.fmt(sl.endMin)}" +
                                if (sl.room.isNotBlank()) " (${sl.room})" else "")
                            TextButton(onClick = { vm.deleteSlot(sl.id) }) { Text("حذف") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { slotFor = c.id }) { Text("＋ موعد") }
                        TextButton(onClick = { delCourse = c }) { Text("حذف المادة", color = Color(0xFFC62828)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotDialog(c: Course, onDismiss: () -> Unit, onAdd: (Int, String, String, String) -> Unit) {
    var day by remember { mutableIntStateOf(0) }
    var from by remember { mutableStateOf("08:00") }
    var to by remember { mutableStateOf("09:30") }
    var room by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onAdd(day, from, to, room) }, enabled = from.isNotBlank() && to.isNotBlank()) { Text("إضافة") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
        title = { Text("موعد ${c.code}-${c.section}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Schedule.DAYS.forEachIndexed { i, d -> FilterChip(selected = day == i, onClick = { day = i }, label = { Text(d) }) }
                }
                F("من (مثل 08:00)", from) { from = it }
                F("إلى (مثل 09:30)", to) { to = it }
                F("القاعة (اختياري)", room) { room = it }
            }
        }
    )
}

@Composable
fun StudentsTab(vm: AdminVM) {
    val ctx = LocalContext.current
    val st by vm.state.collectAsState()
    val busy by vm.busy.collectAsState()
    var text by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { ctx.contentResolver.openOutputStream(it)?.use { o -> o.write(("\uFEFF" + vm.studentsCsv()).toByteArray()) } }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("الطلاب (${st.students.size})", style = MaterialTheme.typography.headlineSmall)
            Text("سطر لكل طالب:\nالرقم الجامعي,الاسم,الكلية,التخصص,المستوى,الشعبة,المواد\nالمواد بصيغة CS101-A وتُفصل بـ ؛ أو ;", style = MaterialTheme.typography.bodySmall)
            F("الطلاب (الصق من Excel أو اكتب)", text, lines = 4) { text = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.addStudents(text); text = "" }, enabled = text.isNotBlank() && !busy) {
                    Text(if (busy) "جارٍ التوليد…" else "إنشاء الأكواد")
                }
                OutlinedButton(onClick = { launcher.launch("student_codes.csv") }, enabled = st.students.isNotEmpty()) { Text("تصدير الأكواد") }
            }
            Text("سلّم كل طالب كوده (12 خانة). يكتبه مرة واحدة في تطبيقه، ثم يرتبط هاتفه بهويته عند أول تقريب من هاتف دكتوره.", style = MaterialTheme.typography.bodySmall)
        }
        items(st.students) { s ->
            val seen = st.bindings.any { it.studentId == s.studentId }
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${s.name} (${s.studentId})")
                        Text("الكود: ${Codes.format(s.code)}  ${if (seen) "✓ ظهر جهازه" else "⏳ لم يظهر بعد"}",
                            color = if (seen) Green else Color.Unspecified)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(onClick = { vm.resetStudent(s.studentId) }, enabled = !busy) { Text("كود جديد") }
                        TextButton(onClick = { vm.deleteStudent(s.studentId) }, enabled = !busy) { Text("حذف", color = Color(0xFFC62828)) }
                    }
                }
            }
        }
    }
}

private fun fmtTime(t: Long): String = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US).format(java.util.Date(t))

@Composable
fun DoctorsTab(vm: AdminVM) {
    val st by vm.state.collectAsState()
    val busy by vm.busy.collectAsState()
    val syncing by vm.syncing.collectAsState()
    val info by vm.syncInfo.collectAsState()
    var text by remember { mutableStateOf("") }
    var relay by remember(st.relayUrl) { mutableStateOf(st.relayUrl) }
    var qrFor by remember { mutableStateOf<String?>(null) }

    qrFor?.let { id ->
        val d = st.doctors.firstOrNull { it.doctorId == id }
        val payload = remember(id, st.relayUrl, d?.mailbox, d?.code) { vm.pairingText(id) }
        if (d != null && payload != null) AlertDialog(
            onDismissRequest = { qrFor = null },
            confirmButton = { TextButton(onClick = { qrFor = null }) { Text("تم") } },
            title = { Text("QR ${d.name}") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QrCode(payload, Modifier.size(260.dp))
                    Text("يفتح الدكتور تطبيقه ← تبويب «المواد» ← «مسح QR» ويصوّر هذا الرمز مرة واحدة فقط. بعدها يتحدّث كل شيء تلقائيًا.",
                        style = MaterialTheme.typography.bodySmall)
                    Text("⚠ الرمز يحوي مفتاح الدكتور — لا تصوّره لأحد غيره.", style = MaterialTheme.typography.bodySmall, color = Amber)
                }
            }
        )
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("الدكاترة (${st.doctors.size})", style = MaterialTheme.typography.headlineSmall)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("الاتصال بالإنترنت (مرة واحدة)", style = MaterialTheme.typography.titleSmall)
                    Text("لا تحتاج خادمًا: أنشئ قاعدة Firebase Realtime Database مجانية والصق عنوانها هنا. ما يمرّ عبرها مشفّر ولا تستطيع قراءته. التفاصيل في README.",
                        style = MaterialTheme.typography.bodySmall)
                    F("عنوان القاعدة (https://…firebaseio.com)", relay) { relay = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.setRelay(relay) }, enabled = relay.isNotBlank() && relay.trim().trimEnd('/') != st.relayUrl) { Text("حفظ") }
                        OutlinedButton(onClick = { vm.syncNow(manual = true) }, enabled = !syncing && st.relayUrl.isNotBlank()) {
                            Text(if (syncing) "جارٍ المزامنة…" else "مزامنة الآن")
                        }
                    }
                    if (info.isNotBlank()) Text(info, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("سطر لكل دكتور:\nمعرف الدكتور,الاسم,المواد (CS101-A;MATH1-B)", style = MaterialTheme.typography.bodySmall)
            F("الدكاترة", text, lines = 3) { text = it }
            Button(onClick = { vm.addDoctors(text); text = "" }, enabled = text.isNotBlank() && !busy) { Text("إضافة الدكاترة") }
            Text("لكل دكتور زر «عرض QR»: يصوّره الدكتور من هاتفك مرة واحدة، وبعدها تصله مواده وشعبه وطلابه وأي تعديل تلقائيًا، ويصلك حضوره تلقائيًا.",
                style = MaterialTheme.typography.bodySmall)
        }
        items(st.doctors) { d ->
            val n = st.students.count { s -> s.courseIds.any { it in d.courseIds } }
            val status = when {
                d.courseIds.isEmpty() -> "لا توجد مواد لهذا الدكتور"
                d.pushedEpoch == 0L -> "⏳ لم يُنشر كشفه بعد (يلزم عنوان القاعدة واتصال بالإنترنت)"
                d.ackEpoch == 0L -> "⏳ لم يمسح QR بعد (أو لم يتصل بالإنترنت)"
                d.ackEpoch < d.pushedEpoch -> "⏳ بانتظار وصول آخر تحديث إلى هاتفه"
                else -> "✓ مرتبط ومحدَّث"
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${d.name} (${d.doctorId})")
                    Text("${d.courseIds.size} مادة، $n طالب", style = MaterialTheme.typography.bodySmall)
                    Text(status, color = if (status.startsWith("✓")) Green else Color.Unspecified, style = MaterialTheme.typography.bodySmall)
                    if (d.lastPullAt > 0) Text("آخر حضور مستلم: ${fmtTime(d.lastPullAt)}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { qrFor = d.doctorId }, enabled = st.relayUrl.isNotBlank()) { Text("عرض QR") }
                        TextButton(onClick = { vm.resetDoctor(d.doctorId) }) { Text("إلغاء الربط (QR جديد)") }
                    }
                    TextButton(onClick = { vm.deleteDoctor(d.doctorId) }) { Text("حذف الدكتور", color = Color(0xFFC62828)) }
                }
            }
        }
    }
}

@Composable
fun ReportsTab(vm: AdminVM) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by vm.state.collectAsState()
    val busy by vm.busy.collectAsState()
    val syncing by vm.syncing.collectAsState()
    val info by vm.syncInfo.collectAsState()
    val summary = remember(st) { vm.courseSummary() }
    val conflicts = remember(st) { vm.conflicts() }
    var restoreBytes by remember { mutableStateOf<ByteArray?>(null) }
    var restoreCode by remember { mutableStateOf("") }

    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { restoreBytes = readUri(ctx, it) }
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { ctx.contentResolver.openOutputStream(it)?.use { o -> o.write(("\uFEFF" + vm.attendanceCsv()).toByteArray()) } }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("التقارير", style = MaterialTheme.typography.headlineSmall)
            Text("يصلك الحضور من الدكاترة تلقائيًا عند اتصال هاتفيكما بالإنترنت.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.syncNow(manual = true) }, enabled = !syncing && st.relayUrl.isNotBlank()) {
                    Text(if (syncing) "جارٍ المزامنة…" else "مزامنة الآن")
                }
                OutlinedButton(onClick = { csvLauncher.launch("attendance_report.csv") }, enabled = st.records.isNotEmpty()) { Text("تصدير CSV") }
            }
            if (info.isNotBlank()) Text(info, style = MaterialTheme.typography.bodySmall)
            if (summary.isEmpty()) Text("لا توجد مواد بعد.")
            summary.forEach { Text(it) }
            if (conflicts.isNotEmpty()) {
                Text("⚠ طلاب بجهازين مختلفين (قد يعني كودًا مشاركًا):", color = Amber, style = MaterialTheme.typography.titleSmall)
                conflicts.forEach { (s, docs) -> Text("• ${s.name} (${s.studentId}) — عند: ${docs.joinToString("، ")}", color = Amber) }
                Text("الحل: «كود جديد» للطالب من تبويب الطلاب (يصل الكشف المحدَّث لدكاترته تلقائيًا).", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Text("النسخ الاحتياطي", style = MaterialTheme.typography.titleMedium)
            Text("هذا الهاتف هو المرجع الوحيد؛ إن ضاع ضاعت الأكواد. خذ نسخة مشفّرة واحفظها خارج الهاتف، واحفظ كودها منفصلًا.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                scope.launch {
                    vm.backup()?.let { (f, pass) ->
                        vm.message.value = "كود النسخة الاحتياطية (لا تفقده، لا استعادة بدونه):\n${Codes.format(pass)}"
                        shareFile(ctx, f, "نسخة احتياطية")
                    }
                }
            }, enabled = !busy) { Text("إنشاء نسخة احتياطية مشفّرة") }
            OutlinedButton(onClick = { restorePicker.launch(arrayOf("*/*")) }, enabled = !busy) { Text("اختيار نسخة للاستعادة") }
            if (restoreBytes != null) {
                F("كود النسخة (12 خانة)", restoreCode) { restoreCode = it.take(20) }
                Button(onClick = { vm.restore(restoreBytes, restoreCode); restoreBytes = null; restoreCode = "" },
                    enabled = restoreCode.isNotBlank() && !busy) { Text("استعادة (تستبدل البيانات الحالية)") }
            }
        }
    }
}
