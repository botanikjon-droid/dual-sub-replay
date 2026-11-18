package com.kienhoang.dualsubreplay.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.data.LessonLibraryStore
import com.kienhoang.dualsubreplay.data.ReadingLink
import com.kienhoang.dualsubreplay.data.UziCatalogStore
import com.kienhoang.dualsubreplay.data.UziReadingStore
import com.kienhoang.dualsubreplay.data.catalogPlaceOf
import com.kienhoang.dualsubreplay.data.lessonSearchLinks

/*
 * "Mavzuni o‘qish": the section's checked references and searches for the lecture's own topic, opened in the
 * phone's browser (the app's single YouTube page never leaves YouTube).
 */

private const val READING_HINT =
    "Video mavzuni to‘liq yoritmagan bo‘lsa, shu manbalardan o‘qing. Havola telefon brauzerida ochiladi."

private fun openReading(
    context: Context,
    url: String,
) {
    if (!url.startsWith("https://")) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (error: android.content.ActivityNotFoundException) {
        Log.w("LessonReading", "No browser for $url", error)
    }
}

@Composable
private fun ReadingLinks(links: List<ReadingLink>) {
    val context = LocalContext.current
    links.forEach { link ->
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { openReading(context, link.url) }
                .padding(vertical = 6.dp)
                .testTag("reading_link"),
        ) {
            Text("🔗 ${link.title}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Hint(link.source)
        }
    }
}

/** The section's references and the lecture's searches, under headings. */
@Composable
private fun ReadingContent(
    sectionName: String?,
    videoTitle: String?,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { UziReadingStore.load(context) }
    val all by UziReadingStore.links.collectAsStateWithLifecycle()
    val references = sectionName?.let { all[it] }.orEmpty()
    Hint(READING_HINT)
    if (references.isNotEmpty()) {
        Text("Bo‘lim bo‘yicha asosiy manbalar", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        ReadingLinks(references)
    }
    if (!videoTitle.isNullOrBlank()) {
        if (references.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("Shu dars mavzusi bo‘yicha qidiruv", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        ReadingLinks(lessonSearchLinks(videoTitle))
    }
}

/** From a lesson's menu: reading for that one lecture. */
@Composable
internal fun LessonReadingDialog(
    sectionName: String?,
    videoTitle: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📚 Mavzuni o‘qish") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text(videoTitle, style = MaterialTheme.typography.bodySmall)
                ReadingContent(sectionName, videoTitle)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Yopish") } },
    )
}

/** On a section's page: its references, folded until opened. */
@Composable
internal fun SectionReadingCard(sectionName: String) {
    var open by rememberSaveable(sectionName) { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).testTag("section_reading")) {
        LessonCard(title = "📚 O‘qish uchun manbalar", icon = Icons.Default.School) {
            TextButton(onClick = { open = !open }) { Text(if (open) "Yopish" else "Manbalarni ko‘rsatish") }
            if (open) ReadingContent(sectionName, videoTitle = null)
        }
    }
}

/** On the lesson screen: reading for the open lecture. */
@Composable
internal fun LessonReadingCard(activeVideoId: String?) {
    if (activeVideoId == null) return
    val sections by UziCatalogStore.sections.collectAsStateWithLifecycle()
    val records by LessonLibraryStore.records.collectAsStateWithLifecycle()
    val place = remember(sections, activeVideoId) { sections?.let { catalogPlaceOf(it, activeVideoId) } }
    val title = place?.second?.title ?: records.firstOrNull { it.videoId == activeVideoId }?.title
    LessonCard(title = "📚 Mavzuni o‘qish", icon = Icons.Default.School) {
        ReadingContent(place?.first, title)
    }
}
