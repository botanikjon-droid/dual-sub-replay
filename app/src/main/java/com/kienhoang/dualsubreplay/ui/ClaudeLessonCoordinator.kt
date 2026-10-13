package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.LearnedGlossaryStore
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.UziGlossaryStore
import com.kienhoang.dualsubreplay.data.formatSrt
import com.kienhoang.dualsubreplay.data.mergeLearnedSuggestions
import com.kienhoang.dualsubreplay.dubbing.DubbingSettings
import com.kienhoang.dualsubreplay.translation.ClaudeChunkResult
import com.kienhoang.dualsubreplay.translation.ClaudeLessonTranslator
import com.kienhoang.dualsubreplay.translation.TranslationLanguages
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

/** The latest "Claude bilan tarjima" run, for the lesson screen. */
internal data class ClaudeRunState(
    val videoId: String? = null,
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val newTerms: Int = 0,
    val message: String? = null,
    val doubts: List<ClaudeDoubt> = emptyList(),
)

/**
 * Runs "Claude bilan tarjima" for one lesson at a time. Machine translation keeps playing while
 * Claude works; the lesson's translation is imported once, when every chunk is done (an imported
 * part would blank the rows it does not cover). Terms Claude suggests go to the learned glossary as they arrive, so the
 * next chunks and the next lessons use them. With "avtomatik" on, every newly opened lesson
 * without a stored translation is translated in the background, once per app session.
 */
internal class ClaudeLessonCoordinator(
    private val scope: CoroutineScope,
    private val segmentsFor: suspend (String) -> List<SubtitleSegment>,
    private val importSrt: suspend (text: String, videoId: String) -> Int,
    private val notify: (String) -> Unit,
    private val translator: ClaudeLessonTranslator = ClaudeLessonTranslator(),
) {
    private val _state = MutableStateFlow(ClaudeRunState())
    val state: StateFlow<ClaudeRunState> = _state
    private var job: Job? = null
    private val autoTried = mutableSetOf<String>()

    /** Called whenever a lesson is (re)loaded; starts a background run when the doctor asked for it. */
    fun onLessonLoaded(
        videoId: String,
        targetLanguage: String,
        hasStoredTranslation: Boolean,
    ) {
        val wanted =
            DubbingSettings.claudeAuto.value &&
                DubbingSettings.claudeApiKey.value.isNotBlank() &&
                TranslationLanguages.normalize(targetLanguage) == "uz" &&
                !hasStoredTranslation
        if (wanted && autoTried.add(videoId)) start(videoId, automatic = true)
    }

    fun start(
        videoId: String,
        automatic: Boolean = false,
    ) {
        if (job?.isActive == true) {
            if (!automatic) notify("Claude hozir boshqa darsni tarjima qilmoqda.")
            return
        }
        val key = DubbingSettings.claudeApiKey.value
        if (key.isBlank()) {
            _state.value = ClaudeRunState(videoId, message = "Avval Claude API kalitini kiriting.")
            return
        }
        _state.value = ClaudeRunState(videoId, running = true, message = "Subtitr tayyorlanmoqda…")
        job = scope.launch { run(videoId, key, automatic) }
    }

    fun cancel() {
        job?.cancel()
        _state.update { it.copy(running = false, message = "To‘xtatildi.") }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun run(
        videoId: String,
        key: String,
        automatic: Boolean,
    ) {
        try {
            val segments = segmentsFor(videoId)
            val sentences = segments.map { it.originalText }
            _state.update { it.copy(total = sentences.size, message = "Claude tarjima qilmoqda…") }
            val doubts = mutableListOf<ClaudeDoubt>()
            val result =
                translator.translate(
                    apiKey = key,
                    model = DubbingSettings.claudeModel.value,
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
            val count = importSrt(formatSrt(translatedSegments(segments, result.translations)), videoId)
            val summary = summary(count, _state.value.newTerms, doubts.size)
            _state.update { it.copy(running = false, message = summary) }
            notify(summary)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            val reason = error.message ?: "Claude tarjimasi bajarilmadi."
            _state.update { it.copy(running = false, message = reason) }
            notify(if (automatic) "Claude avtomatik tarjimasi to‘xtadi: $reason" else reason)
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
        count: Int,
        newTerms: Int,
        doubts: Int,
    ): String =
        buildString {
            append("Claude tarjimasi tayyor: $count qator.")
            if (newTerms > 0) append(" Lug‘atga $newTerms ta yangi atama taklif qilindi.")
            if (doubts > 0) append(" Tekshirish kerak: $doubts ta joy.")
        }
}
