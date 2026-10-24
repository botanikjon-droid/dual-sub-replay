package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.data.CatalogSection
import com.kienhoang.dualsubreplay.data.CatalogVideo
import com.kienhoang.dualsubreplay.data.LessonLibraryStore
import com.kienhoang.dualsubreplay.data.LessonNotes
import com.kienhoang.dualsubreplay.data.PrintableLesson
import com.kienhoang.dualsubreplay.data.sectionQuiz
import com.kienhoang.dualsubreplay.data.sectionTestVerdict
import com.kienhoang.dualsubreplay.dubbing.DubbingSettings
import kotlinx.coroutines.flow.StateFlow

/*
 * "Konspekt va testlar" of a SHOKALON section: how many of its lectures have notes, the notes of all of them
 * to read without opening the videos, one test from all their questions, the section's PDF, and writing the
 * missing notes for many lectures at once.
 */

/** What the section screen needs from the app to write notes for many lectures. */
internal class SectionStudyActions(
    val progress: StateFlow<SectionNotesProgress>,
    val write: (section: String, videos: List<CatalogVideo>, engine: LessonEngine) -> Unit,
    val cancel: () -> Unit,
)

private enum class StudyView { NOTES, TEST }

@Composable
internal fun SectionStudyCard(
    section: CatalogSection,
    actions: SectionStudyActions?,
    onOpen: (CatalogVideo) -> Unit,
) {
    val noted by LessonLibraryStore.notedIds.collectAsStateWithLifecycle()
    val videos = remember(section) { section.topics.flatMap { it.videos }.distinctBy { it.videoId } }
    val withNotes = videos.filter { it.videoId in noted }
    val missing = videos.filterNot { it.videoId in noted }
    var view by remember { mutableStateOf<StudyView?>(null) }
    Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).testTag("section_study")) {
        LessonCard(title = "Konspekt va testlar", icon = Icons.AutoMirrored.Filled.MenuBook) {
            Text("${withNotes.size} / ${videos.size} darsda konspekt va test bor", fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    enabled = withNotes.isNotEmpty(),
                    onClick = { view = StudyView.NOTES },
                    modifier = Modifier.testTag("section_notes_open"),
                ) { Text("Konspektlar") }
                Button(
                    enabled = withNotes.isNotEmpty(),
                    onClick = { view = StudyView.TEST },
                    modifier = Modifier.testTag("section_test_open"),
                ) { Text("Bo‘lim testi") }
            }
            SectionPdfButtons(section.name, withNotes.map { it.videoId })
            if (actions != null) SectionNotesWriting(section.name, missing, actions)
        }
    }
    when (view) {
        StudyView.NOTES -> SectionNotesDialog(section.name, withNotes, onOpen) { view = null }
        StudyView.TEST -> SectionTestDialog(section.name, withNotes) { view = null }
        null -> Unit
    }
}

@Composable
private fun SectionPdfButtons(
    section: String,
    videoIds: List<String>,
) {
    if (videoIds.isEmpty()) return
    val context = LocalContext.current
    val runner = rememberPdfRunner()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { runner.run(false) { buildSectionNotesPdf(context, section, videoIds) } }) { Text("Bo‘lim: PDF") }
        OutlinedButton(onClick = { runner.run(true) { buildSectionNotesPdf(context, section, videoIds) } }) { Text("Chop etish") }
    }
    runner.message?.let { Hint(it) }
}

