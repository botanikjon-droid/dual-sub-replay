package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.data.UZI_TEST_TOPICS
import com.kienhoang.dualsubreplay.data.UziTestBank
import com.kienhoang.dualsubreplay.data.UziTestQuestion
import com.kienhoang.dualsubreplay.data.UziTestStore
import com.kienhoang.dualsubreplay.data.filter
import com.kienhoang.dualsubreplay.data.uziTopicName
import com.kienhoang.dualsubreplay.dubbing.ReadLine
import com.kienhoang.dualsubreplay.dubbing.ReadStage
import com.kienhoang.dualsubreplay.dubbing.ReaderSettings
import com.kienhoang.dualsubreplay.dubbing.UziTestReader
import com.kienhoang.dualsubreplay.dubbing.lessonScript
import com.kienhoang.dualsubreplay.dubbing.questionScript
import com.kienhoang.dualsubreplay.dubbing.uziTestVoice
import kotlinx.coroutines.flow.MutableStateFlow

/*
 * "UZI testlari": the qualification-exam bank inside SHOKALON. Three tabs: listen (the Uzbek voice
 * reads question, answer and a plain explanation, or a topic lesson), test yourself, and search.
 */

/** True while the test screen is open; the sections screen opens it. */
internal val uziTestsOpen = MutableStateFlow(false)

private val OK_GREEN = Color(0xFF1D7A3E)
private val OK_SOFT = Color(0x221D7A3E)
private val WARN_TEXT = Color(0xFF9A6200)
private val WARN_SOFT = Color(0x26F0B54A)
private val BAD_SOFT = Color(0x26B3261E)
private val BOX_SHAPE = RoundedCornerShape(12.dp)

/** The pause after each question is shorter; a longer silent pause is the time to think. */
private const val THINK_PAUSE_MIN_MS = 1500L

@Composable
internal fun UziTestsHost() {
    val open by uziTestsOpen.collectAsStateWithLifecycle()
    if (!open) return
    UziTestsScreen { uziTestsOpen.value = false }
}

/** The card on the sections screen that opens the tests. */
@Composable
internal fun UziTestsCard() {
    Card(
        onClick = { uziTestsOpen.value = true },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("uzi_tests_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Headphones, contentDescription = null, modifier = Modifier.size(32.dp))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text("UZI testlari", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "465 savol · eshitib yodlash, o‘zini sinash, qidirish",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private enum class TestTab(
    val label: String,
) {
    LISTEN("Eshitish"),
    QUIZ("Test"),
    SEARCH("Qidirish"),
}

@Composable
private fun UziTestsScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { UziTestStore.load(context) }
    val bank by UziTestStore.bank.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(TestTab.LISTEN) }
    var topic by rememberSaveable { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("uzi_tests"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "UZI testlari",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Yopish") }
                }
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TestTab.entries.forEach { entry ->
                        FilterChip(selected = tab == entry, onClick = { tab = entry }, label = { Text(entry.label) })
                    }
                }
                TopicChips(bank, topic) { topic = it }
                val loaded = bank
                if (loaded == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                } else {
                    when (tab) {
                        TestTab.LISTEN -> ListenTab(loaded, topic)
                        TestTab.QUIZ -> QuizTab(loaded, topic)
                        TestTab.SEARCH -> SearchTab(loaded, topic)
                    }
                }
            }
        }
    }
}

@Composable
private fun TopicChips(
    bank: UziTestBank?,
    topic: String?,
    onTopic: (String?) -> Unit,
) {
    val counts =
        remember(bank) {
            bank
                ?.questions
                ?.groupingBy { it.topic }
                ?.eachCount()
                .orEmpty()
        }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { FilterChip(selected = topic == null, onClick = { onTopic(null) }, label = { Text("Hammasi") }) }
        items(UZI_TEST_TOPICS.keys.filter { (counts[it] ?: 0) > 0 }) { id ->
            FilterChip(selected = topic == id, onClick = { onTopic(id) }, label = { Text("${uziTopicName(id)} · ${counts[id]}") })
        }
    }
}

// ---------------------------------------------------------------- listening

