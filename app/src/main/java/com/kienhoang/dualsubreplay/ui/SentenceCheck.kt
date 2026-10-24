package com.kienhoang.dualsubreplay.ui

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.data.ReviewSentence
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.readBoundedText
import com.kienhoang.dualsubreplay.data.reviewFileText
import com.kienhoang.dualsubreplay.data.reviewTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * "Gapni tekshirish": a long press on a subtitle sentence opens it with its translation. The Uzbek can be
 * corrected and saved on the spot, copied or shared (with a request to check it) to another app, and the
 * sentence can be marked; the marked sentences of a lesson go out together as one file from the "Dars"
 * screen and come back corrected (see SentenceReview.kt).
 */

/** The sentence whose check dialog is open; set by a long press in the subtitle list. */
internal val sentenceToCheck = MutableStateFlow<SubtitleSegment?>(null)

/** Sentences marked for checking, per lesson (start and end times). Kept by AppViewModel. */
internal val sentenceReviewMarks = MutableStateFlow<Map<String, Set<Pair<Long, Long>>>>(emptyMap())

/** Opens the check dialog for [segment] in the SHOKALON build; null elsewhere, so the long press does nothing. */
internal fun sentenceLongPress(segment: SubtitleSegment): (() -> Unit)? =
    if (shokalonFocusMode && !segment.translatedText.isNullOrBlank()) {
        { sentenceToCheck.value = segment }
    } else {
        null
    }

/** Whether [segment] of the open lesson is marked for checking. */
internal fun sentenceMarked(
    marks: Map<String, Set<Pair<Long, Long>>>,
    videoId: String?,
    segment: SubtitleSegment,
): Boolean = videoId != null && (segment.startMs to segment.endMs) in marks[videoId].orEmpty()

/** The check dialog, shown while [sentenceToCheck] holds a sentence. */
@Composable
internal fun SentenceCheckHost(
    activeVideoId: String?,
    lessonTitle: String?,
    onSave: suspend (startMs: Long, endMs: Long, text: String) -> String,
    onToggleMark: (startMs: Long, endMs: Long) -> Boolean,
) {
    val segment by sentenceToCheck.collectAsStateWithLifecycle()
    val shown = segment ?: return
    val videoId = activeVideoId ?: return
    val marks by sentenceReviewMarks.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var uzbek by remember(shown) { mutableStateOf(shown.translatedText.orEmpty()) }
    var message by remember(shown) { mutableStateOf<String?>(null) }
    val marked = sentenceMarked(marks, videoId, shown)
    val reviewText =
        reviewFileText(lessonTitle, videoId, listOf(ReviewSentence(shown.startMs, shown.endMs, shown.originalText, uzbek)))
    AlertDialog(
        onDismissRequest = { sentenceToCheck.value = null },
        title = { Text("Gapni tekshirish · ${reviewTime(shown.startMs).substringBeforeLast(',')}") },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Inglizcha (eshitilgani)", style = MaterialTheme.typography.labelLarge)
                SelectionContainer { Text(shown.originalText, style = MaterialTheme.typography.bodyMedium) }
                OutlinedTextField(
                    value = uzbek,
                    onValueChange = { uzbek = it },
                    label = { Text("O‘zbekcha tarjima") },
                    modifier = Modifier.fillMaxWidth().testTag("sentence_check_uzbek"),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("SHOKALON", reviewText)))
                            message = "Gap (EN + UZ) buferga nusxalandi."
                        }
                    }) { Text("Nusxa") }
                    OutlinedButton(onClick = {
                        val send =
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, CHECK_REQUEST + reviewText)
                            }
                        context.startActivity(Intent.createChooser(send, "Gapni tekshirish uchun ulashish"))
                    }) { Text("Ulashish") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            val pasted =
                                clipboard
                                    .getClipEntry()
                                    ?.clipData
                                    ?.takeIf { it.itemCount > 0 }
                                    ?.getItemAt(0)
                                    ?.coerceToText(context)
                                    ?.toString()
                                    .orEmpty()
                            val corrected = pastedUzbek(pasted)
                            if (corrected.isBlank()) message = "Buferda matn yo‘q." else uzbek = corrected
                        }
                    }) { Text("Buferdan qo‘yish") }
                    OutlinedButton(onClick = {
                        message = if (onToggleMark(shown.startMs, shown.endMs)) MARKED else UNMARKED
                    }) { Text(if (marked) "Ro‘yxatdan olish" else "Tekshirish ro‘yxatiga") }
                }
                Hint(
                    "Ro‘yxatdagi gaplar “Dars” ekranidagi “Tekshirish fayli” bilan bitta faylga chiqadi; " +
                        "tuzatilgan fayl o‘sha yerda qayta yuklanadi.",
                )
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold) }
            }
        },
        confirmButton = {
            Button(
                enabled = uzbek.isNotBlank() && uzbek.trim() != shown.translatedText?.trim(),
                onClick = {
                    scope.launch {
                        message =
                            try {
                                onSave(shown.startMs, shown.endMs, uzbek).also { sentenceToCheck.value = null }
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (error: IllegalStateException) {
                                error.message
                            } catch (error: IllegalArgumentException) {
                                error.message
                            }
                    }
                },
                modifier = Modifier.testTag("sentence_check_save"),
            ) { Text("Saqlash") }
        },
        dismissButton = { TextButton(onClick = { sentenceToCheck.value = null }) { Text("Yopish") } },
    )
}

