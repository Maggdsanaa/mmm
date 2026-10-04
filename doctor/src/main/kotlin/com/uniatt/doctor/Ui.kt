package com.uniatt.doctor

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.uniatt.core.fmtTime
import kotlinx.coroutines.launch
import java.io.File

private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFC62828)

@Composable
private fun F(label: String, v: String, pw: Boolean = false, on: (String) -> Unit) =
    OutlinedTextField(v, on, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (pw) PasswordVisualTransformation() else VisualTransformation.None)

private fun shareFile(ctx: Context, f: File, title: String) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(i, title))
}

@Composable
fun DoctorApp(vm: DoctorVM, nfcInfo: () -> Pair<Boolean, String>) {
    val logged by vm.loggedIn.collectAsState()
    val msg by vm.message.collectAsState()
    MaterialTheme {
        AppBackground {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.fillMaxSize()) {
                    if (!logged) LoginScreen(vm) else MainScreen(vm, nfcInfo)
                    msg?.let {
                        AlertDialog(onDismissRequest = { vm.message.value = null },
                            confirmButton = { TextButton(onClick = { vm.message.value = null }) { Text("حسنًا") } },
                            text = { Text(it) })
                    }
                }
            }
        }
    }
}

@Composable
fun LoginScreen(vm: DoctorVM) {
    val has = remember { vm.hasAccount() }
    var pin by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Spacer(Modifier.height(48.dp))
        Text(if (has) "تسجيل الدخول" else "إنشاء الرمز السري", style = MaterialTheme.typography.headlineMedium)
        if (!has) Text("رمز يقفل التطبيق على هذا الهاتف (4 خانات على الأقل). مواد الدكتور وطلابه تصل من ملف الكشف.")
        F("الرمز السري", pin, pw = true) { pin = it }
        Button(
            onClick = { if (has) vm.login(pin) else vm.register(pin) },
            enabled = pin.length >= 4,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (has) "دخول" else "إنشاء") }
    }
}

@Composable
fun MainScreen(vm: DoctorVM, nfcInfo: () -> Pair<Boolean, String>) {
    var tab by remember { mutableIntStateOf(0) }
    val titles = listOf("المواد", "مباشر", "الطلاب", "السجل", "التقارير")
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            NavigationBar {
                titles.forEachIndexed { i, t ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text("•") },
                        label = { Text(t, maxLines = 1, style = MaterialTheme.typography.labelSmall) })
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {
            when (tab) {
                0 -> CoursesScreen(vm)
                1 -> LiveScreen(vm, nfcInfo)
                2 -> StudentsScreen(vm)
                3 -> HistoryScreen(vm)
                else -> ReportsScreen(vm)
            }
        }
    }
}

@Composable
fun SelectedCourse(vm: DoctorVM): CourseSection? {
    val courses by vm.courses.collectAsState()
    val sel by vm.selectedCourseId.collectAsState()
    return courses.firstOrNull { it.id == sel }
}

