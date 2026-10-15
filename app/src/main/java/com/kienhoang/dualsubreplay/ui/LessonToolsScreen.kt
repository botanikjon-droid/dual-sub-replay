package com.kienhoang.dualsubreplay.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kienhoang.dualsubreplay.data.readBoundedText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Larger transcripts are saved as a file; Android rejects very large clipboard entries. */
internal const val MAX_CLIPBOARD_SUBTITLE_CHARS = 400_000

/**
 * Lesson tools: open a lesson by its video link, copy or save the open video's transcript as an
 * .srt file, and import a translated .srt/.vtt that replaces machine translation for that video.
 */
@Composable
@Suppress("LongMethod")
internal fun LessonToolsScreen(
    activeVideoId: String?,
    importedTranslationCues: Int,
    onOpenLink: (String) -> Boolean,
    exportSubtitles: suspend () -> SubtitleExport,
    shareForTranslation: suspend () -> SubtitleExport,
    importTranslation: suspend (String) -> Int,
    onClearImport: () -> Unit,
    onDismiss: () -> Unit,
    claudeRun: ClaudeRunState = ClaudeRunState(),
    onClaudeTranslate: () -> Unit = {},
    onGeminiTranslate: () -> Unit = {},
    onClaudeCancel: () -> Unit = {},
    lessonList: @Composable () -> Unit = {},
    notesCard: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var link by rememberSaveable { mutableStateOf("") }
    var linkError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingSave by remember { mutableStateOf<SubtitleExport?>(null) }

    fun work(block: suspend () -> String) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            message =
                try {
                    block()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    error.message ?: "Amal bajarilmadi. Qayta urinib ko‘ring."
                } finally {
                    busy = false
                }
        }
    }

    fun openLink(text: String) {
        if (onOpenLink(text.trim())) {
            linkError = null
            onDismiss()
        } else {
            linkError = "Bu YouTube video havolasi emas."
        }
    }

    suspend fun clipboardText(): String =
        clipboard
            .getClipEntry()
            ?.clipData
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            .orEmpty()

    val saver =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
            val export = pendingSave
            pendingSave = null
            if (uri != null && export != null) {
                work {
                    writeText(context, uri, export.text)
                    "${export.fileName} saqlandi (${export.cueCount} qator)."
                }
            }
        }
    val importer =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                work {
                    val count = importTranslation(readText(context, uri))
                    "Tarjima yuklandi: $count qator. Subtitrlar qayta tayyorlanmoqda."
                }
            }
        }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize().testTag("lesson_tools_screen"),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Orqaga")
                    }
                    Text("Dars: havola va subtitr", style = MaterialTheme.typography.titleLarge)
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LessonCard(title = "Dars havolasi", icon = Icons.Default.Link) {
                        OutlinedTextField(
                            value = link,
                            onValueChange = {
                                link = it
                                linkError = null
                            },
                            modifier = Modifier.fillMaxWidth().testTag("lesson_link_field"),
                            label = { Text("YouTube video havolasi") },
                            placeholder = { Text("https://youtu.be/…") },
                            singleLine = true,
                            isError = linkError != null,
                            supportingText = { linkError?.let { Text(it) } },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { openLink(link) }),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    link = clipboardText().trim()
                                    linkError = null
                                }
                            }) { Text("Buferdan qo‘yish") }
                            Button(
                                onClick = { openLink(link) },
                                enabled = link.isNotBlank(),
                                modifier = Modifier.testTag("open_lesson_link"),
                            ) { Text("Darsni ochish") }
                        }
                    }

                    lessonList()

                    SubtitleOutCard(
                        hasVideo = activeVideoId != null,
                        enabled = activeVideoId != null && !busy,
                        onShare = {
                            work {
                                val request = shareForTranslation()
                                whenNotTooLong(request) {
                                    shareText(context, request)
                                    "${request.cueCount} qator tarjimaga ulashildi."
                                }
                            }
                        },
                        onCopy = {
                            work {
                                val export = exportSubtitles()
                                whenNotTooLong(export) {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(export.fileName, export.textWithHeader)))
                                    "${export.cueCount} qator buferga nusxalandi."
                                }
                            }
                        },
                        onSave = {
                            work {
                                val export = exportSubtitles()
                                pendingSave = export
                                saver.launch(export.fileName)
                                "Saqlash joyini tanlang: ${export.fileName}"
                            }
                        },
                    )

                    ClaudeTranslateCard(
                        activeVideoId = activeVideoId,
                        run = claudeRun,
                        onTranslate = onClaudeTranslate,
                        onGeminiTranslate = onGeminiTranslate,
                        onCancel = onClaudeCancel,
                    )
                    notesCard()
                    LearnedGlossaryCard()

                    LessonCard(title = "Tarjima subtitrini yuklash", icon = Icons.Default.Translate) {
                        Hint(
                            when {
                                activeVideoId == null -> "Avval dars videosini oching."
                                importedTranslationCues > 0 ->
                                    "Bu dars uchun yuklangan tarjima: $importedTranslationCues qator. " +
                                        "U mashina tarjimasi o‘rnida ko‘rsatiladi va ovozda o‘qiladi."
                                else -> "Tarjima qilingan SRT yoki VTT faylni yuklang. U mashina tarjimasini almashtiradi."
                            },
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled = activeVideoId != null && !busy,
                                modifier = Modifier.testTag("import_translation_file"),
                                onClick = { importer.launch(arrayOf("*/*")) },
                            ) { Text("Fayl tanlash") }
                            OutlinedButton(
                                enabled = activeVideoId != null && !busy,
                                onClick = {
                                    work {
                                        val count = importTranslation(clipboardText())
                                        "Tarjima buferdan yuklandi: $count qator."
                                    }
                                },
                            ) { Text("Buferdan yuklash") }
                            if (importedTranslationCues > 0) {
                                TextButton(enabled = !busy, onClick = {
                                    onClearImport()
                                    message = "Yuklangan tarjima o‘chirildi."
                                }) { Text("O‘chirish", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }

                    if (busy) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Bajarilmoqda…")
                        }
                    }
                    message?.let { Text(it, modifier = Modifier.testTag("lesson_tools_message")) }
                }
            }
        }
    }
}

