package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.LessonNotes
import com.kienhoang.dualsubreplay.data.distinctFrames
import com.kienhoang.dualsubreplay.dubbing.DubbingSettings
import com.kienhoang.dualsubreplay.translation.ClaudeBackend
import com.kienhoang.dualsubreplay.translation.GeminiBackend
import com.kienhoang.dualsubreplay.translation.GeminiModelQuota
import com.kienhoang.dualsubreplay.translation.GeminiQuotaExhausted
import com.kienhoang.dualsubreplay.translation.LessonNotesWriter
import com.kienhoang.dualsubreplay.translation.claudeNotesWriter
import com.kienhoang.dualsubreplay.translation.geminiNotesWriter
import com.kienhoang.dualsubreplay.translation.geminiQuotaMessage
import com.kienhoang.dualsubreplay.translation.lessonNotesUserPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the notes need from the open lesson. */
internal data class LessonNotesInput(
    val title: String?,
    val english: List<String>,
    /** The lesson's Uzbek lines in the same order, or null when it has no full translation yet. */
    val uzbek: List<String>?,
    /** When each line starts in the video, so the model can name the moments worth a picture. */
    val startSeconds: List<Int>? = null,
)

/** The open lesson's notes, for the lesson screen. */
internal data class LessonNotesState(
    val videoId: String? = null,
    val running: Boolean = false,
    val notes: LessonNotes? = null,
    val message: String? = null,
)

/** Kept for the app's lifetime, like the translation's, so a used-up model is not asked again today. */
private val geminiNotesQuota = GeminiModelQuota()

internal fun lessonNotesWriterFor(engine: LessonEngine): LessonNotesWriter =
    when (engine) {
        LessonEngine.CLAUDE -> claudeNotesWriter(ClaudeBackend(DubbingSettings.claudeApiKey.value, DubbingSettings.claudeModel.value))
        LessonEngine.GEMINI -> geminiNotesWriter(GeminiBackend(DubbingSettings.geminiApiKey.value, geminiNotesQuota))
        // Notes are not written by DeepSeek (it translates lessons only): they go to Claude.
        LessonEngine.DEEPSEEK -> claudeNotesWriter(ClaudeBackend(DubbingSettings.claudeApiKey.value, DubbingSettings.claudeModel.value))
    }

/**
 * Writes the open lesson's konspekt, key terms and quiz in one request and keeps them per video.
 * Stored notes are shown again without a request; "Qayta yozish" asks again.
 */
internal class LessonNotesCoordinator(
    private val scope: CoroutineScope,
    private val inputFor: suspend (String) -> LessonNotesInput,
    private val load: suspend (String) -> LessonNotes?,
    private val save: suspend (String, LessonNotes) -> Unit,
    private val writerFor: (LessonEngine) -> LessonNotesWriter = ::lessonNotesWriterFor,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(LessonNotesState())
    val state: StateFlow<LessonNotesState> = _state
    private var job: Job? = null

    /** Shows the stored notes of [videoId], if any; called when the lesson screen opens. */
    fun show(videoId: String?) {
        if (videoId == null || _state.value.running) return
        if (_state.value.videoId == videoId && _state.value.notes != null) return
        _state.value = LessonNotesState(videoId)
        scope.launch {
            val stored = load(videoId)
            _state.update { if (it.videoId == videoId && !it.running) it.copy(notes = stored) else it }
        }
    }

    fun start(
        videoId: String,
        engine: LessonEngine,
    ) {
        if (job?.isActive == true) return
        val key = if (engine == LessonEngine.CLAUDE) DubbingSettings.claudeApiKey.value else DubbingSettings.geminiApiKey.value
        if (key.isBlank()) {
            _state.update { it.copy(videoId = videoId, message = "Avval ${engine.label} API kalitini kiriting.") }
            return
        }
        _state.update { current ->
            LessonNotesState(
                videoId = videoId,
                running = true,
                notes = if (current.videoId == videoId) current.notes else null,
                message = "${engine.label} konspekt va test yozmoqda…",
            )
        }
        job = scope.launch { run(videoId, engine) }
    }

    /** Shows notes changed elsewhere (a picture taken), when they belong to the shown lesson. */
    fun replace(
        videoId: String,
        notes: LessonNotes,
    ) {
        _state.update { if (it.videoId == videoId || it.videoId == null) it.copy(videoId = videoId, notes = notes) else it }
    }

    fun cancel() {
        job?.cancel()
        _state.update { it.copy(running = false, message = "To‘xtatildi.") }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun run(
        videoId: String,
        engine: LessonEngine,
    ) {
        try {
            val input = inputFor(videoId)
            val written =
                writerFor(engine)
                    .write(lessonNotesUserPrompt(input.title, input.english, input.uzbek, input.startSeconds))
            // Pictures already taken (and the doctor's own) stay when the notes are written again.
            val taken = load(videoId)?.frames.orEmpty().filter { it.image.isNotEmpty() }
            val notes = written.copy(engine = engine.label, createdAtMs = now(), frames = distinctFrames(taken + written.frames))
            save(videoId, notes)
            val message =
                "Konspekt tayyor: ${notes.summary.size} band, ${notes.quiz.size} ta test savoli, ${notes.frames.size} ta rasm joyi. " +
                    "Rasmlarni olish uchun \u201CKadrlarni videodan olish\u201D tugmasini bosing."
            _state.update { it.copy(running = false, notes = notes, message = message) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            val reason = if (error is GeminiQuotaExhausted) geminiQuotaMessage(error.untilMs) else error.message ?: "Konspekt yozilmadi."
            _state.update { it.copy(running = false, message = reason) }
        }
    }
}
