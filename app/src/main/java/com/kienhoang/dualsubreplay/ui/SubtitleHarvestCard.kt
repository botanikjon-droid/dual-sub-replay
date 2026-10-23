package com.kienhoang.dualsubreplay.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.data.SubtitleHarvester
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * "SHOKALON: subtitrlarni yig‘ish": downloads the English subtitles of every catalog lecture on the phone and
 * saves them as one ZIP for translation (SHOKALON stages 4-6). The screen stays on while it runs. The ZIP of
 * finished Uzbek translations (stage 8) comes back through [onImportTranslations].
 */
@Composable
internal fun SubtitleHarvestCard(onImportTranslations: suspend (android.net.Uri) -> String = { "" }) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val state by SubtitleHarvester.state.collectAsStateWithLifecycle()
    var zipMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { SubtitleHarvester.refresh(context) }
    DisposableEffect(state.running) {
        view.keepScreenOn = state.running
        onDispose { view.keepScreenOn = false }
    }
    val saver =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) {
                scope.launch {
                    zipMessage =
                        try {
                            "ZIP saqlandi: ${SubtitleHarvester.writeZip(context, uri)} ta subtitr va manifest."
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (error: Exception) {
                            "ZIP saqlanmadi: ${error.message}"
                        }
                }
            }
        }
    var importing by remember { mutableStateOf(false) }
    val importer =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    importing = true
                    zipMessage = "Tarjimalar yuklanmoqda…"
                    zipMessage =
                        try {
                            onImportTranslations(uri)
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (error: Exception) {
                            "Tarjimalar yuklanmadi: ${error.message}"
                        } finally {
                            importing = false
                        }
                }
            }
        }
    val counts = state.counts
    LessonCard(title = "SHOKALON: subtitrlarni yig‘ish", icon = Icons.Default.Archive) {
        Hint(
            "Mundarijadagi barcha darslarning inglizcha subtitrini yuklab, SHK_kod_VID_ORIGINAL.srt nomi bilan " +
                "saqlaydi. Uzilsa, qayta bosganda to‘xtagan joyidan davom etadi. Ishlayotganda ekran o‘chmaydi. " +
                "Tayyor o‘zbekcha tarjimalar ZIP’i (SHK_…_UZ-LATIN.srt) bir bosishda o‘z darslariga joylanadi.",
        )
        if (counts.total > 0) {
            Text(
                "Darslar: ${counts.done}/${counts.total} · subtitr bor: ${counts.ok} · " +
                    "yo‘q: ${counts.noCaptions} · xato: ${counts.failed}",
                modifier = Modifier.testTag("harvest_counts"),
            )
            LinearProgressIndicator(
                progress = { counts.done.toFloat() / counts.total },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        state.current?.let { Hint("Yuklanmoqda: $it") }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.running) {
                OutlinedButton(onClick = SubtitleHarvester::cancel) { Text("To‘xtatish") }
            } else {
                Button(
                    onClick = { SubtitleHarvester.start(context, retryNoCaptions = false) },
                    modifier = Modifier.testTag("harvest_start"),
                ) { Text(if (counts.done == 0) "Yig‘ishni boshlash" else "Davom ettirish") }
                OutlinedButton(
                    enabled = counts.ok > 0,
                    onClick = { saver.launch(SubtitleHarvester.zipName()) },
                ) { Text("ZIP saqlash") }
                OutlinedButton(
                    enabled = !importing,
                    onClick = { importer.launch(ZIP_MIME_TYPES) },
                    modifier = Modifier.testTag("import_translation_zip"),
                ) { Text("Tarjimalar ZIP\u2019ini yuklash") }
                if (counts.noCaptions > 0) {
                    TextButton(onClick = { SubtitleHarvester.start(context, retryNoCaptions = true) }) {
                        Text("Subtitrsizlarni qayta tekshirish")
                    }
                }
            }
        }
        (zipMessage ?: state.message)?.let { Text(it, maxLines = 6, overflow = TextOverflow.Ellipsis) }
    }
}

/** File managers label ZIPs differently; octet-stream lets a ZIP saved from a chat be picked too. */
private val ZIP_MIME_TYPES = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
