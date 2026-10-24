package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.data.CatalogSection
import com.kienhoang.dualsubreplay.data.CatalogVideo
import com.kienhoang.dualsubreplay.data.UziCatalogStore
import com.kienhoang.dualsubreplay.data.YouTubeUrlParser
import com.kienhoang.dualsubreplay.data.addCatalogVideo
import com.kienhoang.dualsubreplay.data.moveCatalogSection
import com.kienhoang.dualsubreplay.data.moveCatalogVideo
import com.kienhoang.dualsubreplay.data.removeCatalogSection
import com.kienhoang.dualsubreplay.data.removeCatalogVideo
import kotlinx.coroutines.launch

/**
 * "Ultratovush mundarijasi": sections by examination, topics and English lectures; "Ochish" opens the lecture
 * in the app's YouTube page, "✓" marks lectures already translated. "✏️ O‘zgartirish" lets the doctor add,
 * delete and reorder; his catalog is kept on the phone and can be reset to the built-in one.
 */
@Composable
internal fun LessonCatalogCard(
    translatedVideoIds: Set<String>,
    knownTitles: Map<String, String>,
    activeVideoId: String?,
    onOpen: (String) -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { UziCatalogStore.load(context) }
    val sections by UziCatalogStore.sections.collectAsStateWithLifecycle()
    val edited by UziCatalogStore.edited.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var openSection by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<CatalogConfirm?>(null) }
    val change: ((List<CatalogSection>) -> List<CatalogSection>) -> Unit = { edit -> scope.launch { UziCatalogStore.change(edit) } }
    val shown = sections
    LessonCard(title = "Ultratovush mundarijasi", icon = Icons.AutoMirrored.Filled.List) {
        if (shown == null) {
            Hint("Yuklanmoqda…")
            return@LessonCard
        }
        CatalogToolbar(editing, edited, onToggle = { editing = !editing }, onReset = { confirm = CatalogConfirm.Reset })
        if (editing) AddLessonForm(shown, knownTitles) { section, topic, video -> change { addCatalogVideo(it, section, topic, video) } }
        if (shown.isEmpty()) Hint("Mundarija bo‘sh. «✏️ O‘zgartirish» orqali dars qo‘shing.")
        shown.forEachIndexed { index, section ->
            if (index > 0) HorizontalDivider()
            val expanded = openSection == section.name
            SectionHeader(
                section = section,
                translated = translatedVideoIds,
                expanded = expanded,
                editing = editing,
                onToggle = { openSection = if (expanded) null else section.name },
                onMove = { step -> change { moveCatalogSection(it, section.name, step) } },
                onDelete = { confirm = CatalogConfirm.Section(section.name) },
            )
            if (expanded) {
                SectionBody(section, translatedVideoIds, activeVideoId, editing, onOpen) { action ->
                    when (action) {
                        is VideoAction.Move -> change { moveCatalogVideo(it, action.videoId, action.step) }
                        is VideoAction.Delete -> change { removeCatalogVideo(it, action.videoId) }
                    }
                }
            }
        }
    }
    confirm?.let { asked ->
        ConfirmDialog(asked, onDismiss = { confirm = null }) {
            confirm = null
            when (asked) {
                CatalogConfirm.Reset -> scope.launch { UziCatalogStore.reset() }
                is CatalogConfirm.Section -> change { removeCatalogSection(it, asked.name) }
            }
        }
    }
}

private sealed interface CatalogConfirm {
    data object Reset : CatalogConfirm

    data class Section(
        val name: String,
    ) : CatalogConfirm
}

private sealed interface VideoAction {
    data class Move(
        val videoId: String,
        val step: Int,
    ) : VideoAction

    data class Delete(
        val videoId: String,
    ) : VideoAction
}

@Composable
private fun CatalogToolbar(
    editing: Boolean,
    edited: Boolean,
    onToggle: () -> Unit,
    onReset: () -> Unit,
) {
    Hint(
        if (editing) {
            "Tahrirlash: ↑↓ joyini o‘zgartiradi, 🗑 o‘chiradi. O‘zgarishlar telefonda saqlanadi."
        } else {
            "Bo‘limni tanlang. Inglizcha darslar ilovaning o‘zida ochiladi va tarjima qilinadi."
        },
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onToggle, modifier = Modifier.testTag("catalog_edit")) {
            Text(if (editing) "✓ Tayyor" else "✏️ O‘zgartirish")
        }
        if (editing && edited) {
            TextButton(onClick = onReset, modifier = Modifier.testTag("catalog_reset")) { Text("Asl holiga qaytarish") }
        }
    }
}

