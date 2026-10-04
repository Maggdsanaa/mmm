package com.uniatt.admin

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("المواد والشعب", style = MaterialTheme.typography.headlineSmall)
            Text("أنشئ المواد أولًا قبل تسجيل الطلاب والدكاترة. كل البيانات تُحفظ على هذا الهاتف فقط.")
            F("رمز المادة (مثل CS101)", code) { code = it }
            F("اسم المادة", name) { name = it }
            F("الشعبة (مثل A)", sec) { sec = it }
            Button(onClick = { vm.addCourse(code.trim(), name.trim(), sec.trim()); code = ""; name = ""; sec = "" },
                enabled = code.isNotBlank() && name.isNotBlank() && sec.isNotBlank()) { Text("إضافة") }
        }
        items(st.courses) { Text("${it.code}-${it.section} — ${it.name}") }
    }
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
                    TextButton(onClick = { vm.resetStudent(s.studentId) }, enabled = !busy) { Text("كود جديد") }
                }
            }
        }
    }
}

@Composable
fun DoctorsTab(vm: AdminVM) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by vm.state.collectAsState()
    val busy by vm.busy.collectAsState()
    var text by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { ctx.contentResolver.openOutputStream(it)?.use { o -> o.write(("\uFEFF" + vm.doctorsCsv()).toByteArray()) } }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("الدكاترة (${st.doctors.size})", style = MaterialTheme.typography.headlineSmall)
            Text("سطر لكل دكتور:\nمعرف الدكتور,الاسم,المواد (CS101-A;MATH1-B)", style = MaterialTheme.typography.bodySmall)
            F("الدكاترة", text, lines = 3) { text = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.addDoctors(text); text = "" }, enabled = text.isNotBlank() && !busy) { Text("إنشاء الأكواد") }
                OutlinedButton(onClick = { launcher.launch("doctor_codes.csv") }, enabled = st.doctors.isNotEmpty()) { Text("تصدير") }
            }
            Text("لكل دكتور: ملف كشف مشفّر (أرسله بواتساب/إيميل) + كوده الخاص (أرسله في رسالة منفصلة). الملف لا يُفتح إلا بالكود.", style = MaterialTheme.typography.bodySmall)
        }
        items(st.doctors) { d ->
            val n = st.students.count { s -> s.courseIds.any { it in d.courseIds } }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${d.name} (${d.doctorId})")
                    SelectionContainer { Text("الكود: ${Codes.format(d.code)}") }
                    Text("${d.courseIds.size} مادة، $n طالب", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch { vm.exportRoster(d.doctorId)?.let { shareFile(ctx, it, "إرسال كشف ${d.name}") } }
                        }, enabled = !busy) { Text("تصدير الكشف") }
                        TextButton(onClick = { vm.resetDoctor(d.doctorId) }) { Text("كود جديد") }
                    }
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
    val summary = remember(st) { vm.courseSummary() }
    val conflicts = remember(st) { vm.conflicts() }
    var restoreBytes by remember { mutableStateOf<ByteArray?>(null) }
    var restoreCode by remember { mutableStateOf("") }

    val attPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importAttendance(readUri(ctx, it)) }
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { restoreBytes = readUri(ctx, it) }
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { ctx.contentResolver.openOutputStream(it)?.use { o -> o.write(("\uFEFF" + vm.attendanceCsv()).toByteArray()) } }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("التقارير", style = MaterialTheme.typography.headlineSmall)
            Text("استورد ملفات الحضور التي يرسلها لك الدكاترة (مشفّرة بأكوادهم).", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { attPicker.launch(arrayOf("*/*")) }, enabled = !busy) { Text("استيراد حضور دكتور") }
                OutlinedButton(onClick = { csvLauncher.launch("attendance_report.csv") }, enabled = st.records.isNotEmpty()) { Text("تصدير CSV") }
            }
            if (summary.isEmpty()) Text("لا توجد مواد بعد.")
            summary.forEach { Text(it) }
            if (conflicts.isNotEmpty()) {
                Text("⚠ طلاب بجهازين مختلفين (قد يعني كودًا مشاركًا):", color = Amber, style = MaterialTheme.typography.titleSmall)
                conflicts.forEach { (s, docs) -> Text("• ${s.name} (${s.studentId}) — عند: ${docs.joinToString("، ")}", color = Amber) }
                Text("الحل: «كود جديد» للطالب من تبويب الطلاب ثم أعد تصدير كشوف دكاترته.", style = MaterialTheme.typography.bodySmall)
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