/** Writing the konspekt and test of every lecture of the section that has none. */
@Composable
private fun SectionNotesWriting(
    section: String,
    missing: List<CatalogVideo>,
    actions: SectionStudyActions,
) {
    val progress by actions.progress.collectAsStateWithLifecycle()
    val geminiKey by DubbingSettings.geminiApiKey.collectAsStateWithLifecycle()
    val claudeKey by DubbingSettings.claudeApiKey.collectAsStateWithLifecycle()
    val mine = progress.section == section
    HorizontalDivider()
    when {
        mine && progress.running -> {
            Text("Yozilmoqda: ${progress.done} / ${progress.total}", fontWeight = FontWeight.SemiBold)
            LinearProgressIndicator(
                progress = { if (progress.total == 0) 0f else progress.done.toFloat() / progress.total },
                modifier = Modifier.fillMaxWidth(),
            )
            progress.current?.let { Hint(it) }
            OutlinedButton(onClick = actions.cancel) { Text("To‘xtatish") }
        }
        missing.isEmpty() -> Hint("Bo‘limdagi barcha darslarda konspekt bor.")
        else -> {
            Text("Konspektsiz ${missing.size} ta darsga yozish")
            Hint(
                "Har bir dars uchun bitta so‘rov ketadi, videolar ochilmaydi. Tarjimasi bor darslarda konspekt shu " +
                    "tarjima atamalari bilan yoziladi. Gemini bepul limiti kuniga ~20 so‘rov.",
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = geminiKey.isNotBlank(),
                    onClick = { actions.write(section, missing, LessonEngine.GEMINI) },
                    modifier = Modifier.testTag("section_notes_gemini"),
                ) { Text("Gemini bilan") }
                OutlinedButton(
                    enabled = claudeKey.isNotBlank(),
                    onClick = { actions.write(section, missing, LessonEngine.CLAUDE) },
                    modifier = Modifier.testTag("section_notes_claude"),
                ) { Text("Claude bilan") }
            }
            if (geminiKey.isBlank() && claudeKey.isBlank()) Hint("Gemini yoki Claude API kaliti sozlamalarda kiritilgan bo‘lishi kerak.")
        }
    }
    if (mine && !progress.running) progress.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
}

/** A full-screen page with a title and a close button. */
@Composable
private fun StudyPage(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Hint(subtitle)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Yopish") }
                }
                content()
            }
        }
    }
}

/** Every lecture's notes in the section's order; a lecture opens to show its konspekt, terms and test. */
@Composable
private fun SectionNotesDialog(
    section: String,
    videos: List<CatalogVideo>,
    onOpen: (CatalogVideo) -> Unit,
    onDismiss: () -> Unit,
) {
    var expanded by remember { mutableStateOf<String?>(videos.singleOrNull()?.videoId) }
    StudyPage("Konspektlar", "$section · ${videos.size} ta dars", onDismiss) {
        videos.forEachIndexed { index, video ->
            Surface(
                onClick = { expanded = if (expanded == video.videoId) null else video.videoId },
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth().testTag("section_notes_${video.videoId}"),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${index + 1}. ${video.title}",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = if (expanded == video.videoId) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (expanded == video.videoId) LessonNotesOf(video, onOpen)
                }
            }
        }
    }
}

@Composable
private fun LessonNotesOf(
    video: CatalogVideo,
    onOpen: (CatalogVideo) -> Unit,
) {
    val notes by produceState<LessonNotes?>(null, video.videoId) {
        value = LessonLibraryStore.notes(video.videoId)
    }
    TextButton(onClick = { onOpen(video) }) { Text("▶ Videoni ochish") }
    notes?.let { NotesBody(video.videoId, it, onCaptureFrames = null) } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
}

/** One test from the questions of every lecture of the section, with the score at the end. */
@Composable
private fun SectionTestDialog(
    section: String,
    videos: List<CatalogVideo>,
    onDismiss: () -> Unit,
) {
    val lessons by produceState<List<PrintableLesson>?>(null, videos) {
        value =
            videos.mapNotNull { video -> LessonLibraryStore.notes(video.videoId)?.let { PrintableLesson(video.videoId, video.title, it) } }
    }
    var round by remember { mutableIntStateOf(0) }
    val quiz = remember(lessons, round) { sectionQuiz(lessons.orEmpty()) }
    val chosen = remember(quiz) { mutableStateMapOf<Int, Int>() }
    StudyPage("Bo‘lim testi", "$section · ${quiz.size} savol · ${lessons?.size ?: 0} ta darsdan", onDismiss) {
        if (lessons == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        quiz.forEachIndexed { index, item ->
            QuizItem(index, item.question, chosen[index]) { chosen[index] = it }
            Hint("Dars: ${item.lessonTitle}")
            HorizontalDivider()
        }
        val right = quiz.indices.count { chosen[it] == quiz[it].question.answer }
        if (quiz.isNotEmpty()) {
            Text(
                if (chosen.size ==
                    quiz.size
                ) {
                    "Natija: ${sectionTestVerdict(right, quiz.size)}"
                } else {
                    "Javob berildi: ${chosen.size} / ${quiz.size}"
                },
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("section_test_score"),
            )
        }
        Button(onClick = { round++ }, modifier = Modifier.testTag("section_test_new")) { Text("Yangi test (boshqa savollar)") }
    }
}