@Composable
private fun AddLessonForm(
    sections: List<CatalogSection>,
    knownTitles: Map<String, String>,
    onAdd: (String, String, CatalogVideo) -> Unit,
) {
    var section by rememberSaveable { mutableStateOf("") }
    var topic by rememberSaveable { mutableStateOf("") }
    var link by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Yangi dars qo‘shish", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        CatalogField(section, "Bo‘lim", "catalog_add_section") { section = it }
        NameChips(sections.map { it.name }) { section = it }
        CatalogField(topic, "Mavzu", "catalog_add_topic") { topic = it }
        NameChips(topicsOf(sections, section)) { topic = it }
        CatalogField(link, "YouTube havolasi", "catalog_add_link") { link = it }
        CatalogField(title, "Nomi (ixtiyoriy)", "catalog_add_title") { title = it }
        Button(
            modifier = Modifier.testTag("catalog_add"),
            enabled = section.isNotBlank() && topic.isNotBlank() && link.isNotBlank(),
            onClick = {
                val videoId = YouTubeUrlParser.extractVideoId(link.trim())
                if (videoId == null) {
                    message = "Havoladan YouTube video topilmadi."
                } else {
                    val name = title.trim().ifEmpty { knownTitles[videoId].orEmpty() }.ifEmpty { "YouTube video $videoId" }
                    onAdd(section, topic, CatalogVideo(videoId, name))
                    message = "Qo‘shildi: ${section.trim()} → ${topic.trim()}"
                    link = ""
                    title = ""
                }
            },
        ) { Text("Qo‘shish") }
        message?.let { Hint(it) }
    }
}

private fun topicsOf(
    sections: List<CatalogSection>,
    section: String,
): List<String> =
    sections
        .firstOrNull { it.name.equals(section.trim(), ignoreCase = true) }
        ?.topics
        ?.map { it.name }
        .orEmpty()

@Composable
private fun CatalogField(
    value: String,
    label: String,
    tag: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

/** Existing names to tap instead of typing. */
@Composable
private fun NameChips(
    names: List<String>,
    onPick: (String) -> Unit,
) {
    if (names.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        names.forEach { name -> SuggestionChip(onClick = { onPick(name) }, label = { Text(name, maxLines = 1) }) }
    }
}

@Composable
private fun SectionHeader(
    section: CatalogSection,
    translated: Set<String>,
    expanded: Boolean,
    editing: Boolean,
    onToggle: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    val done = section.topics.sumOf { topic -> topic.videos.count { it.videoId in translated } }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp)
            .testTag("catalog_section_${section.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (expanded) "▾ " else "▸ ", color = MaterialTheme.colorScheme.primary)
        Text(section.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        if (editing) {
            EditButtons("section_${section.name}", onMove, onDelete)
        } else {
            Hint(if (done > 0) "$done/${section.videoCount} ✓" else "${section.videoCount} dars")
        }
    }
}

@Composable
private fun SectionBody(
    section: CatalogSection,
    translated: Set<String>,
    activeVideoId: String?,
    editing: Boolean,
    onOpen: (String) -> Unit,
    onAction: (VideoAction) -> Unit,
) {
    Column(Modifier.padding(start = 12.dp)) {
        section.topics.forEach { topic ->
            Text(
                topic.name,
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            topic.videos.forEach { video ->
                VideoRow(video, video.videoId in translated, video.videoId == activeVideoId, editing, onOpen, onAction)
            }
        }
    }
}

@Composable
private fun VideoRow(
    video: CatalogVideo,
    translated: Boolean,
    active: Boolean,
    editing: Boolean,
    onOpen: (String) -> Unit,
    onAction: (VideoAction) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(video.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            if (translated) Hint("✓ tarjima qilingan")
        }
        when {
            editing ->
                EditButtons(
                    tag = video.videoId,
                    onMove = { onAction(VideoAction.Move(video.videoId, it)) },
                    onDelete = { onAction(VideoAction.Delete(video.videoId)) },
                )
            active -> Hint("ochiq")
            else ->
                TextButton(modifier = Modifier.testTag("catalog_open_${video.videoId}"), onClick = { onOpen(video.videoId) }) {
                    Text("Ochish")
                }
        }
    }
}

@Composable
private fun EditButtons(
    tag: String,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    SmallIcon(Icons.Default.KeyboardArrowUp, "Yuqoriga", "catalog_up_$tag") { onMove(-1) }
    SmallIcon(Icons.Default.KeyboardArrowDown, "Pastga", "catalog_down_$tag") { onMove(1) }
    SmallIcon(Icons.Default.Delete, "O‘chirish", "catalog_delete_$tag", onDelete)
}

@Composable
private fun SmallIcon(
    icon: ImageVector,
    description: String,
    tag: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp).testTag(tag)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ConfirmDialog(
    asked: CatalogConfirm,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val text =
        when (asked) {
            CatalogConfirm.Reset -> "Siz qo‘shgan va o‘zgartirgan hamma narsa o‘chadi, ilovadagi asl mundarija qaytadi."
            is CatalogConfirm.Section -> "«${asked.name}» bo‘limi va undagi barcha darslar mundarijadan o‘chiriladi."
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (asked == CatalogConfirm.Reset) "Asl holiga qaytarilsinmi?" else "Bo‘lim o‘chirilsinmi?") },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("catalog_confirm")) { Text("Ha") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Yo‘q") } },
    )
}