@Composable
private fun ListenTab(
    bank: UziTestBank,
    topic: String?,
) {
    val voice = remember { uziTestVoice() }
    if (voice == null) {
        Box(Modifier.padding(16.dp)) { Hint("Ovozli o‘qish faqat SHOKALON (o‘zbek) versiyasida ishlaydi.") }
        return
    }
    val reader = remember { UziTestReader(voice) }
    DisposableEffect(Unit) { onDispose { reader.release() } }
    var lessons by rememberSaveable { mutableStateOf(false) }
    var settings by remember { mutableStateOf(ReaderSettings()) }
    var shuffleSeed by rememberSaveable { mutableIntStateOf(0) }
    var loop by rememberSaveable { mutableStateOf(true) }
    val questions = remember(bank, topic, shuffleSeed) { bank.filter(topic).shuffledBy(shuffleSeed) }
    val lessonList = remember(bank, topic) { bank.lessons.filter { topic == null || it.id == topic } }
    val count = if (lessons) lessonList.size else questions.size
    val playing by reader.playing.collectAsStateWithLifecycle()
    val index by reader.index.collectAsStateWithLifecycle()
    val line by reader.line.collectAsStateWithLifecycle()
    val error by reader.error.collectAsStateWithLifecycle()
    LaunchedEffect(lessons, topic, shuffleSeed) {
        reader.stop()
        reader.select(0)
    }
    KeepScreenOn(playing)
    val script: (Int) -> List<ReadLine> = { i ->
        if (lessons) lessonScript(lessonList[i]) else questionScript(questions[i], reader.settings)
    }
    val play = { reader.play(count, loop, script) }
    val step = { delta: Int ->
        val wasPlaying = playing
        reader.stop()
        if (count > 0) reader.select((index + delta + count) % count)
        if (wasPlaying) play()
    }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = !lessons, onClick = { lessons = false }, label = { Text("Savol-javob") })
                FilterChip(selected = lessons, onClick = { lessons = true }, label = { Text("Mavzu darslari") })
            }
        }
        item {
            NowReading(
                lesson = if (lessons) lessonList.getOrNull(index) else null,
                question = if (lessons) null else questions.getOrNull(index),
                line = line,
                position = "${(index + 1).coerceAtMost(count)} / $count",
            )
        }
        item {
            Column {
                if (count > 0) LinearProgressIndicator(progress = { (index + 1f) / count }, modifier = Modifier.fillMaxWidth())
                Transport(playing, onPrevious = { step(-1) }, onNext = { step(1) }) { if (playing) reader.stop() else play() }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Hint("Ovoz internetdan olinadi va telefonda saqlanadi: ikkinchi marta eshitganda internet kerak emas.")
            }
        }
        item {
            ListenSettings(settings, loop, shuffled = shuffleSeed != 0, onLoop = { loop = it }, onShuffle = {
                shuffleSeed = if (it) (1..Int.MAX_VALUE).random() else 0
            }) {
                settings = it
                reader.settings = it
            }
        }
        listRows(lessons, lessonList.map { it.title }, questions, index) { k ->
            val wasPlaying = playing
            reader.stop()
            reader.select(k)
            if (wasPlaying) play()
        }
    }
}

private fun List<UziTestQuestion>.shuffledBy(seed: Int): List<UziTestQuestion> =
    when (seed) {
        0 -> this
        else -> shuffled(kotlin.random.Random(seed))
    }

@Composable
private fun NowReading(
    lesson: com.kienhoang.dualsubreplay.data.UziTestLesson?,
    question: UziTestQuestion?,
    line: ReadLine?,
    position: String,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, BOX_SHAPE)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val label = question?.let { "${uziTopicName(it.topic)} · savol ${it.number}" } ?: "Dars"
        Row {
            Hint(label)
            Spacer(Modifier.weight(1f))
            Hint(position)
        }
        when {
            question != null -> QuestionReading(question, line)
            lesson != null -> {
                Text(lesson.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                lesson.paragraphs.forEachIndexed { k, paragraph ->
                    Highlighted(paragraph, line != null && line.stage == ReadStage.LESSON && line.paragraph == k)
                }
            }
            else -> Hint("Bu mavzuda material yo‘q.")
        }
    }
}

@Composable
private fun QuestionReading(
    question: UziTestQuestion,
    line: ReadLine?,
) {
    val stage = line?.stage
    Highlighted(question.question, stage == ReadStage.QUESTION, bold = true)
    // The answer stays hidden while the question is read and during the pause to think.
    val hidden = stage == ReadStage.QUESTION || (line != null && stage == ReadStage.THINK && line.pauseAfterMs > THINK_PAUSE_MIN_MS)
    if (hidden) {
        Hint(if (stage == ReadStage.THINK) "O‘ylab ko‘ring…" else "Javob tez orada…")
        return
    }
    Text(
        "✓ ${question.answer}",
        color = OK_GREEN,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().background(OK_SOFT, BOX_SHAPE).padding(10.dp),
    )
    if (question.explanation.isNotBlank()) Highlighted(question.explanation, stage == ReadStage.EXPLANATION)
    WarningLine(question)
}

