package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.data.UziCatalogStore

/**
 * SHOKALON's lesson steps in the top bar: the previous and next lecture of the open lecture's section and its
 * place there ("Abdominal · 3/12"); tapping the place opens the section's lesson list. Shown only in focus
 * mode for a catalog lecture.
 */
@Composable
internal fun RowScope.LessonStepBar(
    activeVideoId: String?,
    onSectionList: (String) -> Unit = {},
    onOpen: (String) -> Unit,
) {
    if (!shokalonFocusMode) return
    val sections by UziCatalogStore.sections.collectAsStateWithLifecycle()
    val steps = remember(sections, activeVideoId) { lessonSteps(sections, activeVideoId) }
    LaunchedEffect(steps) { focusLessonSteps.value = steps }
    if (steps == null) return
    IconButton(
        enabled = steps.previous != null,
        onClick = { steps.previous?.let { onOpen(it.videoId) } },
        modifier = Modifier.align(Alignment.CenterVertically).testTag("lesson_previous"),
    ) { Icon(Icons.Default.SkipPrevious, contentDescription = "Oldingi dars") }
    Text(
        "\u2630 ${steps.section} · ${steps.position}/${steps.total}",
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier =
            Modifier
                .weight(1f)
                .align(Alignment.CenterVertically)
                .clickable(onClickLabel = "Bo\u2018lim darslari ro\u2018yxati") { onSectionList(steps.section) }
                .padding(vertical = 12.dp)
                .testTag("lesson_position"),
    )
    IconButton(
        enabled = steps.next != null,
        onClick = { steps.next?.let { onOpen(it.videoId) } },
        modifier = Modifier.align(Alignment.CenterVertically).testTag("lesson_next"),
    ) { Icon(Icons.Default.SkipNext, contentDescription = "Keyingi dars") }
}
