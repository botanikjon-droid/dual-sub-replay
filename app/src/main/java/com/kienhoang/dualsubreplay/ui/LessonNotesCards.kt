package com.kienhoang.dualsubreplay.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.data.LessonLibraryStore
import com.kienhoang.dualsubreplay.data.LessonNotes
import com.kienhoang.dualsubreplay.data.LessonRecord
import com.kienhoang.dualsubreplay.data.NoteFrame
import com.kienhoang.dualsubreplay.data.QuizQuestion
import com.kienhoang.dualsubreplay.data.displayTitle
import com.kienhoang.dualsubreplay.data.formatVideoTime
import com.kienhoang.dualsubreplay.data.quizScore
import com.kienhoang.dualsubreplay.dubbing.DubbingSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "Konspekt va test": the lesson's summary, key terms and a quiz, written in one request. */
@Composable
internal fun LessonNotesCard(
    activeVideoId: String?,
    state: LessonNotesState,
    onWrite: (LessonEngine) -> Unit,
    onCancel: () -> Unit,
    onCaptureFrames: () -> Unit = {},
) {
    val geminiKey by DubbingSettings.geminiApiKey.collectAsStateWithLifecycle()
    val claudeKey by DubbingSettings.claudeApiKey.collectAsStateWithLifecycle()
    val notes = state.notes?.takeIf { state.videoId == activeVideoId }
    LessonCard(title = "Konspekt va test", icon = Icons.AutoMirrored.Filled.MenuBook) {
        Hint(
            if (activeVideoId == null) {
                "Avval dars videosini oching."
            } else {
                "Darsning qisqa konspekti, asosiy atamalari va 5–10 ta test savoli bitta so‘rovda yoziladi " +
                    "(Gemini bepul limitidan 1 so‘rov). Tarjima tayyor bo‘lsa, konspekt shu tarjima atamalarini ishlatadi."
            },
        )
        if (state.running && state.videoId == activeVideoId) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(onClick = onCancel) { Text("To‘xtatish") }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = activeVideoId != null && geminiKey.isNotBlank(),
                    modifier = Modifier.testTag("notes_with_gemini"),
                    onClick = { onWrite(LessonEngine.GEMINI) },
                ) { Text(if (notes == null) "Gemini bilan yozish" else "Gemini: qayta yozish") }
                OutlinedButton(
                    enabled = activeVideoId != null && claudeKey.isNotBlank(),
                    modifier = Modifier.testTag("notes_with_claude"),
                    onClick = { onWrite(LessonEngine.CLAUDE) },
                ) { Text("Claude bilan") }
            }
            if (geminiKey.isBlank() && claudeKey.isBlank()) Hint("Gemini yoki Claude API kaliti sozlamalarda kiritilgan bo‘lishi kerak.")
        }
        state.message?.takeIf { state.videoId == activeVideoId }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (notes != null && activeVideoId != null) NotesBody(activeVideoId, notes, onCaptureFrames)
        HorizontalDivider()
        NotesPdfButtons(activeVideoId?.takeIf { notes != null })
    }
}

@Composable
private fun NotesBody(
    videoId: String,
    notes: LessonNotes,
    onCaptureFrames: () -> Unit,
) {
    HorizontalDivider()
    NoteFrames(videoId, notes.frames, onCaptureFrames)
    if (notes.summary.isEmpty()) return
    Text("Konspekt", fontWeight = FontWeight.SemiBold)
    notes.summary.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
    if (notes.terms.isNotEmpty()) {
        Text("Asosiy atamalar", fontWeight = FontWeight.SemiBold)
        notes.terms.forEach { Text("${it.english} — ${it.uzbek}", style = MaterialTheme.typography.bodyMedium) }
    }
    HorizontalDivider()
    if (notes.quiz.isNotEmpty()) Quiz(notes)
    Hint(
        "${notes.engine} yozgan, ${formatDay(
            notes.createdAtMs,
        )}. Konspekt avtomatik tuzilgan: muhim qiymatlarni videoning o‘zidan tekshiring.",
    )
}

