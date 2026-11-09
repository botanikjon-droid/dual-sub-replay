package com.kienhoang.dualsubreplay.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.data.ApiUsageLog
import com.kienhoang.dualsubreplay.data.RawCaptionCue
import com.kienhoang.dualsubreplay.data.apiUsageLine
import com.kienhoang.dualsubreplay.data.apiUsageTotals
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Tarjimani tekshirish va tahrirlash": the open lesson's stored translation can be corrected line by line
 * in the app, copied or shared as an .srt file or as an English/Uzbek text (to check it in another app or
 * with another AI), or saved as a file. Corrections are stored at once and shown in the lesson.
 */
@Composable
internal fun TranslationCheckCard(
    activeVideoId: String?,
    storedCues: () -> List<RawCaptionCue>?,
    save: suspend (List<RawCaptionCue>) -> Int,
    srtExport: suspend () -> SubtitleExport,
    bilingualExport: suspend () -> SubtitleExport,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<List<RawCaptionCue>?>(null) }
    var pendingSave by remember { mutableStateOf<SubtitleExport?>(null) }

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
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
            val export = pendingSave
            pendingSave = null
            if (uri != null && export != null) {
                work {
                    withContext(Dispatchers.IO) {
                        context.contentResolver
                            .openOutputStream(uri, "wt")
                            ?.bufferedWriter(Charsets.UTF_8)
                            ?.use { it.write(export.text) }
                    }
                    "${export.fileName} saqlandi."
                }
            }
        }
    LessonCard(title = "Tarjimani tekshirish va tahrirlash", icon = Icons.Default.EditNote) {
        Hint(
            "Saqlangan o‘zbekcha tarjimani shu yerda qatorma-qator tuzating yoki nusxa olib boshqa ilovada " +
                "(masalan, Claude yoki ChatGPT'da) tekshiring. “EN + UZ” nusxasida har bir gap inglizchasi bilan yonma-yon turadi.",
        )
        LessonApiUsage(activeVideoId)
        val enabled = activeVideoId != null
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(
                enabled = enabled,
                onClick = { storedCues()?.let { editing = it } ?: run { message = "Bu darsning saqlangan tarjimasi yo‘q." } },
                modifier = Modifier.testTag("translation_edit"),
            ) { Text("Tahrirlash") }
            OutlinedButton(enabled = enabled, onClick = {
                work {
                    val export = bilingualExport()
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(export.fileName, export.text)))
                    "${export.cueCount} gap (EN + UZ) buferga nusxalandi."
                }
            }) { Text("EN + UZ nusxa") }
            OutlinedButton(enabled = enabled, onClick = {
                work {
                    val export = srtExport()
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(export.fileName, export.text)))
                    "${export.cueCount} qator (SRT) buferga nusxalandi."
                }
            }) { Text("SRT nusxa") }
            OutlinedButton(enabled = enabled, onClick = {
                work {
                    val export = bilingualExport()
                    share(context, export)
                    "Ulashish oynasi ochildi."
                }
            }) { Text("Ulashish") }
            OutlinedButton(enabled = enabled, onClick = {
                work {
                    val export = srtExport()
                    pendingSave = export
                    saver.launch(export.fileName)
                    "Saqlash joyini tanlang."
                }
            }) { Text("Faylga saqlash") }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
    editing?.let { cues ->
        TranslationEditor(cues, onDismiss = { editing = null }) { edited ->
            editing = null
            work { "Tarjima saqlandi: ${save(edited)} qator." }
        }
    }
}

private fun share(
    context: Context,
    export: SubtitleExport,
) {
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, export.title ?: export.fileName)
            putExtra(Intent.EXTRA_TEXT, export.text)
        }
    context.startActivity(Intent.createChooser(send, "Tarjimani tekshirish uchun ulashish"))
}

private fun clock(ms: Long): String {
    val seconds = ms / MS_PER_SECOND
    return "%d:%02d".format(seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
}

private const val MS_PER_SECOND = 1_000
private const val SECONDS_PER_MINUTE = 60

/** Every stored line with its time, editable; a search box finds a word. Nothing changes until "Saqlash". */
@Composable
private fun TranslationEditor(
    cues: List<RawCaptionCue>,
    onDismiss: () -> Unit,
    onSave: (List<RawCaptionCue>) -> Unit,
) {
    val edits = remember { mutableStateMapOf<Int, String>() }
    var filter by remember { mutableStateOf("") }
    val shown =
        remember(filter, cues) {
            val needle = filter.trim()
            cues.withIndex().filter { (index, cue) -> needle.isEmpty() || (edits[index] ?: cue.text).contains(needle, ignoreCase = true) }
        }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("translation_editor"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onDismiss,
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Saqlamasdan chiqish") }
                    Column(Modifier.weight(1f)) {
                        Text("Tarjimani tahrirlash", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Hint("${cues.size} qator · o‘zgartirilgan: ${edits.size}")
                    }
                    Button(
                        onClick = { onSave(cues.mapIndexed { index, cue -> edits[index]?.let { cue.copy(text = it.trim()) } ?: cue }) },
                        enabled = edits.isNotEmpty(),
                        modifier = Modifier.padding(end = 8.dp).testTag("translation_editor_save"),
                    ) { Text("Saqlash") }
                }
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    label = { Text("So‘z bo‘yicha qidirish") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(shown, key = { _, item -> item.index }) { _, (index, cue) ->
                        OutlinedTextField(
                            value = edits[index] ?: cue.text,
                            onValueChange = { text -> if (text == cue.text) edits.remove(index) else edits[index] = text },
                            label = { Text("${index + 1} · ${clock(cue.startMs)}") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/** The open lesson's API calls and tokens from the usage log (nothing when it made none). */
@Composable
private fun LessonApiUsage(videoId: String?) {
    val entries by ApiUsageLog.entries.collectAsStateWithLifecycle()
    val totals = remember(entries, videoId) { videoId?.let { apiUsageTotals(entries, it) } }
    if (totals == null || totals.calls == 0) return
    Text(apiUsageLine(totals), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("lesson_api_usage"))
}
