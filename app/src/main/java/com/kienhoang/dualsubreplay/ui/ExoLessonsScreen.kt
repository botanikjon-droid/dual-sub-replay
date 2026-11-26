package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.data.ExoCourse
import com.kienhoang.dualsubreplay.data.ExoLesson
import com.kienhoang.dualsubreplay.data.ExoLessonStore
import com.kienhoang.dualsubreplay.data.ExoQuestion
import com.kienhoang.dualsubreplay.dubbing.ReadLine
import com.kienhoang.dualsubreplay.dubbing.ReadStage
import com.kienhoang.dualsubreplay.dubbing.UziTestReader
import com.kienhoang.dualsubreplay.dubbing.uziTestVoice
import kotlinx.coroutines.flow.MutableStateFlow

/*
 * "ExoKG darslari": echocardiography lessons inside SHOKALON. A lesson shows a drawn echo picture
 * (the view, and for a defect its hole and moving shunt jets), Sardor reads the lesson aloud, and the
 * doctor answers its questions; Sardor can also run them as spoken question → pause → answer → why.
 */

/** True while the lessons screen is open (the card on the sections screen opens it). */
internal val exoLessonsOpen = MutableStateFlow(false)

private val EXO_RED = Color(0xFFB91C1C)
private val OK_GREEN = Color(0xFF1D7A3E)
private val OK_SOFT = Color(0x221D7A3E)
private val BAD_SOFT = Color(0x26B3261E)
private val READING_SOFT = Color(0x263B82F6)
private val BOX = RoundedCornerShape(12.dp)

private const val PARAGRAPH_PAUSE_MS = 900L
private const val SHORT_PAUSE_MS = 500L
private const val THINK_MS = 3000L
private const val ITEM_PAUSE_MS = 1200L

@Composable
internal fun ExoLessonsHost() {
    val open by exoLessonsOpen.collectAsStateWithLifecycle()
    if (open) ExoLessonsScreen { exoLessonsOpen.value = false }
}