@Composable
private fun Highlighted(
    text: String,
    active: Boolean,
    bold: Boolean = false,
) {
    Text(
        text,
        style = if (bold) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
        fontWeight = if (bold) FontWeight.SemiBold else null,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, BOX_SHAPE)
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun WarningLine(question: UziTestQuestion) {
    val text =
        when {
            question.warning.isNotBlank() -> "⚠ ${question.warning}"
            question.suggested -> "⚠ Faylda kalit yo‘q edi: javobni Claude taklif qilgan."
            else -> return
        }
    Text(
        text,
        color = WARN_TEXT,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().background(WARN_SOFT, BOX_SHAPE).padding(10.dp),
    )
}

@Composable
private fun Transport(
    playing: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPlay: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedIconButton(onClick = onPrevious, modifier = Modifier.size(52.dp)) {
            Icon(Icons.Default.SkipPrevious, contentDescription = "Oldingi")
        }
        FilledIconButton(onClick = onPlay, modifier = Modifier.size(68.dp).testTag("uzi_tests_play")) {
            Icon(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (playing) "Pauza" else "Eshitish",
                modifier = Modifier.size(34.dp),
            )
        }
        OutlinedIconButton(onClick = onNext, modifier = Modifier.size(52.dp)) {
            Icon(Icons.Default.SkipNext, contentDescription = "Keyingi")
        }
    }
}

@Composable
private fun ListenSettings(
    settings: ReaderSettings,
    loop: Boolean,
    shuffled: Boolean,
    onLoop: (Boolean) -> Unit,
    onShuffle: (Boolean) -> Unit,
    onChange: (ReaderSettings) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Sozlamalar", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        ChoiceRow("O‘ylash pauzasi", listOf(0, 2, 4, 7), settings.thinkSeconds, { if (it == 0) "yo‘q" else "$it s" }) {
            onChange(settings.copy(thinkSeconds = it))
        }
        ChoiceRow("Javobni takrorlash", listOf(1, 2, 3), settings.repeats, { "$it marta" }) {
            onChange(settings.copy(repeats = it))
        }
        Text("Tezlik: ${String.format(java.util.Locale.US, "%.2f", settings.speed)}×", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = settings.speed,
            onValueChange = { onChange(settings.copy(speed = (it * 20).toInt() / 20f)) },
            valueRange = 0.75f..1.5f,
        )
        SwitchRow("Tushuntirishni o‘qish", settings.explain) { onChange(settings.copy(explain = it)) }
        SwitchRow("Aralash tartibda", shuffled, onShuffle)
        SwitchRow("Oxirida boshidan davom etish", loop, onLoop)
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    values: List<Int>,
    selected: Int,
    label: (Int) -> String,
    onSelect: (Int) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { value ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label(value)) })
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun LazyListScope.listRows(
    lessons: Boolean,
    lessonTitles: List<String>,
    questions: List<UziTestQuestion>,
    current: Int,
    onSelect: (Int) -> Unit,
) {
    item { Text("Ro‘yxat", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
    if (lessons) {
        itemsIndexed(lessonTitles) { k, title -> ListRow("${k + 1}", title, k == current) { onSelect(k) } }
    } else {
        itemsIndexed(questions, key = { _, q -> q.number }) { k, q -> ListRow("${q.number}", q.question, k == current) { onSelect(k) } }
    }
}

@Composable
private fun ListRow(
    number: String,
    text: String,
    current: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, BOX_SHAPE)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(number, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
    }
}

// ---------------------------------------------------------------- quiz

private class QuizItem(
    val question: UziTestQuestion,
    val order: List<Int>,
) {
    var selected by mutableStateOf(emptySet<Int>())
    var checked by mutableStateOf(false)
    val right: Boolean get() = question.options.indices.all { question.options[it].correct == (it in selected) }
}

@Composable
private fun QuizTab(
    bank: UziTestBank,
    topic: String?,
) {
    val pool = remember(bank, topic) { bank.filter(topic) }
    var items by remember(topic) { mutableStateOf<List<QuizItem>?>(null) }
    var current by remember(topic) { mutableIntStateOf(0) }
    val start = { source: List<UziTestQuestion>, size: Int ->
        items = source.shuffled().take(size).map { QuizItem(it, it.options.indices.shuffled()) }
        current = 0
    }
    val quiz = items
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            quiz == null -> item { QuizStart(pool.size) { start(pool, it) } }
            current >= quiz.size -> item { QuizResult(quiz, onRetry = { wrong -> start(wrong, wrong.size) }) { items = null } }
            else ->
                item {
                    QuizCard(quiz, current, onNext = { current++ }) { items = null }
                }
        }
    }
}

