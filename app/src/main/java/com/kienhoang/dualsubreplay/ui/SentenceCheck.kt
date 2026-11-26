package com.kienhoang.dualsubreplay.ui

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.data.ReplaceRequest
import com.kienhoang.dualsubreplay.data.ReplaceScope
import com.kienhoang.dualsubreplay.data.ReviewSentence
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.WordTap
import com.kienhoang.dualsubreplay.data.readBoundedText
import com.kienhoang.dualsubreplay.data.reviewFileText
import com.kienhoang.dualsubreplay.data.reviewTime
import com.kienhoang.dualsubreplay.dubbing.DubbingSettings
import com.kienhoang.dualsubreplay.translation.DOCTOR_SHARE_PROMPT
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
    onClaudeFix: SentenceFixer? = null,
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
                TranslationEditor(
                    ReviewSentence(shown.startMs, shown.endMs, shown.originalText, uzbek),
                    uzbek,
                    onUzbek = { uzbek = it },
                    onMessage = { message = it },
                    fixer = onClaudeFix,
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
                Hint(MARK_LIST_HINT)
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold) }
            }
        },
        confirmButton = {
            Button(
                enabled = uzbek.trim() != shown.translatedText?.trim(),
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
            ) { Text(if (uzbek.isBlank()) "Gapni o\u2018chirish" else "Saqlash") }
        },
        dismissButton = { TextButton(onClick = { sentenceToCheck.value = null }) { Text("Yopish") } },
    )
}

private const val MARK_LIST_HINT =
    "Ro‘yxatdagi gaplar “Dars” ekranidagi “Tekshirish fayli” bilan bitta faylga chiqadi; " +
        "tuzatilgan fayl o‘sha yerda qayta yuklanadi."

/** The Uzbek of a pasted answer: the text after "UZ:" when the answer repeats the block, else all of it. */
internal fun pastedUzbek(pasted: String): String {
    val line = pasted.lineSequence().firstOrNull { it.trimStart().startsWith("UZ:") }
    return (line?.trimStart()?.removePrefix("UZ:") ?: pasted).trim()
}

private const val MARKED = "Tekshirish ro‘yxatiga qo‘shildi."
private const val UNMARKED = "Ro‘yxatdan olindi."
private val CHECK_REQUEST = DOCTOR_SHARE_PROMPT + "\n\n"

/**
 * "Gaplarni tekshirish fayli" on the "Dars" screen: the lesson's marked sentences (all of them when none is
 * marked) go out as one text file to check elsewhere, and the corrected file or copied text comes back.
 */
