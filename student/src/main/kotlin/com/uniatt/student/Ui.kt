package com.uniatt.student

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.uniatt.core.Codes
import com.uniatt.core.Kdf
import com.uniatt.core.QrProtocol
import com.uniatt.core.Status
import com.uniatt.core.StudentProfile
import com.uniatt.core.fmtTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFC62828)

fun nfcState(ctx: Context): Pair<Boolean, String> {
    val ad = NfcAdapter.getDefaultAdapter(ctx) ?: return false to "هذا الجهاز لا يدعم NFC"
    if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION))
        return false to "هذا الجهاز لا يدعم محاكاة البطاقة (HCE)"
    if (!ad.isEnabled) return false to "NFC مغلق — فعّله من الإعدادات"
    return true to "جاهز ✓ قرّب هاتفك من هاتف الدكتور"
}

@Composable
fun StudentApp(store: StudentStore, tick: Int) {
    var hasCode by remember { mutableStateOf(store.credentials() != null) }
    MaterialTheme {
        AppBackground {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                if (!hasCode) Activation(store) { hasCode = true }
                else MainScreen(store, tick) { store.clearAll(); hasCode = false }
            }
        }
    }
}

/** إدخال الكود مرة واحدة: كله محلي، لا إنترنت ولا خادم. */
@Composable
fun Activation(store: StudentStore, onDone: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(48.dp))
        Text("تفعيل التطبيق", style = MaterialTheme.typography.headlineMedium)
        Text("اكتب الكود الذي سلّمته لك الإدارة (12 خانة، مثل AB12-CD34-EF56). تكتبه مرة واحدة فقط ولا يلزم إنترنت.")
        OutlinedTextField(
            input, { input = it.take(20); error = null },
            label = { Text("كود الطالب") }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth()
        )
        error?.let { Text(it, color = Red) }
        Button(
            onClick = {
                val code = Codes.normalize(input)
                if (code == null) { error = "الكود غير صحيح — راجع الخانات وأعد الكتابة"; return@Button }
                busy = true
                scope.launch {
                    val ok = withContext(Dispatchers.Default) {
                        runCatching {
                            val key = Kdf.studentKey(code)
                            DeviceKey.publicRaw()                 // إنشاء مفتاح الجهاز الآن ليكون أول تقريب سريعًا
                            store.saveCredentials(Codes.tag(code), key)
                        }.isSuccess
                    }
                    busy = false
                    if (ok) onDone() else error = "تعذّر التفعيل على هذا الجهاز"
                }
            },
            enabled = input.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "جارٍ التفعيل…" else "تفعيل") }
        Text(
            "بعد التفعيل قرّب هاتفك من هاتف الدكتور في أول محاضرة: يرتبط هاتفك بهويتك وتصلك بياناتك ومواد الدكتور تلقائيًا.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun MainScreen(store: StudentStore, tick: Int, onReset: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val version by ResultBus.profileVersion.collectAsState()
    val profile = remember(version) { store.profile() }
    val titles = listOf("الرئيسية", "بياناتي", "المواد", "السجل", "إعدادات")
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            NavigationBar {
                titles.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i }, icon = { Text("•") },
                        label = { Text(t, maxLines = 1, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {
            when (tab) {
                0 -> Home(tick, profile)
                1 -> ProfileView(profile)
                2 -> CoursesView(profile)
                3 -> HistoryView(store)
                else -> SettingsView(store, onReset)
            }
        }
    }
}

private const val WAITING = "لم تصلك بياناتك بعد. قرّب هاتفك من هاتف الدكتور في أول محاضرة وستظهر هنا تلقائيًا."

@Composable
fun Home(tick: Int, profile: StudentProfile?) {
    val ctx = LocalContext.current
    val (ok, msg) = remember(tick) { nfcState(ctx) }
    val last by ResultBus.last.collectAsState()
    val contact by ResultBus.contact.collectAsState()
    val store = remember { StudentStore(ctx) }
    var auto by remember { mutableStateOf(store.autoBle && blePermissionsGranted(ctx)) }
    var autoMsg by remember { mutableStateOf<String?>(null) }
    val bleState by ResultBus.ble.collectAsState()
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        if (blePermissionsGranted(ctx)) { store.autoBle = true; auto = true; autoMsg = null; AutoAttendService.start(ctx) }
        else { store.autoBle = false; auto = false; autoMsg = "لم تُمنح صلاحية البلوتوث — اسمح بها من إعدادات التطبيق ليعمل التسجيل التلقائي." }
    }
    var qrReply by remember { mutableStateOf<String?>(null) }
    var qrError by remember { mutableStateOf<String?>(null) }
    val qrScope = rememberCoroutineScope()
    val challengeScanner = rememberLauncherForActivityResult(ScanContract()) { res ->
        val t = res.contents
        if (t != null) {
            val req = QrProtocol.parseChallenge(t)
            if (req == null) {
                qrError = "هذا ليس رمز الدكتور. اطلب منه تفعيل «بديل NFC: تسجيل بالـQR» ثم امسح الرمز الذي يظهر في هاتفه."
            } else qrScope.launch {
                val text = withContext(Dispatchers.Default) { runCatching { StudentQr.reply(ctx, req) }.getOrNull() }
                if (text == null) qrError = "تعذّر تجهيز الإجابة على هذا الجهاز" else { qrError = null; qrReply = text }
            }
        }
    }
    qrReply?.let { txt ->
        AlertDialog(
            onDismissRequest = { qrReply = null },
            confirmButton = { TextButton(onClick = { qrReply = null }) { Text("تم") } },
            title = { Text("اعرض هذا الرمز للدكتور") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QrCode(txt, Modifier.size(300.dp))
                    Text("وجّه شاشتك نحو كاميرا هاتف الدكتور وأبقِها ثابتة. الرمز صالح لدقيقة واحدة؛ إن انتهت امسح رمز الدكتور من جديد.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        )
    }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { nowMs = System.currentTimeMillis(); delay(1000) } }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(profile?.let { "أهلًا ${it.name}" } ?: "تسجيل الحضور", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(msg, color = if (ok) Green else Red)
                if (!ok) Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }) { Text("فتح إعدادات NFC") }
                Text("أبقِ التطبيق مفتوحًا والشاشة مضاءة، وقرّب ظهر الهاتف من هاتف الدكتور حتى تشعر بالاهتزاز.")
                if (profile == null) Text(WAITING, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = auto, onCheckedChange = { on ->
                        if (on) permLauncher.launch(blePermissions())
                        else { store.autoBle = false; auto = false; AutoAttendService.stop(ctx) }
                    })
                    Text("التسجيل التلقائي بالبلوتوث (بلا أي لمسة)", style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    "يعمل في الخلفية بإشعار دائم: فور بدء الدكتور للجلسة يتصل هاتفك به ويُسجَّل حضورك تلقائيًا. أبقِ البلوتوث مفعّلًا. قد يستغرق ذلك حتى نصف دقيقة في القاعات الكبيرة." +
                        (if (auto && bleState.isNotBlank()) "\nالحالة: $bleState" else ""),
                    style = MaterialTheme.typography.bodySmall
                )
                autoMsg?.let { Text(it, color = Red, style = MaterialTheme.typography.bodySmall) }
                Button(onClick = {
                    qrError = null
                    challengeScanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("امسح الرمز المعروض على هاتف الدكتور").setBeepEnabled(false).setOrientationLocked(false))
                }, modifier = Modifier.fillMaxWidth()) { Text("تسجيل بالـQR (إن لم يعمل NFC)") }
                qrError?.let { Text(it, color = Red, style = MaterialTheme.typography.bodySmall) }
                Text("جهازك: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} — Android ${android.os.Build.VERSION.RELEASE}", style = MaterialTheme.typography.bodySmall)
                var tips by remember { mutableStateOf(false) }
                TextButton(onClick = { tips = !tips }) { Text(if (tips) "إخفاء إعدادات الهاتف" else "التقريب لا يعمل؟ افحص إعدادات هاتفك") }
                if (tips) Text(
                    "• افتح إعدادات NFC وتأكد من تفعيل «محاكاة البطاقة / Card emulation / الدفع باللمس» بوضع HCE (وليس SIM أو eSE) — في هواوي/هونر/شاومي/أوبو/ريلمي/فيفو يوجد خيار «Card emulation mode» أو «HCE Wallet».\n" +
                    "• في سامسونج: الإعدادات ← الاتصالات ← NFC والدفع بلا تلامس، وفعّل NFC.\n" +
                    "• أوقف «طلب فتح القفل لاستخدام NFC» إن وُجد، وأبقِ الشاشة مضاءة والتطبيق مفتوحًا.\n" +
                    "• قرّب ظهر هاتفك من ظهر هاتف الدكتور وحرّكه ببطء حتى تجد موضع الهوائي (غالبًا منتصف الظهر أو أعلاه)، وانزع الجراب إن كان سميكًا أو فيه معدن.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    contact?.let { "آخر اتصال من هاتف الدكتور: قبل ${((nowMs - it.time) / 1000).coerceAtLeast(0)} ثانية — ${it.step}" }
                        ?: "لم يصل أي اتصال من هاتف دكتور بعد (إن قرّبت ولم يظهر شيء هنا فالهاتفان لم يتلامسا من منطقة الهوائي)",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        last?.let {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(Status.arabic(it.status), style = MaterialTheme.typography.titleLarge,
                        color = if (Status.ok(it.status)) Green else Red)
                    Text(fmtTime(it.time))
                }
            }
        }
    }
}