/** The card on the sections screen that opens the lessons. */
@Composable
internal fun ExoLessonsCard() {
    Card(
        onClick = { exoLessonsOpen.value = true },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("exo_lessons_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Favorite, contentDescription = null, tint = EXO_RED, modifier = Modifier.size(32.dp))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text("ExoKG darslari", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Kesimlar, nuqson va shuntlar simulyatsiyasi · Sardor o‘qiydi va savol beradi",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ExoLessonsScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { ExoLessonStore.load(context) }
    val course by ExoLessonStore.course.collectAsStateWithLifecycle()
    val scores by ExoLessonStore.scores.collectAsStateWithLifecycle()
    var lessonId by rememberSaveable { mutableStateOf<String?>(null) }
    val loaded = course
    val lesson = loaded?.lessons?.firstOrNull { it.id == lessonId }
    Dialog(
        onDismissRequest = { if (lesson != null) lessonId = null else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize().testTag("exo_lessons"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                TopBar(lesson?.title ?: "ExoKG darslari", back = lesson != null, onBack = { lessonId = null }, onClose = onDismiss)
                when {
                    loaded == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                    lesson == null -> CourseList(loaded, scores) { lessonId = it }
                    else ->
                        LessonView(loaded, lesson) {
                            val all = loaded.lessons
                            lessonId = all.getOrNull(all.indexOf(lesson) + 1)?.id
                        }
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    title: String,
    back: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("exo_back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Darslar ro‘yxatiga qaytish")
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f).padding(start = if (back) 0.dp else 12.dp),
        )
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Yopish") }
    }
}

@Composable
private fun CourseList(
    course: ExoCourse,
    scores: Map<String, Int>,
    onLesson: (String) -> Unit,
) {
    val done = course.lessons.count { lesson -> (scores[lesson.id] ?: -1) >= lesson.questions.size }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                "${course.lessons.size} dars · ${course.lessons.sumOf { it.questions.size }} savol · $done tasi to‘liq o‘zlashtirildi",
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(
                progress = { done.toFloat() / course.lessons.size.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
        var number = 0
        course.modules.forEach { module ->
            item(key = module.id) {
                Column(Modifier.padding(top = 10.dp)) {
                    Text(module.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(module.subtitle, style = MaterialTheme.typography.bodySmall)
                }
            }
            val first = number
            itemsIndexed(module.lessons, key = { _, lesson -> lesson.id }) { i, lesson ->
                LessonRow(first + i + 1, lesson, scores[lesson.id]) { onLesson(lesson.id) }
            }
            number += module.lessons.size
        }
    }
}

@Composable
private fun LessonRow(
    number: Int,
    lesson: ExoLesson,
    score: Int?,
    onClick: () -> Unit,
) {
    val full = score != null && score >= lesson.questions.size
    Surface(onClick = onClick, shape = BOX, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth().testTag("exo_lesson_${lesson.id}")) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(if (full) OK_GREEN else EXO_RED),
                contentAlignment = Alignment.Center,
            ) { Text("$number", color = Color.White, fontWeight = FontWeight.Bold) }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(lesson.title, style = MaterialTheme.typography.bodyLarge)
                val tail = score?.let { " · eng yaxshi natija $it/${lesson.questions.size}" }.orEmpty()
                Hint((if (lesson.view != null) "Simulyatsiya · " else "") + "${lesson.questions.size} savol$tail")
            }
        }
    }
}

/** What Sardor reads: the lesson text, or the questions one by one. */
private enum class ReadMode { LESSON, QUESTIONS }

private fun lessonLines(lesson: ExoLesson): List<ReadLine> =
    buildList {
        add(ReadLine(ReadStage.LESSON, lesson.title, SHORT_PAUSE_MS))
        lesson.paragraphs.forEachIndexed { i, text -> add(ReadLine(ReadStage.LESSON, text, PARAGRAPH_PAUSE_MS, paragraph = i)) }
    }

internal fun exoQuestionLines(
    number: Int,
    question: ExoQuestion,
): List<ReadLine> =
    buildList {
        add(ReadLine(ReadStage.QUESTION, "Savol $number. ${question.question}", SHORT_PAUSE_MS))
        val letters = "ABCD"
        val options = question.options.mapIndexed { i, option -> "${letters.getOrElse(i) { ' ' }}: $option" }.joinToString(". ")
        add(ReadLine(ReadStage.QUESTION, "Variantlar. $options", SHORT_PAUSE_MS))
        add(ReadLine(ReadStage.THINK, null, THINK_MS))
        add(ReadLine(ReadStage.ANSWER, "To‘g‘ri javob: ${question.options[question.answer]}", SHORT_PAUSE_MS))
        if (question.explanation.isNotBlank()) add(ReadLine(ReadStage.EXPLANATION, question.explanation, SHORT_PAUSE_MS))
        add(ReadLine(ReadStage.THINK, null, ITEM_PAUSE_MS))
    }

@Composable
private fun LessonView(
    course: ExoCourse,
    lesson: ExoLesson,
    onNext: () -> Unit,
) {
    val context = LocalContext.current
    val voice = remember { uziTestVoice() }
    val reader = remember(voice) { voice?.let { UziTestReader(it) } }
    DisposableEffect(reader) { onDispose { reader?.release() } }
    var mode by remember(lesson.id) { mutableStateOf(ReadMode.LESSON) }
    val chosen = remember(lesson.id) { mutableStateMapOf<Int, Int>() }
    DisposableEffect(lesson.id) { onDispose { reader?.stop() } }
    val listState = rememberLazyListState()
    LaunchedEffect(lesson.id) { listState.scrollToItem(0) }
    LaunchedEffect(chosen.size) {
        if (chosen.size == lesson.questions.size && lesson.questions.isNotEmpty()) {
            ExoLessonStore.saveScore(context, lesson.id, chosen.count { (q, a) -> lesson.questions[q].answer == a })
        }
    }
    LazyColumn(state = listState, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        lesson.view?.let { view -> item { ExoScan(course, view, lesson.defect, Modifier.fillMaxWidth()) } }
        item { ReaderControls(reader, lesson, mode) { mode = it } }
        lessonText(lesson, reader, mode)
        item { Text("Savollar", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        questionCards(lesson, reader, mode, chosen)
        item {
            val correct = chosen.count { (q, a) -> lesson.questions[q].answer == a }
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                if (chosen.size == lesson.questions.size && lesson.questions.isNotEmpty()) {
                    Text(
                        "Natija: $correct / ${lesson.questions.size}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Button(onClick = onNext, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("exo_next")) {
                    Text("Keyingi dars")
                }
            }
        }
    }
}

@Composable
private fun ReaderControls(
    reader: UziTestReader?,
    lesson: ExoLesson,
    mode: ReadMode,
    onMode: (ReadMode) -> Unit,
) {
    if (reader == null) {
        Hint("Sardor ovozi faqat SHOKALON (o‘zbek) versiyasida ishlaydi.")
        return
    }
    val playing by reader.playing.collectAsStateWithLifecycle()
    val error by reader.error.collectAsStateWithLifecycle()
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val lessonPlaying = playing && mode == ReadMode.LESSON
            val questionsPlaying = playing && mode == ReadMode.QUESTIONS
            OutlinedButton(onClick = {
                if (lessonPlaying) {
                    reader.stop()
                } else {
                    onMode(ReadMode.LESSON)
                    reader.select(0)
                    reader.play(1, loop = false) { lessonLines(lesson) }
                }
            }, modifier = Modifier.weight(1f).testTag("exo_read_lesson")) { Text(if (lessonPlaying) "■ To‘xtatish" else "▶ Darsni o‘qish") }
            OutlinedButton(
                onClick = {
                    if (questionsPlaying) {
                        reader.stop()
                    } else {
                        onMode(ReadMode.QUESTIONS)
                        reader.select(0)
                        reader.play(lesson.questions.size, loop = false) { i -> exoQuestionLines(i + 1, lesson.questions[i]) }
                    }
                },
                modifier =
                    Modifier
                        .weight(
                            1f,
                        ).testTag("exo_read_questions"),
            ) { Text(if (questionsPlaying) "■ To‘xtatish" else "▶ Savol-javob") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Hint("Sardor o‘qiydi; ovoz bir marta yuklanadi va telefonda saqlanadi.")
    }
}

private fun LazyListScope.lessonText(
    lesson: ExoLesson,
    reader: UziTestReader?,
    mode: ReadMode,
) {
    itemsIndexed(lesson.paragraphs) { i, paragraph ->
        val line by (reader?.line ?: MutableStateFlow(null)).collectAsStateWithLifecycle()
        val reading = mode == ReadMode.LESSON && line?.paragraph == i && line?.stage == ReadStage.LESSON
        Text(
            paragraph,
            style = MaterialTheme.typography.bodyLarge,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(if (reading) Modifier.background(READING_SOFT, BOX) else Modifier)
                    .padding(6.dp),
        )
    }
}

private fun LazyListScope.questionCards(
    lesson: ExoLesson,
    reader: UziTestReader?,
    mode: ReadMode,
    chosen: MutableMap<Int, Int>,
) {
    itemsIndexed(lesson.questions) { i, question ->
        val index by (reader?.index ?: MutableStateFlow(-1)).collectAsStateWithLifecycle()
        val playing by (reader?.playing ?: MutableStateFlow(false)).collectAsStateWithLifecycle()
        val current = playing && mode == ReadMode.QUESTIONS && index == i
        QuestionCard(i + 1, question, chosen[i], current) { chosen[i] = it }
    }
}

@Composable
private fun QuestionCard(
    number: Int,
    question: ExoQuestion,
    picked: Int?,
    current: Boolean,
    onPick: (Int) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(if (current) 2.dp else 1.dp, if (current) DOPPLER_BLUE else MaterialTheme.colorScheme.outlineVariant, BOX)
            .padding(12.dp)
            .testTag("exo_question_$number"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("$number. ${question.question}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        question.options.forEachIndexed { i, option ->
            val background =
                when {
                    picked == null -> Color.Transparent
                    i == question.answer -> OK_SOFT
                    i == picked -> BAD_SOFT
                    else -> Color.Transparent
                }
            Text(
                option,
                style = MaterialTheme.typography.bodyMedium,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, BOX)
                        .background(background, BOX)
                        .clickable(enabled = picked == null) { onPick(i) }
                        .padding(10.dp),
            )
        }
        if (picked != null) {
            val right = picked == question.answer
            Text(
                (if (right) "To‘g‘ri. " else "Noto‘g‘ri. To‘g‘ri javob: ${question.options[question.answer]}. ") + question.explanation,
                color = if (right) OK_GREEN else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
