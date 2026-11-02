package com.kienhoang.dualsubreplay.dubbing

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import com.kienhoang.dualsubreplay.data.UziTestLesson
import com.kienhoang.dualsubreplay.data.UziTestQuestion
import com.kienhoang.dualsubreplay.data.uziTestSpeechText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/*
 * "Eshitib yodlash": the Uzbek voice reads the UZI test bank aloud. Each question is read as
 * question → a pause to think → the answer (repeated) → the explanation → the warning, if any.
 * The next line's speech is fetched while the current one plays, and clips stay in the voice's
 * disk cache, so a second listen works without internet.
 */

internal enum class ReadStage { QUESTION, THINK, ANSWER, EXPLANATION, WARNING, LESSON }

internal data class ReadLine(
    val stage: ReadStage,
    /** Display text to speak (Cyrillic is fine); null for a silent pause. */
    val text: String?,
    val pauseAfterMs: Long,
    /** For lessons: which paragraph this line is (-1 for the title). */
    val paragraph: Int = -1,
)

internal data class ReaderSettings(
    val thinkSeconds: Int = 2,
    val repeats: Int = 2,
    val explain: Boolean = true,
    val speed: Float = 1f,
)

private const val MS_IN_SECOND = 1000L
private const val SHORT_PAUSE_MS = 500L
private const val ITEM_PAUSE_MS = 1200L
private const val PREFETCH_LIMIT = 12

/** What the voice says for one question, in order. */
internal fun questionScript(
    question: UziTestQuestion,
    settings: ReaderSettings,
): List<ReadLine> =
    buildList {
        add(ReadLine(ReadStage.QUESTION, "Савол ${question.number}. ${question.question}", SHORT_PAUSE_MS))
        if (settings.thinkSeconds > 0) add(ReadLine(ReadStage.THINK, null, settings.thinkSeconds * MS_IN_SECOND))
        repeat(settings.repeats.coerceIn(1, 3)) { round ->
            val lead = if (round == 0) "Жавоб: " else "Яна бир марта: "
            add(ReadLine(ReadStage.ANSWER, lead + question.answer, SHORT_PAUSE_MS))
        }
        if (settings.explain && question.explanation.isNotBlank()) {
            add(ReadLine(ReadStage.EXPLANATION, question.explanation, SHORT_PAUSE_MS))
        }
        if (question.warning.isNotBlank()) add(ReadLine(ReadStage.WARNING, "Эътибор беринг: ${question.warning}", SHORT_PAUSE_MS))
        add(ReadLine(ReadStage.THINK, null, ITEM_PAUSE_MS))
    }

/** What the voice says for one topic lesson: the title, then each paragraph. */
internal fun lessonScript(lesson: UziTestLesson): List<ReadLine> =
    buildList {
        add(ReadLine(ReadStage.LESSON, lesson.title, SHORT_PAUSE_MS))
        lesson.paragraphs.forEachIndexed { index, paragraph ->
            add(ReadLine(ReadStage.LESSON, paragraph, ITEM_PAUSE_MS, paragraph = index))
        }
    }

private fun nextIndex(
    item: Int,
    count: Int,
    loop: Boolean,
): Int =
    when {
        item + 1 < count -> item + 1
        loop -> 0
        else -> -1
    }

/** The voice for the test reader: null in builds without an Uzbek voice. */
internal fun uziTestVoice(): (suspend (String) -> DubClip)? {
    val synthesize = DubbingVoiceSample.synthesize ?: return null
    return { text ->
        val prepared = prepareUzbekSpeech(uziTestSpeechText(text), SpeechOptions(readUnits = true))
        synthesize(DubbingSettings.voiceId.value, prepared)
    }
}

internal class UziTestReader(
    private val voice: suspend (String) -> DubClip,
) {
    // Its own supervisor scope: one failed speech request must not cancel the rest of the reader.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing
    private val _index = MutableStateFlow(0)
    val index: StateFlow<Int> = _index
    private val _line = MutableStateFlow<ReadLine?>(null)
    val line: StateFlow<ReadLine?> = _line
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var job: Job? = null
    private var generation = 0
    private var player: MediaPlayer? = null
    private val clips = LinkedHashMap<String, Deferred<DubClip>>()

    var settings = ReaderSettings()

    fun select(index: Int) {
        _index.value = index
        _line.value = null
    }

    /** Reads items from the current index on; [script] gives the lines of item i, [count] how many there are. */
    fun play(
        count: Int,
        loop: Boolean,
        script: (Int) -> List<ReadLine>,
    ) {
        stop()
        if (count == 0) return
        _error.value = null
        _playing.value = true
        val run = ++generation
        job =
            scope.launch {
                try {
                    readFrom(count, loop, script)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    _error.value = "Ovoz olinmadi: ${failure.message ?: "internetni tekshiring"}"
                } finally {
                    // A cancelled run finishes after the next one started; only the current run resets the state.
                    if (run == generation) {
                        _playing.value = false
                        _line.value = null
                    }
                }
            }
    }

    private suspend fun readFrom(
        count: Int,
        loop: Boolean,
        script: (Int) -> List<ReadLine>,
    ) {
        var item = _index.value.coerceIn(0, count - 1)
        while (true) {
            _index.value = item
            val lines = script(item)
            val nextItem = nextIndex(item, count, loop)
            val spoken = lines.mapNotNull { it.text }
            val nextFirst = if (nextItem >= 0) script(nextItem).firstNotNullOfOrNull { it.text } else null
            lines.forEach { line ->
                _line.value = line
                line.text?.let { text ->
                    // Fetch the following line's speech while this one plays.
                    (spoken.getOrNull(spoken.indexOf(text) + 1) ?: nextFirst)?.let(::clipFor)
                    playClip(clipFor(text).await())
                }
                delay(line.pauseAfterMs)
            }
            if (nextItem < 0) return
            item = nextItem
        }
    }

    private fun clipFor(text: String): Deferred<DubClip> {
        clips[text]?.takeUnless { it.isCancelled }?.let { return it }
        while (clips.size >= PREFETCH_LIMIT) clips.remove(clips.keys.first())
        val clip = scope.async { voice(text) }
        clips[text] = clip
        return clip
    }

    private suspend fun playClip(clip: DubClip) =
        suspendCancellableCoroutine { continuation ->
            val media =
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    setDataSource(clip.file.path)
                    setOnCompletionListener {
                        if (player === it) player = null
                        it.release()
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                    prepare()
                }
            player = media
            continuation.invokeOnCancellation {
                if (player === media) {
                    player = null
                    runCatching { media.stop() }
                    media.release()
                }
            }
            // Setting a speed on a prepared player starts it, so 1x simply calls start().
            if (settings.speed != 1f) media.playbackParams = PlaybackParams().setSpeed(settings.speed) else media.start()
        }

    fun stop() {
        job?.cancel()
        job = null
        _playing.value = false
        _line.value = null
    }

    fun release() {
        stop()
        clips.clear()
        scope.cancel()
    }
}
