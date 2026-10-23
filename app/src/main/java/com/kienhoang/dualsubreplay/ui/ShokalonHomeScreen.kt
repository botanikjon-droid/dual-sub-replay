package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.data.LessonLibraryStore

/**
 * SHOKALON's first screen: the ultrasound sections, their topics and lectures, and the lessons already
 * translated. A chosen lecture opens in the player; nothing here leads to other YouTube videos.
 */
@Composable
internal fun ShokalonHomeScreen(
    activeVideoId: String?,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val lessons by LessonLibraryStore.records.collectAsStateWithLifecycle()
    val brand = appBrand()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize().testTag("shokalon_home"),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (activeVideoId != null) {
                        IconButton(onClick = onDismiss, modifier = Modifier.testTag("shokalon_home_back")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Videoga qaytish")
                        }
                    }
                    Column(Modifier.padding(start = if (activeVideoId != null) 0.dp else 12.dp)) {
                        Text(brand.name, style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Ultratovush bo‘limlari",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Hint(
                        "Bo‘limni oching, mavzu va videoni tanlang. Video SHOKALON ichida, tarjimasi bilan ochiladi; " +
                            "YouTube tavsiyalari ko‘rsatilmaydi.",
                    )
                    LessonCatalogCard(
                        translatedVideoIds = lessons.mapTo(HashSet()) { it.videoId },
                        knownTitles = lessons.filter { it.title.isNotBlank() }.associate { it.videoId to it.title },
                        activeVideoId = activeVideoId,
                        onOpen = onOpen,
                    )
                    if (lessons.isNotEmpty()) LessonLibraryCard(lessons, activeVideoId, onOpen)
                }
            }
        }
    }
}
