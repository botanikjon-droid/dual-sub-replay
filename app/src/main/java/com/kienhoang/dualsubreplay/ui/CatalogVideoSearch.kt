package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.data.CatalogSection
import com.kienhoang.dualsubreplay.data.CatalogVideo
import com.kienhoang.dualsubreplay.data.UziCatalogStore
import com.kienhoang.dualsubreplay.data.YouTubeSearchResult
import com.kienhoang.dualsubreplay.data.addCatalogVideo
import com.kienhoang.dualsubreplay.data.replaceCatalogVideo
import kotlinx.coroutines.launch

/*
 * Growing the catalog from inside SHOKALON: a YouTube search whose result is added to a chosen section and
 * topic, or put in the place of a lecture that cannot be used (another language, removed, poor quality).
 * Changes go to the doctor's own copy of the catalog (UziCatalogStore), which "Asl holiga qaytarish" resets.
 */

/** What a catalog search is for. */
internal sealed interface CatalogEdit {
    /** A new lecture, in [section] when one is given (else the doctor picks it). */
    data class Add(
        val section: String?,
    ) : CatalogEdit

    /** Another lecture in the place of [video] (same section, topic and position). */
    data class Replace(
        val video: CatalogVideo,
    ) : CatalogEdit
}

/** English words that find lectures of a section on YouTube (its name is Uzbek). */
internal fun sectionSearchHint(sectionName: String?): String =
    when (sectionName?.let(::sectionArtKey)) {
        null -> "ultrasound lecture"
        "physics" -> "ultrasound physics lecture"
        "method" -> "ultrasound report writing scanning technique"
        "abdomen" -> "abdominal ultrasound lecture"
        "gyn" -> "gynecologic ultrasound lecture"
        "uro" -> "renal bladder prostate ultrasound lecture"
        "neuro" -> "neonatal cranial ultrasound neurosonography"
        "peds" -> "pediatric ultrasound lecture"
        "heart" -> "echocardiography lecture"
        "thyroid" -> "thyroid ultrasound TIRADS lecture"
        "breast" -> "breast ultrasound BIRADS lecture"
        "superficial" -> "superficial structures lymph node ultrasound"
        "fetal" -> "fetal anomaly scan ultrasound lecture"
        "vascular" -> "vascular doppler ultrasound lecture"
        "tcd" -> "transcranial doppler lecture"
        "msk" -> "musculoskeletal ultrasound lecture"
        "pocus" -> "POCUS emergency ultrasound lecture"
        "elasto" -> "elastography contrast enhanced ultrasound lecture"
        else -> "ultrasound lecture"
    }

/** The search for [edit], then the step that puts the chosen video into the catalog. */
@Composable
internal fun CatalogVideoSearch(
    edit: CatalogEdit,
    sections: List<CatalogSection>?,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    var chosen by remember { mutableStateOf<YouTubeSearchResult?>(null) }
    val (title, subtitle, query) =
        when (edit) {
            is CatalogEdit.Add ->
                Triple(
                    "Mundarijaga video qo‘shish",
                    edit.section ?: "YouTube'dan qidiring va bo‘limni tanlang",
                    sectionSearchHint(edit.section),
                )
            is CatalogEdit.Replace -> Triple("Darsni almashtirish", "O‘rniga: ${edit.video.title}", edit.video.title)
        }
    YouTubeSearchDialog(
        title = title,
        subtitle = subtitle,
        initialQuery = query,
        excludeVideoId = (edit as? CatalogEdit.Replace)?.video?.videoId,
        sections = sections,
        onPick = { chosen = it },
        onDismiss = onDismiss,
    )
    chosen?.let { result ->
        when (edit) {
            is CatalogEdit.Add ->
                AddToCatalogDialog(
                    result,
                    sections.orEmpty(),
                    edit.section,
                    onDismiss = { chosen = null },
                    onDone = onDone,
                )
            is CatalogEdit.Replace -> ReplaceInCatalogDialog(result, edit.video, onDismiss = { chosen = null }, onDone = onDone)
        }
    }
}

/** The topic a lecture goes to when the doctor names none, so adding never stops on an empty field. */
internal const val DEFAULT_ADDED_TOPIC = "Qo\u2018shimcha darslar"

/** The topic name to store: the doctor's, or [DEFAULT_ADDED_TOPIC] when he left it empty. */
internal fun chosenTopic(typed: String): String = typed.trim().ifEmpty { DEFAULT_ADDED_TOPIC }

@Composable
private fun AddToCatalogDialog(
    result: YouTubeSearchResult,
    sections: List<CatalogSection>,
    presetSection: String?,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var section by remember { mutableStateOf(presetSection ?: sections.firstOrNull()?.name.orEmpty()) }
    var changeSection by remember { mutableStateOf(presetSection == null) }
    var topic by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val topics = sections.firstOrNull { it.name == section }?.topics.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Qaysi mavzuga qo\u2018shilsin?") },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) }
                Text(result.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Bo\u2018lim: $section", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    if (!changeSection) TextButton(onClick = { changeSection = true }) { Text("O\u2018zgartirish") }
                }
                if (changeSection) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        sections.forEach { option ->
                            FilterChip(selected = option.name == section, onClick = {
                                section = option.name
                                topic = ""
                            }, label = { Text(option.name) })
                        }
                    }
                }
                Text("Mavzu", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    topics.forEach { option ->
                        FilterChip(selected = option.name == topic, onClick = { topic = option.name }, label = { Text(option.name) })
                    }
                }
                OutlinedTextField(
                    value = topic,
                    onValueChange = { topic = it },
                    singleLine = true,
                    label = { Text("yoki yangi mavzu nomi") },
                    modifier = Modifier.testTag("catalog_add_topic"),
                )
                Hint("Mavzu tanlanmasa, video \u201C$DEFAULT_ADDED_TOPIC\u201D mavzusiga qo\u2018shiladi.")
            }
        },
        confirmButton = {
            Button(onClick = {
                scope.launch {
                    try {
                        val name = chosenTopic(topic)
                        UziCatalogStore.change { addCatalogVideo(it, section, name, CatalogVideo(result.videoId, result.title)) }
                        onDone("Qo\u2018shildi: $section \u203A $name")
                    } catch (failure: IllegalArgumentException) {
                        error = failure.message
                    }
                }
            }, modifier = Modifier.testTag("catalog_add_confirm")) { Text("Qo\u2018shish") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Bekor qilish") } },
    )
}

@Composable
private fun ReplaceInCatalogDialog(
    result: YouTubeSearchResult,
    old: CatalogVideo,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Darsni almashtirasizmi?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Eski: ${old.title}", style = MaterialTheme.typography.bodySmall)
                Text("Yangi: ${result.title} (${result.duration})", fontWeight = FontWeight.SemiBold)
                Hint("Yangi video eskisining o‘rnida turadi: shu bo‘lim, shu mavzu, shu tartib.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                scope.launch {
                    try {
                        UziCatalogStore.change { replaceCatalogVideo(it, old.videoId, CatalogVideo(result.videoId, result.title)) }
                        onDone("Almashtirildi: ${result.title}")
                    } catch (failure: IllegalArgumentException) {
                        error = failure.message
                    }
                }
            }, modifier = Modifier.testTag("catalog_replace_confirm")) { Text("Almashtirish") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Bekor qilish") } },
    )
}