@Composable
internal fun SentenceReviewCard(
    activeVideoId: String?,
    export: suspend () -> SubtitleExport,
    import: suspend (String) -> String,
    claudeFix: ClaudeFixActions? = null,
) {
    var fixing by remember { mutableStateOf(false) }
    if (fixing && claudeFix != null) ClaudeFixesDialog(claudeFix) { fixing = false }
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
        if (claudeFix != null) {
            Button(enabled = enabled, onClick = { fixing = true }, modifier = Modifier.testTag("review_claude_fix")) {
                Text(if (count > 0) "Claude bilan tuzatish ($count gap)" else "Claude bilan butun darsni tekshirish")
            }
            Hint("Claude API kaliti bilan: o‘zgargan gaplar eski va yangi ko‘rinishda chiqadi, keraklilarini tanlab qo‘llaysiz.")
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

/**
 * "Takrorlanuvchi xatoni almashtirish": a mistake that recurs (a wrong term, a misheard word) is replaced in
 * the open lesson's translation or in every stored translation at once, instead of sentence by sentence.
 */
@Composable
internal fun ReplaceAllCard(
    replace: suspend (ReplaceRequest, apply: Boolean) -> String,
    undo: suspend () -> String,
) {
    val scope = rememberCoroutineScope()
    var find by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var wholeWord by remember { mutableStateOf(true) }
    var where by remember { mutableStateOf(ReplaceScope.LESSON) }
    var message by remember { mutableStateOf<String?>(null) }

    fun run(block: suspend () -> String) {
        scope.launch {
            message =
                try {
                    block()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: IllegalArgumentException) {
                    error.message
                } catch (error: IllegalStateException) {
                    error.message
                } catch (error: java.io.IOException) {
                    error.message
                }
        }
    }
    LessonCard(title = "Takrorlanuvchi xatoni almashtirish", icon = Icons.Default.FindReplace) {
        Hint(REPLACE_HINT)
        OutlinedTextField(
            value = find,
            onValueChange = { find = it },
            singleLine = true,
            label = { Text("Xato so\u2018z yoki so\u2018z birikmasi") },
            modifier = Modifier.fillMaxWidth().testTag("replace_find"),
        )
        OutlinedTextField(
            value = replacement,
            onValueChange = { replacement = it },
            singleLine = true,
            label = { Text("To\u2018g\u2018risi") },
            modifier = Modifier.fillMaxWidth().testTag("replace_with"),
        )
        LabeledSwitch("Faqat butun so\u2018z (so\u2018z ichidan qidirmasin)", wholeWord) { wholeWord = it }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(ReplaceScope.LESSON, ReplaceScope.SECTION, ReplaceScope.ALL).forEach { option ->
                FilterChip(selected = where == option, onClick = { where = option }, label = { Text(option.label) })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = find.isNotBlank(), onClick = {
                run { replace(ReplaceRequest(find, replacement.trim(), wholeWord, where), false) }
            }, modifier = Modifier.testTag("replace_preview")) { Text("Oldindan ko\u2018rish") }
            Button(enabled = find.isNotBlank(), onClick = {
                run { replace(ReplaceRequest(find, replacement.trim(), wholeWord, where), true) }
            }, modifier = Modifier.testTag("replace_all")) { Text("Hammasini almashtirish") }
            TextButton(onClick = { run { undo() } }) { Text("Bekor qilish") }
        }
        message?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodyMedium) } }
    }
}

private const val REPLACE_HINT =
    "Bir xil xato ko\u2018p joyda bo\u2018lsa, uni bir yo\u2018la almashtiring. Bitta so\u2018z ko\u2018p ma\u2019noli bo\u2018lsa, " +
        "so\u2018z birikmasini yozing (masalan, \u201Cakustik soya\u201D): " +
        "birikma ikki qatorga bo\u2018linib qolgan bo\u2018lsa ham topiladi. " +
        "Avval \u201COldindan ko\u2018rish\u201D bilan qayerlar o\u2018zgarishini tekshiring. Kelajakdagi Claude tarjimalari " +
        "uchun atamani \u201CO\u2018sib boruvchi lug\u2018at\u201Dga ham qo\u2018shing."

