package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.data.LessonLibraryStore
import com.kienhoang.dualsubreplay.dubbing.DubbingSettings
import com.kienhoang.dualsubreplay.translation.TRANSLATION_PROVIDER_DEEPL
import com.kienhoang.dualsubreplay.translation.TRANSLATION_PROVIDER_GEMINI
import com.kienhoang.dualsubreplay.translation.TranslationLanguages
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The latest whole-lesson run, mirrored by ClaudeLessonCoordinator for the line above the subtitles. */
internal object LessonRunStatus {
    private val _state = MutableStateFlow(ClaudeRunState())
    val state: StateFlow<ClaudeRunState> = _state

    fun publish(run: ClaudeRunState) {
        _state.value = run
    }
}

/** What the line above the subtitles needs to name the translator of the open lesson. */
internal data class TranslatorFacts(
    val activeVideoId: String?,
    val targetLanguage: String,
    val importedCues: Int,
    /** "Claude", "Gemini", "DeepL" or "Fayl" from the list of translated lessons; null when unknown. */
    val storedEngine: String?,
    val run: ClaudeRunState,
    val provider: String,
    val geminiWholeLesson: Boolean,
    val geminiKey: Boolean,
    val deeplKey: Boolean,
)

/**
 * Which program translates the open lesson, in one short Uzbek line: a stored whole-lesson translation
 * (no request, no waiting), a whole-lesson run in progress, or the sentence-by-sentence translator.
 */
internal fun translatorLabel(facts: TranslatorFacts): String {
    val live = liveTranslatorName(facts)
    val run = facts.run
    if (run.running && run.videoId == facts.activeVideoId) {
        val progress = if (run.total > 0) " ${run.done}/${run.total}" else ""
        return "⏳ ${run.engine.label} butun darsni tarjima qilmoqda$progress · hozircha: $live"
    }
    if (facts.importedCues > 0) {
        val who =
            when (facts.storedEngine) {
                null, "" -> "saqlangan tarjima"
                "Fayl" -> "fayldan yuklangan"
                else -> facts.storedEngine
            }
        return "✓ Tarjima: $who · saqlangan, qayta so‘rov sarflanmaydi"
    }
    return "Tarjima: $live"
}

private fun liveTranslatorName(facts: TranslatorFacts): String {
    val uzbek = TranslationLanguages.normalize(facts.targetLanguage) == "uz"
    return when {
        facts.provider == TRANSLATION_PROVIDER_GEMINI && facts.geminiKey && !facts.geminiWholeLesson -> "Gemini (gapma-gap)"
        facts.provider == TRANSLATION_PROVIDER_DEEPL && facts.deeplKey && !uzbek -> "DeepL (gapma-gap)"
        else -> "Google Translate"
    }
}

/** The translator line above the subtitles; shown only in the build with the Uzbek settings. */
@Composable
internal fun TranslatorBadge(
    activeVideoId: String?,
    targetLanguage: String,
    importedCues: Int,
) {
    val available by DubbingSettings.available.collectAsStateWithLifecycle()
    if (!available || activeVideoId == null) return
    val records by LessonLibraryStore.records.collectAsStateWithLifecycle()
    val run by LessonRunStatus.state.collectAsStateWithLifecycle()
    val provider by DubbingSettings.translationProvider.collectAsStateWithLifecycle()
    val whole by DubbingSettings.geminiWholeLesson.collectAsStateWithLifecycle()
    val geminiKey by DubbingSettings.geminiApiKey.collectAsStateWithLifecycle()
    val deeplKey by DubbingSettings.deeplApiKey.collectAsStateWithLifecycle()
    val facts =
        TranslatorFacts(
            activeVideoId = activeVideoId,
            targetLanguage = targetLanguage,
            importedCues = importedCues,
            storedEngine = records.firstOrNull { it.videoId == activeVideoId }?.engine,
            run = run,
            provider = provider,
            geminiWholeLesson = whole,
            geminiKey = geminiKey.isNotBlank(),
            deeplKey = deeplKey.isNotBlank(),
        )
    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = translatorLabel(facts),
            color = Color(0xFFB7CED1),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag("translator_badge"),
        )
        // The frame on the screen goes into the lesson's pictured notes.
        IconButton(onClick = { VideoFrameSource.onCaptureNow?.invoke() }, modifier = Modifier.size(36.dp).testTag("capture_note_frame")) {
            Icon(Icons.Default.PhotoCamera, contentDescription = "Kadrni konspektga qo\u2018shish", tint = Color(0xFFB7CED1))
        }
    }
}