@Composable
private fun QuizStart(
    available: Int,
    onStart: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("O‘zingizni sinang", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Hint("Tanlangan mavzuda $available ta savol. Variantlar har safar aralashtiriladi.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(20, 50, 100).filter { it < available }.forEach { size ->
                OutlinedButton(onClick = { onStart(size) }) { Text("$size ta savol") }
            }
            Button(onClick = { onStart(available) }, enabled = available > 0) { Text("Hammasi ($available)") }
        }
    }
}

@Composable
private fun QuizCard(
    quiz: List<QuizItem>,
    current: Int,
    onNext: () -> Unit,
    onQuit: () -> Unit,
) {
    val item = quiz[current]
    val question = item.question
    val score = quiz.count { it.checked && it.right }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Hint("Savol ${current + 1} / ${quiz.size} · to‘g‘ri $score · savol ${question.number}")
        LinearProgressIndicator(progress = { current.toFloat() / quiz.size }, modifier = Modifier.fillMaxWidth())
        Text(question.question, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        val multi = question.correctCount > 1
        if (multi) Hint("Bir nechta to‘g‘ri javobni tanlang.")
        item.order.forEachIndexed { k, optionIndex ->
            QuizOption(item, optionIndex, "${k + 1}") {
                if (item.checked) return@QuizOption
                item.selected = if (multi) item.selected.toggle(optionIndex) else setOf(optionIndex)
                if (!multi) item.checked = true
            }
        }
        if (item.checked) WarningLine(question)
        if (item.checked && question.explanation.isNotBlank()) Text(question.explanation, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                item.checked -> Button(onClick = onNext) { Text(if (current + 1 < quiz.size) "Keyingi" else "Natija") }
                multi -> Button(onClick = { item.checked = true }, enabled = item.selected.isNotEmpty()) { Text("Tekshirish") }
            }
            OutlinedButton(onClick = onQuit) { Text("Tugatish") }
        }
    }
}

private fun Set<Int>.toggle(value: Int): Set<Int> = if (value in this) this - value else this + value

@Composable
private fun QuizOption(
    item: QuizItem,
    optionIndex: Int,
    label: String,
    onClick: () -> Unit,
) {
    val option = item.question.options[optionIndex]
    val chosen = optionIndex in item.selected
    val fill =
        when {
            item.checked && option.correct -> OK_SOFT
            item.checked && chosen -> BAD_SOFT
            chosen -> MaterialTheme.colorScheme.primaryContainer
            else -> Color.Transparent
        }
    val edge = if (item.checked && option.correct) OK_GREEN else MaterialTheme.colorScheme.outlineVariant
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.5.dp, edge, BOX_SHAPE)
            .background(fill, BOX_SHAPE)
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 10.dp))
        Text(option.text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun QuizResult(
    quiz: List<QuizItem>,
    onRetry: (List<UziTestQuestion>) -> Unit,
    onNew: () -> Unit,
) {
    val answered = quiz.filter { it.checked }
    val wrong = answered.filterNot { it.right }.map { it.question }
    val percent = if (answered.isEmpty()) 0 else (answered.size - wrong.size) * 100 / answered.size
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("$percent%", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
        Text("${answered.size - wrong.size} ta to‘g‘ri, ${wrong.size} ta xato (${answered.size} ta savoldan)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (wrong.isNotEmpty()) Button(onClick = { onRetry(wrong) }) { Text("Xatolarni qayta ishlash") }
            OutlinedButton(onClick = onNew) { Text("Yangi test") }
        }
        wrong.forEach { AnswerCard(it) }
    }
}

// ---------------------------------------------------------------- search

@Composable
private fun SearchTab(
    bank: UziTestBank,
    topic: String?,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val found = remember(bank, topic, query) { bank.filter(topic, query) }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().testTag("uzi_tests_search"),
                    singleLine = true,
                    label = { Text("So‘z yoki savol raqami") },
                    placeholder = { Text("portal vena, jigar segment, 153") },
                )
                Spacer(Modifier.height(4.dp))
                Hint("${found.size} ta savol")
            }
        }
        items(found.take(SEARCH_LIMIT), key = { it.number }) { AnswerCard(it) }
    }
}

private const val SEARCH_LIMIT = 100

@Composable
private fun AnswerCard(question: UziTestQuestion) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, BOX_SHAPE)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Hint("${question.number} · ${uziTopicName(question.topic)}")
        Text(question.question, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        question.options.filter { it.correct }.forEach { option ->
            Text(
                "✓ ${option.text}",
                color = OK_GREEN,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().background(OK_SOFT, BOX_SHAPE).padding(8.dp),
            )
        }
        WarningLine(question)
    }
}
