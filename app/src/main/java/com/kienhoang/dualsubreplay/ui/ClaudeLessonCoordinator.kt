package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.LearnedGlossaryStore
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.UziGlossaryStore
import com.kienhoang.dualsubreplay.data.checkTranslation
import com.kienhoang.dualsubreplay.data.formatSrt
import com.kienhoang.dualsubreplay.data.mergeLearnedSuggestions
import com.kienhoang.dualsubreplay.dubbing.DubbingSettings
import com.kienhoang.dualsubreplay.dubbing.LessonVoicePrefetch
import com.kienhoang.dualsubreplay.translation.ClaudeBackend
import com.kienhoang.dualsubreplay.translation.ClaudeChunkResult
import com.kienhoang.dualsubreplay.translation.GEMINI_LESSON_CHUNK_SIZE
import com.kienhoang.dualsubreplay.translation.GeminiBackend
import com.kienhoang.dualsubreplay.translation.GeminiModelQuota
import com.kienhoang.dualsubreplay.translation.GeminiQuotaExhausted
import com.kienhoang.dualsubreplay.translation.LessonTranslator
import com.kienhoang.dualsubreplay.translation.TRANSLATION_PROVIDER_CLAUDE
import com.kienhoang.dualsubreplay.translation.TRANSLATION_PROVIDER_GEMINI
import com.kienhoang.dualsubreplay.translation.TranslationLanguages
import com.kienhoang.dualsubreplay.translation.geminiQuotaMessage
import com.kienhoang.dualsubreplay.translation.glossaryForBatch
import com.kienhoang.dualsubreplay.translation.translatedSegments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A place Claude marked as misheard or doubtful, shown on the lesson screen for the doctor to check. */
internal data class ClaudeDoubt(
    val index: Int,
    val english: String,
    val uzbek: String,
    val heard: String,
    val options: List<String>,
    val reason: String,
)

/** Who translates a whole lesson: Claude (paid, best) or Gemini (free tier, one request per lesson). */
internal enum class LessonEngine(
    val label: String,
) {
    CLAUDE("Claude"),
    GEMINI("Gemini"),
}

/** The latest whole-lesson run, for the lesson screen. */
internal data class ClaudeRunState(
    val videoId: String? = null,
    val engine: LessonEngine = LessonEngine.CLAUDE,
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val newTerms: Int = 0,
    val message: String? = null,
    val doubts: List<ClaudeDoubt> = emptyList(),
)

/** Kept for the app's lifetime, so a Gemini model out of free quota is not asked again today. */
private val geminiLessonQuota = GeminiModelQuota()

/** The real engines, with the doctor's keys and choices from the settings. */
internal fun lessonTranslatorFor(engine: LessonEngine): LessonTranslator =
    when (engine) {
        LessonEngine.CLAUDE ->
            LessonTranslator(ClaudeBackend(DubbingSettings.claudeApiKey.value, DubbingSettings.claudeModel.value))
        LessonEngine.GEMINI ->
            LessonTranslator(
                GeminiBackend(DubbingSettings.geminiApiKey.value, geminiLessonQuota, onModel = { model ->
                    DubbingSettings.publishTranslatorStatus("Gemini ($model): butun dars bitta so‘rovda tarjima qilindi.")
                }),
                chunkSize = GEMINI_LESSON_CHUNK_SIZE,
            )
    }

private fun keyFor(engine: LessonEngine): String =
    when (engine) {
        LessonEngine.CLAUDE -> DubbingSettings.claudeApiKey.value
        LessonEngine.GEMINI -> DubbingSettings.geminiApiKey.value
    }

/**
 * Translates one whole lesson at a time, with Claude or Gemini. Machine translation keeps playing
 * meanwhile; the lesson's translation is imported once, when every chunk is done (an imported part
 * would blank the rows it does not cover), so a reopened lesson needs no request at all. Suggested
 * terms go to the learned glossary as they arrive, so the next chunks and lessons use them.
 * Automatic runs, once per lesson and app session, for a lesson without a stored translation:
 * Claude when its "avtomatik" switch is on or it is the chosen translator, otherwise Gemini when it is
 * the chosen translator in whole-lesson mode.
 */