@Composable
private fun LabeledSwitch(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** The word of the Uzbek translation the doctor tapped, for "So‘zni almashtirish"; null when closed. */
internal val wordToReplace = MutableStateFlow<WordTap?>(null)

/**
 * "So‘zni almashtirish": a tapped word of the translation (or a phrase built from the sentence's words) is
 * replaced in the chosen scope, or only said differently by the voice.
 */
@Composable
internal fun WordReplaceHost(
    replace: suspend (ReplaceRequest, apply: Boolean) -> String,
    undo: suspend () -> String,
    onSpeechRule: (written: String, spoken: String) -> Unit,
) {
    val tap by wordToReplace.collectAsStateWithLifecycle()
    val shown = tap ?: return
    val sentence = shown.segment ?: return
    val scope = rememberCoroutineScope()
    var find by remember(shown) { mutableStateOf(shown.token.text.trim('.', ',', ';', ':', '!', '?', '“', '”', '"')) }
    var replacement by remember(shown) { mutableStateOf("") }
    var where by remember(shown) { mutableStateOf(ReplaceScope.LESSON) }
    var message by remember(shown) { mutableStateOf<String?>(null) }
    var working by remember(shown) { mutableStateOf(false) }
    val words =
        remember(sentence) {
            sentence.translatedText
                .orEmpty()
                .split(Regex("\\s+"))
                .map { it.trim('.', ',', ';', ':', '!', '?') }
                .filter(String::isNotBlank)
        }

    fun request() = ReplaceRequest(find, replacement.trim(), true, where, sentence.startMs, sentence.endMs)

    fun run(block: suspend () -> String) {
        working = true
        message = null
        scope.launch {
            message =
                try {
                    block()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: IllegalArgumentException) {
                    error.message
                } catch (error: IllegalStateException) {
                    error.message
                } catch (error: java.io.IOException) {
                    error.message
                }
            working = false
        }
    }
    AlertDialog(
        onDismissRequest = { wordToReplace.value = null },
        title = { Text("So‘zni almashtirish") },
        text = {
            Column(
                Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                message?.let { ResultNote(it) }
                if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(sentence.translatedText.orEmpty(), style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = {
                        wordToReplace.value = null
                        sentenceToCheck.value = sentence
                    },
                    modifier = Modifier.fillMaxWidth().testTag("word_replace_whole_sentence"),
                ) { Text(WHOLE_SENTENCE_BUTTON) }
                Hint("Birikma kerak bo‘lsa, qo‘shni so‘zlarni bosing:")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    words.forEach { word ->
                        AssistChip(onClick = { find = (find.trim() + " " + word).trim() }, label = { Text(word) })
                    }
                }
                OutlinedTextField(
                    value = find,
                    onValueChange = { find = it },
                    singleLine = true,
                    label = { Text("Almashtiriladigan so‘z / birikma") },
                    modifier = Modifier.fillMaxWidth().testTag("word_replace_find"),
                )
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    singleLine = true,
                    label = { Text("Yangisi") },
                    modifier = Modifier.fillMaxWidth().testTag("word_replace_with"),
                )
                Text("Qayerda almashtirilsin?", style = MaterialTheme.typography.labelLarge)
                ReplaceScope.entries.forEach { option ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = where == option, onClick = { where = option })
                        Text(option.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        enabled = find.isNotBlank(),
                        onClick = { run { replace(request(), false) } },
                    ) { Text("Oldindan ko\u2018rish") }
                    Button(enabled = find.isNotBlank() && replacement.isNotBlank(), onClick = { run { replace(request(), true) } }) {
                        Text("Almashtirish")
                    }
                    TextButton(onClick = { run { undo() } }) { Text("Bekor qilish") }
                }
                OutlinedButton(
                    enabled = find.isNotBlank() && replacement.isNotBlank(),
                    onClick = {
                        onSpeechRule(find, replacement)
                        message = "Endi ovoz “${find.trim()}” ni “${replacement.trim()}” deb o‘qiydi; matn o‘zgarmaydi."
                    },
                ) { Text("Faqat ovozda shunday o‘qilsin") }
            }
        },
        confirmButton = { TextButton(onClick = { wordToReplace.value = null }) { Text("Yopish") } },
    )
}

private const val WHOLE_SENTENCE_BUTTON = "Butun gapni tahrirlash: qo\u2018lda, Claude yoki ma\u2019nosini yozib"

/** The result of a replacement, at the top of the dialog where it is seen at once. */
@Composable
private fun ResultNote(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag("word_replace_result"),
    ) {
        SelectionContainer {
            Text(text, Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** "Talaffuz lug‘ati": the voice's pronunciation rules, each removable. */
@Composable
internal fun SpeechLexiconCard() {
    val rules by DubbingSettings.speechLexicon.collectAsStateWithLifecycle()
    LessonCard(title = "Talaffuz lug‘ati", icon = Icons.Default.RecordVoiceOver) {
        Hint(
            "Ovoz qanday o‘qishi kerak bo‘lgan so‘zlar (masalan, UTT → ultratovush tekshiruvi). Matn o‘zgarmaydi. " +
                "Qoida subtitrdagi o‘zbekcha so‘zni bosib qo‘shiladi.",
        )
        if (rules.isEmpty()) Hint("Hali qoida yo‘q.")
        rules.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (written, spoken) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$written → $spoken", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { DubbingSettings.removeSpeechRule(written) }) { Text("O‘chirish") }
            }
        }
    }
}