@Composable
private fun SubtitleOutCard(
    hasVideo: Boolean,
    enabled: Boolean,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onSave: () -> Unit,
) {
    LessonCard(title = "Subtitrni ko\u2018chirish", icon = Icons.Default.ClosedCaption) {
        Hint(
            if (!hasVideo) {
                "Avval dars videosini oching."
            } else {
                "\u201CUlashish\u201D subtitrni video nomi, havolasi va tarjima ko\u2018rsatmasi bilan Claude yoki ChatGPT\u2019ga " +
                    "yuboradi. Javobini yana \u201CUlashish\u201D orqali Dual Replay\u2019ga qaytaring \u2014 tarjima shu darsga " +
                    "yuklanadi. Uzun javob qismlarga bo\u2018linsa, har bir qismini ketma-ket yuklang."
            },
        )
        Button(
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("share_subtitles_for_translation"),
            onClick = onShare,
        ) {
            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Ulashish (Claude, ChatGPT)")
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = enabled, modifier = Modifier.testTag("copy_subtitles"), onClick = onCopy) {
                Text("Nusxalash")
            }
            OutlinedButton(enabled = enabled, onClick = onSave) { Text("Fayl sifatida saqlash") }
        }
    }
}

@Composable
internal fun LessonCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            content()
        }
    }
}

@Composable
internal fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private suspend fun readText(
    context: Context,
    uri: Uri,
): String =
    withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use(::readBoundedText) ?: error("Faylni ochib bo\u2018lmadi.")
    }

/** Android rejects very large clipboard and share payloads, so long transcripts go to a file instead. */
private suspend fun whenNotTooLong(
    export: SubtitleExport,
    action: suspend () -> String,
): String =
    if (export.text.length > MAX_CLIPBOARD_SUBTITLE_CHARS) {
        "Subtitr juda uzun. \u201CFayl sifatida saqlash\u201D tugmasidan foydalaning."
    } else {
        action()
    }

/** Opens the Android share sheet so the request can go to the Claude or ChatGPT app. */
private fun shareText(
    context: Context,
    request: SubtitleExport,
) {
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, request.title ?: request.fileName)
            putExtra(Intent.EXTRA_TEXT, request.text)
        }
    context.startActivity(Intent.createChooser(send, "Tarjima uchun ulashish"))
}

private suspend fun writeText(
    context: Context,
    uri: Uri,
    text: String,
) = withContext(Dispatchers.IO) {
    context.contentResolver
        .openOutputStream(uri, "wt")
        ?.bufferedWriter(Charsets.UTF_8)
        ?.use { it.write(text) }
        ?: error("Faylga yozib bo‘lmadi.")
}
