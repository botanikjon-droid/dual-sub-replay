package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.CatalogVideo
import com.kienhoang.dualsubreplay.data.LessonNotes
import com.kienhoang.dualsubreplay.data.distinctFrames
import com.kienhoang.dualsubreplay.dubbing.DubbingSettings
import com.kienhoang.dualsubreplay.translation.GeminiQuotaExhausted
import com.kienhoang.dualsubreplay.translation.LessonNotesWriter
import com.kienhoang.dualsubreplay.translation.geminiQuotaMessage
import com.kienhoang.dualsubreplay.translation.lessonNotesUserPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How far "Konspektsiz darslarga yozish" of a section has got. */
internal data class SectionNotesProgress(
    val section: String? = null,
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val current: String? = null,
    val message: String? = null,
)

/**
 * Writes the konspekt and test of every lecture of a section that has none, one lecture after another (one
 * request each), without opening the videos: the lecture's English subtitles are read as for the open
 * lesson, with its stored Uzbek translation when there is one. A used-up free limit or a missing key stops
 * the run; a lecture without subtitles is skipped.
 */
internal class SectionNotesBatch(
    private val scope: CoroutineScope,
    private val inputFor: suspend (String) -> LessonNotesInput,
    private val load: suspend (String) -> LessonNotes?,
    private val save: suspend (String, LessonNotes) -> Unit,
    private val writerFor: (LessonEngine) -> LessonNotesWriter = ::lessonNotesWriterFor,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _progress = MutableStateFlow(SectionNotesProgress())
    val progress: StateFlow<SectionNotesProgress> = _progress
    private var job: Job? = null

    fun start(
        section: String,
        videos: List<CatalogVideo>,
        engine: LessonEngine,
    ) {
        if (job?.isActive == true) return
        val key = if (engine == LessonEngine.CLAUDE) DubbingSettings.claudeApiKey.value else DubbingSettings.geminiApiKey.value
        if (key.isBlank()) {
            _progress.value = SectionNotesProgress(section, message = "Avval ${engine.label} API kalitini sozlamalarda kiriting.")
            return
        }
        _progress.value = SectionNotesProgress(section, running = true, total = videos.size, message = "${engine.label} yozmoqda…")
        job = scope.launch { run(section, videos, engine) }
    }

    fun cancel() {
        job?.cancel()
        _progress.update { it.copy(running = false, current = null, message = "To‘xtatildi: ${it.done} ta dars yozildi.") }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun run(
        section: String,
        videos: List<CatalogVideo>,
        engine: LessonEngine,
    ) {
        val writer = writerFor(engine)
        val skipped = mutableListOf<String>()
        for (video in videos) {
            _progress.update { it.copy(current = video.title) }
            try {
                val input = inputFor(video.videoId)
                val written =
                    writer.write(
                        lessonNotesUserPrompt(input.title ?: video.title, input.english, input.uzbek, input.startSeconds),
                    )
                val taken = load(video.videoId)?.frames.orEmpty().filter { it.image.isNotEmpty() }
                save(
                    video.videoId,
                    written.copy(engine = engine.label, createdAtMs = now(), frames = distinctFrames(taken + written.frames)),
                )
                _progress.update { it.copy(done = it.done + 1) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (quota: GeminiQuotaExhausted) {
                finish(section, "To‘xtadi: ${geminiQuotaMessage(quota.untilMs)}")
                return
            } catch (error: Exception) {
                skipped += "${video.title}: ${error.message ?: "yozilmadi"}"
            }
        }
        val tail = if (skipped.isEmpty()) "" else "\nO‘tkazib yuborildi (${skipped.size}):\n" + skipped.joinToString("\n")
        finish(section, "Tayyor: ${_progress.value.done} ta darsga konspekt va test yozildi.$tail")
    }

    private fun finish(
        section: String,
        message: String,
    ) {
        _progress.update { it.copy(section = section, running = false, current = null, message = message) }
    }
}