@Composable
fun ProfileView(p: StudentProfile?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("بيانات الطالب", style = MaterialTheme.typography.headlineSmall)
        if (p == null) { Text(WAITING); return }
        listOf("الاسم" to p.name, "الرقم الجامعي" to p.studentId, "الكلية" to p.faculty,
            "التخصص" to p.major, "المستوى" to p.level, "الشعبة" to p.section)
            .forEach { Text("${it.first}: ${it.second}") }
    }
}

@Composable
fun CoursesView(p: StudentProfile?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("المواد", style = MaterialTheme.typography.headlineSmall)
        if (p == null) { Text(WAITING); return }
        if (p.courses.isEmpty()) Text("لا توجد مواد مسجلة")
        Text("تظهر هنا مواد كل دكتور قرّبت هاتفك منه.", style = MaterialTheme.typography.bodySmall)
        p.courses.forEach { Card(Modifier.fillMaxWidth()) { Text(it, Modifier.padding(16.dp)) } }
    }
}

@Composable
fun HistoryView(store: StudentStore) {
    val last by ResultBus.last.collectAsState()
    val items = remember(last) { store.history() }
    Column {
        Text("سجل الحضور", style = MaterialTheme.typography.headlineSmall)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(Status.arabic(it.status), color = if (Status.ok(it.status)) Green else Red)
                        Text(fmtTime(it.time), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsView(store: StudentStore, onReset: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    val tag = remember { store.credentials()?.tag ?: "" }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
        Text("إعدادات", style = MaterialTheme.typography.headlineSmall)
        Text("رمز الجهاز: $tag", style = MaterialTheme.typography.bodySmall)
        Text("ارتباط الكود بهذا الجهاز يتم عند أول تقريب من هاتف الدكتور. إن غيّرت هاتفك أو فقدته اطلب كودًا جديدًا من الإدارة.")
        OutlinedButton(onClick = { confirm = true }) { Text("إعادة إدخال الكود / مسح البيانات") }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        confirmButton = { TextButton(onClick = { confirm = false; onReset() }) { Text("مسح") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("إلغاء") } },
        text = { Text("سيُمسح الكود وبياناتك وسجلك من هذا الهاتف. لن تستطيع تسجيل الحضور حتى تُدخل كودك من جديد.") }
    )
}
