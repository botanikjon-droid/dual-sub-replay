package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.R
import com.kienhoang.dualsubreplay.data.CatalogSection
import com.kienhoang.dualsubreplay.data.LessonLibraryStore
import com.kienhoang.dualsubreplay.data.UziCatalogStore

/**
 * SHOKALON's first screen: a card with a picture for every ultrasound section; a card opens the section's
 * topics and lectures, and a lecture opens in the player. Nothing here leads to other YouTube videos.
 */
@Composable
internal fun ShokalonHomeScreen(
    activeVideoId: String?,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
    onSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { UziCatalogStore.load(context) }
    val sections by UziCatalogStore.sections.collectAsStateWithLifecycle()
    val lessons by LessonLibraryStore.records.collectAsStateWithLifecycle()
    val translated = remember(lessons) { lessons.mapTo(HashSet()) { it.videoId } }
    var openName by rememberSaveable { mutableStateOf<String?>(null) }
    val open = sections?.firstOrNull { it.name == openName }
    Dialog(
        onDismissRequest = { if (open != null) openName = null else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().testTag("shokalon_home"),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
                if (open == null) {
                    HomeHeader(sections.orEmpty(), translated, activeVideoId, onDismiss, onSettings)
                    SectionGrid(sections, translated) { openName = it }
                    if (lessons.isNotEmpty()) {
                        Box(Modifier.padding(12.dp)) { LessonLibraryCard(lessons, activeVideoId, onOpen) }
                    }
                } else {
                    SectionDetail(open, translated, activeVideoId, onBack = { openName = null }, onOpen = onOpen)
                }
            }
        }
    }
}

private val HERO_BRUSH = Brush.linearGradient(listOf(Color(0xFF0B3C5D), Color(0xFF0E7490), Color(0xFF14B8A6)))
private val CARD_SHAPE = RoundedCornerShape(22.dp)

@Composable
private fun HomeHeader(
    sections: List<CatalogSection>,
    translated: Set<String>,
    activeVideoId: String?,
    onBackToVideo: () -> Unit,
    onSettings: () -> Unit,
) {
    val total = sections.sumOf { it.videoCount }
    val done = sections.sumOf { section -> section.topics.sumOf { topic -> topic.videos.count { it.videoId in translated } } }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .shadow(8.dp, CARD_SHAPE)
            .clip(CARD_SHAPE)
            .background(HERO_BRUSH)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.drawer_logo),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(60.dp).clip(CircleShape).background(Color.White),
            )
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text("SHOKALON", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Ultratovush darslarini o‘rganish platformasi",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            IconButton(onClick = onSettings, modifier = Modifier.testTag("shokalon_home_settings")) {
                Icon(Icons.Default.Settings, contentDescription = "Sozlamalar va menyu", tint = Color.White)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "${sections.size} bo‘lim · $total dars · $done tasi o‘zbekcha",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
        if (total > 0) {
            LinearProgressIndicator(
                progress = { done.toFloat() / total },
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.25f),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .height(6.dp)
                        .clip(CircleShape),
            )
        }
        if (activeVideoId != null) {
            OutlinedButton(onClick = onBackToVideo, modifier = Modifier.padding(top = 12.dp).testTag("shokalon_home_back")) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                Text("Videoga qaytish", color = Color.White, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun SectionGrid(
    sections: List<CatalogSection>?,
    translated: Set<String>,
    onSection: (String) -> Unit,
) {
    Text(
        "Bo‘limlar",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
    )
    if (sections == null) {
        Box(Modifier.padding(16.dp)) { Hint("Yuklanmoqda…") }
        return
    }
    sections.chunked(2).forEach { row ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            row.forEach { section ->
                val done = section.topics.sumOf { topic -> topic.videos.count { it.videoId in translated } }
                SectionTile(section, done, Modifier.weight(1f)) { onSection(section.name) }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun SectionTile(
    section: CatalogSection,
    done: Int,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val art = sectionArt(section.name)
    Column(
        modifier
            .height(190.dp)
            .shadow(6.dp, CARD_SHAPE)
            .clip(CARD_SHAPE)
            .background(Brush.verticalGradient(listOf(art.top, art.bottom)))
            .clickable(onClick = onClick)
            .padding(14.dp)
            .testTag("home_section_${section.name}"),
    ) {
        SectionPicture(art, 64)
        Spacer(Modifier.weight(1f))
        Text(
            section.name,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (done > 0) "${section.videoCount} dars · $done ✓" else "${section.videoCount} dars",
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (section.videoCount > 0) {
            LinearProgressIndicator(
                progress = { done.toFloat() / section.videoCount },
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.25f),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .height(4.dp)
                        .clip(CircleShape),
            )
        }
    }
}

/** The section's picture in a white circle; the photo-like neurosonography picture fills its circle. */
@Composable
private fun SectionPicture(
    art: SectionArt,
    sizeDp: Int,
) {
    Box(
        Modifier
            .size(sizeDp.dp)
            .shadow(4.dp, CircleShape)
            .clip(CircleShape)
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(art.image),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size((sizeDp * PICTURE_FILL).dp).clip(CircleShape),
        )
    }
}

private const val PICTURE_FILL = 0.84f

@Composable
private fun SectionDetail(
    section: CatalogSection,
    translated: Set<String>,
    activeVideoId: String?,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val art = sectionArt(section.name)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .shadow(8.dp, CARD_SHAPE)
            .clip(CARD_SHAPE)
            .background(Brush.verticalGradient(listOf(art.top, art.bottom)))
            .padding(12.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("home_section_back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Bo‘limlarga qaytish", tint = Color.White)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
            SectionPicture(art, 84)
            Column(Modifier.padding(start = 14.dp)) {
                Text(section.name, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "${section.topics.size} mavzu · ${section.videoCount} dars",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
    var number = 0
    section.topics.forEach { topic ->
        Text(
            topic.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = art.bottom,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 4.dp),
        )
        topic.videos.forEach { video ->
            number += 1
            LessonRow(number, video.title, video.videoId in translated, video.videoId == activeVideoId, art) { onOpen(video.videoId) }
        }
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun LessonRow(
    number: Int,
    title: String,
    translated: Boolean,
    active: Boolean,
    art: SectionArt,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = if (active) 6.dp else 1.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(art.top, art.bottom))),
                contentAlignment = Alignment.Center,
            ) { Text("$number", color = Color.White, fontWeight = FontWeight.Bold) }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                when {
                    active -> Hint("Hozir ochiq")
                    translated -> Hint("O‘zbekcha tarjimasi bor")
                }
            }
            if (translated) Icon(Icons.Default.CheckCircle, contentDescription = "Tarjima qilingan", tint = Color(0xFF16A34A))
        }
    }
}