@Composable
fun CoursesScreen(vm: DoctorVM) {
    val ctx = LocalContext.current
    val courses by vm.courses.collectAsState()
    val sel by vm.selectedCourseId.collectAsState()
    val linked by vm.linked.collectAsState()
    val importing by vm.importing.collectAsState()
    val pending by vm.pendingFile.collectAsState()
    val hint by vm.pendingHint.collectAsState()
    var code by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val bytes = try { ctx.contentResolver.openInputStream(it)?.use { s -> s.readBytes() } } catch (_: Exception) { null }
            vm.pickRoster(bytes)
        }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("كشف الطلاب (ملف من الإدارة)", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (linked) "✓ ${vm.doctorName} — إصدار الكشف ${vm.epoch}" else "لم يُستورد كشف بعد. استلم الملف من الإدارة (واتساب/إيميل) وكودك الخاص، ثم اخترهما هنا. لا يلزم إنترنت.",
                        color = if (linked) Green else Color.Unspecified
                    )
                    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                        Text(if (linked) "استيراد كشف محدَّث" else "اختيار ملف الكشف")
                    }
                    if (pending != null) {
                        Text("الملف المختار للدكتور: ${hint ?: "؟"}", style = MaterialTheme.typography.bodySmall)
                        F("كود الدكتور (12 خانة)", code) { code = it.take(20) }
                        Button(onClick = { vm.importRoster(code) }, enabled = code.isNotBlank() && !importing) {
                            Text(if (importing) "جارٍ الفتح…" else "استيراد")
                        }
                    }
                }
            }
            Text("المواد والشعب", style = MaterialTheme.typography.headlineSmall)
            if (courses.isEmpty()) Text("لا توجد مواد بعد.")
            else Text("اضغط على مادة لاختيارها:")
        }
        items(courses) { c ->
            Card(Modifier.fillMaxWidth().clickable { vm.selectedCourseId.value = c.id },
                colors = CardDefaults.cardColors(
                    containerColor = if (c.id == sel) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant)) {
                Text("${c.code} — ${c.name} / شعبة ${c.section}", Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
fun LiveScreen(vm: DoctorVM, nfcInfo: () -> Pair<Boolean, String>) {
    val active by vm.active.collectAsState()
    val events by vm.events.collectAsState()
    val live by vm.liveAttendance.collectAsState()
    val course = SelectedCourse(vm)
    var minutes by remember { mutableStateOf("15") }
    val (ok, msg) = nfcInfo()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("الحضور المباشر", style = MaterialTheme.typography.headlineSmall)
        Text(msg, color = if (ok) Green else Red)
        if (active == null) {
            Text(course?.let { "المادة: ${it.name} / شعبة ${it.section}" } ?: "اختر مادة من تبويب «المواد»")
            F("مدة الجلسة (دقائق)", minutes) { minutes = it.filter(Char::isDigit) }
            Button(onClick = { vm.startSession(course!!, minutes.toIntOrNull()?.coerceIn(1, 240) ?: 15) },
                enabled = course != null && ok, modifier = Modifier.fillMaxWidth()) { Text("بدء الحضور") }
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("وضع استقبال الحضور — قرّبوا الهواتف", style = MaterialTheme.typography.titleMedium)
                    Text("المادة: ${active!!.course.name} | حضر: ${live.size}")
                    Text("تنتهي: ${fmtTime(active!!.endsAt)}")
                }
            }
            OutlinedButton(onClick = { vm.endSession() }, modifier = Modifier.fillMaxWidth()) { Text("إنهاء الجلسة") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(events) { Text(it.text, color = if (it.ok) Green else Red) }
        }
    }
}

@Composable
fun StudentsScreen(vm: DoctorVM) {
    val students by vm.students.collectAsState()
    val course = SelectedCourse(vm)
    val bound = students.count { it.publicKeyHex != null }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("قائمة الطلاب" + (course?.let { " — ${it.name}" } ?: ""), style = MaterialTheme.typography.headlineSmall)
        Text("العدد: ${students.size} — ارتبطت أجهزتهم: $bound", style = MaterialTheme.typography.bodySmall)
        Text("يرتبط جهاز الطالب بهويته تلقائيًا عند أول تقريب ناجح.", style = MaterialTheme.typography.bodySmall)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(students) {
                val b = it.publicKeyHex != null
                Text("${if (b) "✓" else "⏳"} ${it.studentId} — ${it.name} (${it.major} / ${it.level})",
                    color = if (b) Green else Color.Unspecified)
            }
        }
    }
}

@Composable
fun HistoryScreen(vm: DoctorVM) {
    val sessions by vm.sessions.collectAsState()
    val open by vm.historySession.collectAsState()
    val att by vm.historyAttendance.collectAsState()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Text("سجل المحاضرات", style = MaterialTheme.typography.headlineSmall) }
        items(sessions) { s ->
            Card(Modifier.fillMaxWidth().clickable { vm.historySession.value = if (open == s.sessionId) null else s.sessionId }) {
                Column(Modifier.padding(12.dp)) {
                    Text(fmtTime(s.startedAt))
                    Text("المدة: ${s.durationMin} دقيقة", style = MaterialTheme.typography.bodySmall)
                    if (open == s.sessionId) {
                        Text("الحاضرون: ${att.size}")
                        att.forEach { Text("• ${it.studentId} ${it.studentName} — ${fmtTime(it.timestamp)}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

@Composable
fun ReportsScreen(vm: DoctorVM) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rows by vm.report.collectAsState()
    val sel by vm.selectedCourseId.collectAsState()
    val linked by vm.linked.collectAsState()
    val course = SelectedCourse(vm)
    LaunchedEffect(sel) { vm.loadReport() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { ctx.contentResolver.openOutputStream(it)?.use { o -> o.write(("\uFEFF" + vm.csv(rows)).toByteArray()) } }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("تقارير الحضور" + (course?.let { " — ${it.name}" } ?: ""), style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.loadReport() }) { Text("تحديث") }
            Button(onClick = { launcher.launch("attendance_${course?.code ?: "report"}.csv") }, enabled = rows.isNotEmpty()) { Text("تصدير CSV") }
        }
        OutlinedButton(
            onClick = {
                scope.launch {
                    val f = vm.buildAttendanceExport()
                    if (f != null) shareFile(ctx, f, "إرسال الحضور للإدارة") else vm.message.value = "استورد كشف الإدارة أولًا"
                }
            },
            enabled = linked, modifier = Modifier.fillMaxWidth()
        ) { Text("إرسال الحضور للإدارة (ملف مشفّر)") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(rows) {
                val pct = if (it.total == 0) 0 else it.attended * 100 / it.total
                Text("${it.name}: ${it.attended}/${it.total} ($pct%)")
            }
        }
    }
}
