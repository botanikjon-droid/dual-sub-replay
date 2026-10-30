package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.data.CatalogRow
import com.kienhoang.dualsubreplay.data.CatalogSection
import com.kienhoang.dualsubreplay.data.UziCatalogStore
import com.kienhoang.dualsubreplay.data.UziSuggestionsStore
import com.kienhoang.dualsubreplay.data.addCatalogVideo
import com.kienhoang.dualsubreplay.data.sectionSuggestions
import kotlinx.coroutines.launch

/*
 * "Tavsiya qilingan videolar" of a section: lectures found for the section's gaps that the doctor has not
 * looked at. He watches one ("Ko‘rish" opens it in the player) and adds it to its topic, or hides it.
 */

@Composable
internal fun SectionSuggestions(
    section: CatalogSection,
    onWatch: (String) -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { UziSuggestionsStore.load(context) }
    val all by UziSuggestionsStore.rows.collectAsStateWithLifecycle()
    val hidden by UziSuggestionsStore.hidden.collectAsStateWithLifecycle()
    val catalog by UziCatalogStore.sections.collectAsStateWithLifecycle()
    val rows = remember(all, hidden, catalog, section.name) { sectionSuggestions(all, section.name, catalog, hidden) }
    if (rows.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).testTag("section_suggestions")) {
        LessonCard(title = "Tavsiya qilingan videolar (${rows.size})", icon = Icons.Default.Lightbulb) {
            Hint(
                "Mundarijada yo‘q mavzular uchun topilgan videolar. Ko‘rib chiqing: yoqsa “Qo‘shish” " +
                    "(o‘z mavzusiga tushadi), yoqmasa “Kerak emas”.",
            )
            notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
            TextButton(onClick = { open = !open }) { Text(if (open) "Yopish" else "Ro‘yxatni ochish") }
            if (open) {
                rows.forEach { row ->
                    SuggestionRow(
                        row,
                        onWatch = { onWatch(row.video.videoId) },
                        onAdd = {
                            scope.launch {
                                notice =
                                    try {
                                        UziCatalogStore.change { addCatalogVideo(it, row.section, row.topic, row.video) }
                                        "Qo‘shildi: ${row.topic}"
                                    } catch (failure: IllegalArgumentException) {
                                        failure.message
                                    }
                            }
                        },
                        onHide = { UziSuggestionsStore.hide(row.video.videoId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    row: CatalogRow,
    onWatch: () -> Unit,
    onAdd: () -> Unit,
    onHide: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider()
        Text(row.topic, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(row.video.title, style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onWatch, modifier = Modifier.testTag("suggestion_watch")) { Text("▶ Ko‘rish") }
            Button(onClick = onAdd, modifier = Modifier.testTag("suggestion_add")) { Text("Qo‘shish") }
            TextButton(onClick = onHide, modifier = Modifier.testTag("suggestion_hide")) { Text("Kerak emas") }
        }
    }
}