/** The Uzbek of a pasted answer: the text after "UZ:" when the answer repeats the block, else all of it. */
internal fun pastedUzbek(pasted: String): String {
    val line = pasted.lineSequence().firstOrNull { it.trimStart().startsWith("UZ:") }
    return (line?.trimStart()?.removePrefix("UZ:") ?: pasted).trim()
}

private const val MARKED = "Tekshirish ro‘yxatiga qo‘shildi."
private const val UNMARKED = "Ro‘yxatdan olindi."
private const val CHECK_REQUEST =
    "Quyidagi ultratovush (UZI) ma’ruzasi gapining o‘zbekcha tarjimasini tibbiy aniqlik, atamalar va " +
        "grammatika bo‘yicha tekshiring. Javobda faqat tuzatilgan “UZ:” qatorini qaytaring.\n\n"

/**
 * "Gaplarni tekshirish fayli" on the "Dars" screen: the lesson's marked sentences (all of them when none is
 * marked) go out as one text file to check elsewhere, and the corrected file or copied text comes back.
 */
@Composable
internal fun SentenceReviewCard(
    activeVideoId: String?,
    export: suspend () -> SubtitleExport,
    import: suspend (String) -> String,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val marks by sentenceReviewMarks.collectAsStateWithLifecycle()
    val count = activeVideoId?.let { marks[it]?.size } ?: 0
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<SubtitleExport?>(null) }

    fun work(block: suspend () -> String) {
        scope.launch {
            message =
                try {
                    block()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: IllegalStateException) {
                    error.message
                } catch (error: IllegalArgumentException) {
                    error.message
                } catch (error: java.io.IOException) {
                    error.message ?: "Bajarilmadi."
                }
        }
    }
    val saver =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri ->
            val file = pending
            pending = null
            if (uri != null && file != null) {
                work {
                    withContext(Dispatchers.IO) {
                        context.contentResolver
                            .openOutputStream(uri, "wt")
                            ?.bufferedWriter(Charsets.UTF_8)
                            ?.use { it.write(file.text) }
                    }
                    "Tekshirish fayli saqlandi (${file.cueCount} gap)."
                }
            }
        }
    val opener =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                work {
                    val text =
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri)?.use { readBoundedText(it) }
                        } ?: throw java.io.IOException("Faylni ochib bo‘lmadi.")
                    import(text)
                }
            }
        }
    LessonCard(title = "Gaplarni tekshirish fayli", icon = Icons.Default.Flag) {
        Hint(REVIEW_HINT)
        Text(
            if (count > 0) "Ro‘yxatda: $count ta gap" else "Ro‘yxat bo‘sh: fayl butun darsni o‘z ichiga oladi.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        val enabled = activeVideoId != null
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = enabled, onClick = {
                work {
                    val file = export()
                    val send =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, file.title)
                            putExtra(Intent.EXTRA_TEXT, CHECK_REQUEST + file.text)
                        }
                    context.startActivity(Intent.createChooser(send, "Tekshirish uchun ulashish"))
                    "${file.cueCount} gap ulashishga tayyorlandi."
                }
            }, modifier = Modifier.testTag("review_share")) { Text("Ulashish") }
            OutlinedButton(enabled = enabled, onClick = {
                work {
                    val file = export()
                    pending = file
                    saver.launch(file.fileName.removeSuffix(".srt") + ".txt")
                    "Saqlash joyini tanlang."
                }
            }) { Text("Faylga saqlash") }
            OutlinedButton(enabled = enabled, onClick = {
                work {
                    val file = export()
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(file.fileName, file.text)))
                    "${file.cueCount} gap buferga nusxalandi."
                }
            }) { Text("Nusxa") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = enabled, onClick = { opener.launch(arrayOf("text/*", "application/octet-stream")) }) {
                Text("Tuzatilgan faylni yuklash")
            }
            OutlinedButton(enabled = enabled, onClick = {
                work { import(clipboardText(clipboard, context)) }
            }) { Text("Buferdan yuklash") }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

private const val REVIEW_HINT =
    "Dars paytida xato gapni barmoq bilan bosib turing va \u201CTekshirish ro\u2018yxatiga\u201D qo\u2018shing. " +
        "Bu yerda ro\u2018yxatdagi gaplar (EN + UZ, vaqti bilan) bitta faylga chiqadi: uni boshqa ilovada yoki qo\u2018lda " +
        "tuzating va qayta yuklang \u2014 har bir gap o\u2018z joyiga tushadi."

/** The text on the clipboard, or "" when there is none. */
private suspend fun clipboardText(
    clipboard: androidx.compose.ui.platform.Clipboard,
    context: android.content.Context,
): String =
    clipboard
        .getClipEntry()
        ?.clipData
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()
        .orEmpty()