/** The lecture's pictured moments: taken frames with captions, and a button to take the missing ones. */
@Composable
private fun NoteFrames(
    videoId: String,
    frames: List<NoteFrame>,
    onCaptureFrames: () -> Unit,
) {
    if (frames.isEmpty()) {
        Hint("Video ko\u2018rayotganda subtitr ustidagi \uD83D\uDCF7 tugmasi ekrandagi UZI kadrini konspektga qo\u2018shadi.")
        return
    }
    val missing = frames.count { it.image.isEmpty() }
    Text("Rasmli konspekt (${frames.size - missing} / ${frames.size} kadr)", fontWeight = FontWeight.SemiBold)
    if (missing > 0) {
        Button(onClick = onCaptureFrames, modifier = Modifier.fillMaxWidth().testTag("capture_note_frames")) {
            Text("\uD83D\uDCF7 Kadrlarni videodan olish ($missing ta)")
        }
        Hint("Oyna yopiladi, video har bir joyga o\u2018tib to\u2018xtaydi va kadr olinadi. Video yuklab olinmaydi.")
    }
    frames.forEach { frame ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FrameImage(LessonLibraryStore.frameFile(videoId, frame.image))
            Text("${formatVideoTime(frame.seconds)} · ${frame.caption}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun FrameImage(file: File?) {
    val image by produceState<ImageBitmap?>(null, file) {
        value = file?.takeIf { it.isFile }?.let { withContext(Dispatchers.IO) { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }
    }
    image?.let {
        Image(
            bitmap = it,
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
        )
    } ?: Hint("\uD83D\uDCF7 kadr hali olinmagan")
}

@Composable
private fun Quiz(notes: LessonNotes) {
    val chosen = remember(notes) { mutableStateMapOf<Int, Int>() }
    Text("Test (${notes.quiz.size} savol)", fontWeight = FontWeight.SemiBold)
    notes.quiz.forEachIndexed { index, question ->
        QuizItem(index, question, chosen[index]) { chosen[index] = it }
    }
    if (chosen.size == notes.quiz.size) {
        Text(
            "Natija: ${quizScore(notes.quiz, chosen)} / ${notes.quiz.size}",
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("quiz_score"),
        )
    }
    if (chosen.isNotEmpty()) TextButton(onClick = { chosen.clear() }) { Text("Testni qaytadan boshlash") }
}

@Composable
private fun QuizItem(
    index: Int,
    question: QuizQuestion,
    chosen: Int?,
    onChoose: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${index + 1}. ${question.question}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        question.options.forEachIndexed { option, text ->
            val mark =
                when {
                    chosen == null -> ""
                    option == question.answer -> "✓ "
                    option == chosen -> "✗ "
                    else -> ""
                }
            FilterChip(
                selected = chosen == option,
                enabled = chosen == null || option == chosen || option == question.answer,
                onClick = { if (chosen == null) onChoose(option) },
                label = { Text(mark + text) },
                modifier = Modifier.fillMaxWidth().testTag("quiz_${index}_$option"),
            )
        }
        if (chosen != null) {
            val verdict = if (chosen == question.answer) "To‘g‘ri." else "Noto‘g‘ri."
            Hint("$verdict ${question.explanation}".trim())
        }
    }
}

/** "Tarjima qilingan darslar": every lesson with a stored translation, newest first, to reopen. */
@Composable
internal fun LessonLibraryCard(
    records: List<LessonRecord>,
    activeVideoId: String?,
    onOpen: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    LessonCard(title = "Tarjima qilingan darslar (${records.size})", icon = Icons.Default.History) {
        if (records.isEmpty()) {
            Hint("Hali tarjima qilingan dars yo‘q. Tarjima tayyor bo‘lgan har bir dars shu yerda saqlanadi.")
            return@LessonCard
        }
        val shown = if (expanded) records else records.take(COLLAPSED_LESSONS)
        shown.forEach { record ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(record.displayTitle(), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Hint("${record.engine} · ${formatDay(record.translatedAtMs)} · ${record.lines} qator")
                }
                if (record.videoId == activeVideoId) {
                    Hint("ochiq")
                } else {
                    TextButton(modifier = Modifier.testTag("open_lesson_${record.videoId}"), onClick = { onOpen(record.videoId) }) {
                        Text("Ochish")
                    }
                }
            }
        }
        if (records.size > COLLAPSED_LESSONS) {
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Kamroq" else "Hammasi (${records.size})") }
        }
    }
}

private const val COLLAPSED_LESSONS = 5

private fun formatDay(timeMs: Long): String =
    if (timeMs <= 0L) "" else SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT).format(Date(timeMs))