internal class ClaudeLessonCoordinator(
    private val scope: CoroutineScope,
    private val segmentsFor: suspend (String) -> List<SubtitleSegment>,
    private val importSrt: suspend (text: String, videoId: String, source: String) -> Int,
    private val notify: (String) -> Unit,
    private val translatorFor: (LessonEngine) -> LessonTranslator = ::lessonTranslatorFor,
) {
    private val _state = MutableStateFlow(ClaudeRunState())
    val state: StateFlow<ClaudeRunState> = _state
    private var job: Job? = null
    private val autoTried = mutableSetOf<String>()

    init {
        // The line above the subtitles names the engine while a whole lesson is being translated.
        scope.launch { state.collect(LessonRunStatus::publish) }
    }

    /** Called whenever a lesson is (re)loaded; starts a background run when the doctor asked for it. */
    fun onLessonLoaded(
        videoId: String,
        targetLanguage: String,
        hasStoredTranslation: Boolean,
    ) {
        if (hasStoredTranslation || TranslationLanguages.normalize(targetLanguage) != "uz") return
        val engine =
            when {
                DubbingSettings.claudeApiKey.value.isNotBlank() &&
                    (DubbingSettings.claudeAuto.value || DubbingSettings.translationProvider.value == TRANSLATION_PROVIDER_CLAUDE) ->
                    LessonEngine.CLAUDE
                DubbingSettings.translationProvider.value == TRANSLATION_PROVIDER_GEMINI &&
                    DubbingSettings.geminiWholeLesson.value &&
                    DubbingSettings.geminiApiKey.value.isNotBlank() -> LessonEngine.GEMINI
                else -> return
            }
        if (autoTried.add(videoId)) start(videoId, engine, automatic = true)
    }

    fun start(
        videoId: String,
        engine: LessonEngine = LessonEngine.CLAUDE,
        automatic: Boolean = false,
    ) {
        if (job?.isActive == true) {
            if (!automatic) notify("${_state.value.engine.label} hozir boshqa darsni tarjima qilmoqda.")
            return
        }
        if (keyFor(engine).isBlank()) {
            _state.value = ClaudeRunState(videoId, engine, message = "Avval ${engine.label} API kalitini kiriting.")
            return
        }
        _state.value = ClaudeRunState(videoId, engine, running = true, message = "Subtitr tayyorlanmoqda…")
        job = scope.launch { run(videoId, engine, automatic) }
    }

    fun cancel() {
        job?.cancel()
        _state.update { it.copy(running = false, message = "To‘xtatildi.") }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun run(
        videoId: String,
        engine: LessonEngine,
        automatic: Boolean,
    ) {
        try {
            val segments = segmentsFor(videoId)
            // Claude and Gemini get every caption row and rebuild the sentences themselves.
            val groups = segments.indices.map { SentenceGroup(listOf(it), segments[it].originalText, emptyList()) }
            val sentences = groups.map { it.text }
            _state.update { it.copy(total = sentences.size, message = "${engine.label} butun darsni tarjima qilmoqda…") }
            val doubts = mutableListOf<ClaudeDoubt>()
            val result =
                translatorFor(engine).translate(
                    sentences = sentences,
                    glossaryFor = { texts -> glossaryForBatch(UziGlossaryStore.glossary.value, texts) },
                    terms = { LearnedGlossaryStore.terms.value },
                ) { chunk, progress ->
                    val added = learn(chunk, videoId)
                    doubts += doubtsOf(chunk, sentences)
                    _state.update {
                        it.copy(done = progress.done, total = progress.total, newTerms = it.newTerms + added, doubts = doubts.toList())
                    }
                }
            val count =
                importSrt(formatSrt(translatedSegments(segments, spreadOverRows(groups, result.translations))), videoId, engine.label)
            // Speak the whole lesson into the voice cache now, so playback never waits for a sentence.
            if (DubbingSettings.enabled.value) {
                LessonVoicePrefetch.start(
                    result.translations
                        .toSortedMap()
                        .values
                        .toList(),
                )
            }
            val summary = summary(engine, count, _state.value.newTerms, doubts.size)
            _state.update { it.copy(running = false, message = summary) }
            notify(summary)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            val reason =
                if (error is GeminiQuotaExhausted) geminiQuotaMessage(error.untilMs) else error.message ?: "Tarjima bajarilmadi."
            _state.update { it.copy(running = false, message = reason) }
            notify(if (automatic) "${engine.label} avtomatik tarjimasi to‘xtadi: $reason" else reason)
        }
    }

    /** Adds the chunk's new terms to the learned glossary; returns how many were new. */
    private suspend fun learn(
        chunk: ClaudeChunkResult,
        videoId: String,
    ): Int {
        if (chunk.newTerms.isEmpty()) return 0
        val glossary = UziGlossaryStore.glossary.value
        val before = LearnedGlossaryStore.terms.value.size
        val after =
            LearnedGlossaryStore.update { existing ->
                mergeLearnedSuggestions(existing, chunk.newTerms, videoId) { english ->
                    glossary?.findTerms(english, includeGeneral = true)?.isNotEmpty() == true
                }
            }
        return (after.size - before).coerceAtLeast(0)
    }

    private fun doubtsOf(
        chunk: ClaudeChunkResult,
        sentences: List<String>,
    ): List<ClaudeDoubt> = modelDoubts(chunk, sentences) + automaticDoubts(chunk, sentences)

    /** Numbers, units and negations the translation lost, found without any model. */
    private fun automaticDoubts(
        chunk: ClaudeChunkResult,
        sentences: List<String>,
    ): List<ClaudeDoubt> =
        chunk.translations.mapNotNull { (index, uzbek) ->
            val english = sentences.getOrElse(index) { "" }
            val issues = checkTranslation(english, uzbek)
            if (issues.isEmpty()) {
                null
            } else {
                ClaudeDoubt(
                    index,
                    english,
                    uzbek,
                    heard = "avtomatik tekshiruv",
                    options = emptyList(),
                    reason = issues.joinToString("; ") { it.label },
                )
            }
        }

    private fun modelDoubts(
        chunk: ClaudeChunkResult,
        sentences: List<String>,
    ): List<ClaudeDoubt> =
        chunk.uncertain.map {
            ClaudeDoubt(
                index = it.index,
                english = sentences.getOrElse(it.index) { "" },
                uzbek = chunk.translations[it.index].orEmpty(),
                heard = it.heard,
                options = it.options,
                reason = it.reason,
            )
        }

    private fun summary(
        engine: LessonEngine,
        count: Int,
        newTerms: Int,
        doubts: Int,
    ): String =
        buildString {
            append("${engine.label} tarjimasi tayyor: $count qator.")
            if (newTerms > 0) append(" Lug‘atga $newTerms ta yangi atama taklif qilindi.")
            if (doubts > 0) append(" Tekshirish kerak: $doubts ta joy.")
        }
}
