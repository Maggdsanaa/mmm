package com.maggd.math1

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.random.Random

sealed class Screen {
    object Splash : Screen()
    object Home : Screen()
    object About : Screen()
    data class LessonS(val lesson: Lesson) : Screen()
    data class QuizS(val lesson: Lesson?) : Screen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme { App() }
            }
        }
    }
}

@Composable
fun DeveloperBanner() {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.developer_credit),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            textAlign = TextAlign.Center,
            modifier = Modifier.statusBarsPadding().padding(vertical = 6.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    var screen by remember { mutableStateOf<Screen>(Screen.Splash) }
    fun back() {
        screen = when (val s = screen) {
            is Screen.QuizS -> if (s.lesson != null) Screen.LessonS(s.lesson) else Screen.Home
            else -> Screen.Home
        }
    }
    BackHandler(enabled = screen != Screen.Home && screen != Screen.Splash) { back() }

    if (screen == Screen.Splash) {
        SplashScreen { screen = Screen.Home }
        return
    }
    val title = when (val s = screen) {
        is Screen.LessonS -> s.lesson.title
        is Screen.QuizS -> "تدريب: ${s.lesson?.title ?: "شامل"}"
        Screen.About -> "حول التطبيق"
        else -> stringResource(R.string.app_name)
    }
    Scaffold(topBar = {
        Column {
            DeveloperBanner()
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { if (screen != Screen.Home) TextButton(onClick = { back() }) { Text("رجوع") } },
                actions = { if (screen == Screen.Home) TextButton(onClick = { screen = Screen.About }) { Text("حول") } }
            )
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (val s = screen) {
                Screen.Home -> HomeScreen({ screen = Screen.LessonS(it) }, { screen = Screen.QuizS(null) })
                Screen.About -> AboutScreen()
                is Screen.LessonS -> LessonScreen(s.lesson) { screen = Screen.QuizS(s.lesson) }
                is Screen.QuizS -> QuizScreen(s.lesson)
                Screen.Splash -> {}
            }
        }
    }
}

@Composable
fun SplashScreen(onDone: () -> Unit) {
    LaunchedEffect(Unit) { delay(1500); onDone() }
    Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.developer_credit), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun AboutScreen() {
    Card(Modifier.padding(16.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text("الإصدار ${BuildConfig.VERSION_NAME}")
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.developer_credit))
            Spacer(Modifier.height(8.dp))
            Text("المحتوى: ${Content.UNIT_TITLE}. يعمل التطبيق دون إنترنت.")
            Text("عدد أسئلة بنك الملزمة: ${Bank.all.size}")
        }
    }
}

@Composable
fun HomeScreen(onLesson: (Lesson) -> Unit, onQuiz: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(Content.UNIT_TITLE, style = MaterialTheme.typography.titleLarge) }
        item { Button(onClick = onQuiz, modifier = Modifier.fillMaxWidth()) { Text("تدريب شامل على كل الدروس") } }
        items(Content.lessons) { l ->
            Card(Modifier.fillMaxWidth().clickable { onLesson(l) }) {
                Text(l.title, Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
fun SectionBar(title: String) {
    Surface(color = MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Text(title, Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun LessonScreen(l: Lesson, onQuiz: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SectionBar(l.title) }
        item { MathText(l.summary) }
        item { Text("القوانين والتعريفات:", fontWeight = FontWeight.Bold, color = primary) }
        items(l.laws) {
            Card(Modifier.fillMaxWidth(), border = BorderStroke(1.5.dp, primary),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                MathText(it, Modifier.padding(12.dp), bold = true)
            }
        }
        item { Text("أمثلة:", fontWeight = FontWeight.Bold, color = primary) }
        itemsIndexed(l.examples) { i, e ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("مثال ${i + 1}:", fontWeight = FontWeight.Bold, color = primary)
                    MathText(e.q)
                    Text("الحل:", fontWeight = FontWeight.Bold)
                    val parts = e.a.split(" ⟹ ")
                    parts.forEachIndexed { j, p -> MathText(if (j == parts.lastIndex) "∴ $p" else "❖ $p") }
                    Quiz.parseIntervals(e.a)?.takeIf { it.isNotEmpty() }?.let { NumberLine(it) }
                }
            }
        }
        if (l.pitfalls.isNotEmpty()) {
            item { Text("فخاخ شائعة:", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error) }
            items(l.pitfalls) { Text("• $it") }
        }
        item { Text("رسوم تفاعلية (حرّك المنزلقات):", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary) }
        item {
            when (l.id) {
                "linear" -> LinearExplorer()
                "system", "cramer" -> LinesExplorer()
                "quad" -> QuadExplorer()
                "abs" -> AbsExplorer()
                "rad" -> RadExplorer()
                "sign" -> SignExplorer()
                else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { IneqLab(); QuadExplorer() }
            }
        }
        item { Button(onClick = onQuiz, modifier = Modifier.fillMaxWidth()) { Text("تدريب على هذا الدرس") } }
    }
}

@Composable
fun QuizScreen(lesson: Lesson?) {
    val rnd = remember { Random(System.nanoTime()) }
    var round by remember { mutableStateOf(Choices.build(Quiz.next(lesson?.id, rnd), rnd)) }
    var picked by remember { mutableStateOf<Int?>(null) }
    var score by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    val done = picked != null

    @Composable
    fun Opt(i: Int, mod: Modifier) {
        val txt = round.options[i]
        when {
            done && i == round.correct -> Button(onClick = {}, modifier = mod) { MathText("✓ $txt", color = MaterialTheme.colorScheme.onPrimary) }
            done && i == picked -> Button(onClick = {}, modifier = mod,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                MathText("✗ $txt", color = MaterialTheme.colorScheme.onError)
            }
            done -> OutlinedButton(onClick = {}, enabled = false, modifier = mod) { MathText(txt) }
            else -> OutlinedButton(onClick = {
                picked = i; total++; if (i == round.correct) score++
            }, modifier = mod) { MathText(txt, color = MaterialTheme.colorScheme.primary) }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("النتيجة: $score / $total")
        Card(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            MathText(round.prompt, Modifier.padding(16.dp), fontSize = 18.sp, bold = true)
        }
        if (round.statement != null) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                MathText(round.statement!!, Modifier.padding(16.dp), fontSize = 18.sp, bold = true)
            }
            Text("هل الحل المقترح صحيح؟")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Opt(0, Modifier.weight(1f)); Opt(1, Modifier.weight(1f))
            }
        } else {
            round.options.indices.forEach { Opt(it, Modifier.fillMaxWidth()) }
        }
        if (done) {
            val ok = picked == round.correct
            Text(if (ok) "إجابة صحيحة ✓" else "إجابة خاطئة",
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleMedium)
            if (round.steps.isNotEmpty()) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("الحل:", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        round.steps.forEach { MathText("❖ $it") }
                    }
                }
            }
            Card(Modifier.fillMaxWidth(), border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                MathText("∴ الحل: ${round.solution}", Modifier.padding(12.dp), bold = true)
            }
            round.line?.let {
                Text("الحل على خط الأعداد:", style = MaterialTheme.typography.bodySmall)
                NumberLine(it)
                if (it.isEmpty()) Text("∅ مجموعة الحل خالية: لا توجد نقاط مظللة")
            }
            if (round.note.isNotEmpty()) Text("ملاحظة: ${round.note}", style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                round = Choices.build(Quiz.next(lesson?.id, rnd, round.src), rnd); picked = null
            }, modifier = Modifier.fillMaxWidth()) { Text("السؤال التالي") }
        }
    }
}
