package com.kienhoang.dualsubreplay.dubbing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/** Progress of preparing a lesson's speech in advance, for the lesson screen. */
internal data class VoicePrefetchState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val failed: Int = 0,
    val message: String? = null,
)

/**
 * Speaks a whole lesson's translation into the voice's clip cache in the background, once the lesson is
 * translated, so playback never waits for a sentence and the lesson's Uzbek speech plays without a
 * network (the video itself still needs one). It uses the same voice and text preparation as playback,
 * so every clip is a cache hit later. Sentences go one at a time to spare the voice service; after
 * [MAX_FAILURES_IN_A_ROW] failures in a row it stops and says why.
 */
internal object LessonVoicePrefetch {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(VoicePrefetchState())
    val state: StateFlow<VoicePrefetchState> = _state
    private var job: Job? = null

    /** The app's voice; null in builds without one. Set by AppViewModel. */
    @Volatile var voice: DubbingVoice? = null

    /** The open lesson's translated sentences, for the lesson screen's button. Set by AppViewModel. */
    @Volatile var currentLessonTexts: () -> List<String> = { emptyList() }

    fun startForCurrentLesson() = start(currentLessonTexts())

    fun start(texts: List<String>) {
        val speaker = voice ?: return
        val lines = prefetchLines(texts)
        if (lines.isEmpty()) {
            _state.value = VoicePrefetchState(message = "Avval darsni tarjima qiling.")
            return
        }
        job?.cancel()
        _state.value = VoicePrefetchState(running = true, total = lines.size)
        job =
            scope.launch {
                var failuresInARow = 0
                var lastError: String? = null
                for (line in lines) {
                    try {
                        speaker.synthesize(line)
                        failuresInARow = 0
                        _state.update { it.copy(done = it.done + 1) }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: IOException) {
                        lastError = error.message
                        _state.update { it.copy(done = it.done + 1, failed = it.failed + 1) }
                        if (++failuresInARow >= MAX_FAILURES_IN_A_ROW) break
                    }
                }
                _state.update {
                    it.copy(
                        running = false,
                        message = prefetchSummary(it, failuresInARow >= MAX_FAILURES_IN_A_ROW, lastError),
                    )
                }
            }
    }

    fun cancel() {
        job?.cancel()
        _state.update { it.copy(running = false, message = "To‘xtatildi: ${it.done} / ${it.total} gap tayyor.") }
    }

    private const val MAX_FAILURES_IN_A_ROW = 5
}

/** The distinct, non-blank sentences in lesson order. */
internal fun prefetchLines(texts: List<String>): List<String> = texts.mapNotNull(::speakableText).distinct()

internal fun prefetchSummary(
    state: VoicePrefetchState,
    stoppedOnErrors: Boolean,
    lastError: String?,
): String =
    when {
        stoppedOnErrors -> "Ovoz xizmati javob bermadi, to‘xtatildi (${state.done} / ${state.total}). ${lastError.orEmpty()}".trim()
        state.failed > 0 -> "Dars ovozi tayyor: ${state.done - state.failed} / ${state.total} gap (${state.failed} tasi tayyorlanmadi)."
        else -> "Dars ovozi tayyor: ${state.total} gap. Endi video ovozni kutmaydi."
    }
