package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.RawCaptionCue
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.SubtitleStore
import java.io.File

/*
 * Whole sentences for a lesson with a stored translation (SHOKALON files, Claude, Gemini, a file). YouTube's
 * automatic English captions carry almost no punctuation (about 3 % of cues end a sentence), so rows cut from
 * them break sentences in the middle, and a half sentence of Uzbek (verb last) loses its meaning. The stored
 * Uzbek translation is punctuated, so its cues are joined into sentences, and each display row is one such
 * sentence with exactly the English words spoken in the same time: both lines show the whole thought.
 */

/** One translated sentence: the stored cues from [startMs] to [endMs] and their joined text. */
internal data class TranslatedSentenceSpan(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

private val SENTENCE_END = Regex("""[.!?…][”"»')\]]*$""")
private val NON_SPEECH = Regex("""^(♪+|\[[^\]]*])$""")
private const val MAX_CUES_PER_SENTENCE = 6
private const val MAX_SENTENCE_MS = 30_000L

/** Joins stored cues until the Uzbek text ends a sentence; music and [bracketed] cues stay alone. */
internal fun translatedSentenceSpans(cues: List<RawCaptionCue>): List<TranslatedSentenceSpan> {
    val spans = mutableListOf<TranslatedSentenceSpan>()
    val open = mutableListOf<RawCaptionCue>()

    fun close() {
        if (open.isEmpty()) return
        spans +=
            TranslatedSentenceSpan(
                startMs = open.minOf { it.startMs },
                endMs = open.maxOf { it.endMs },
                text = open.joinToString(" ") { it.text.trim() }.replace(Regex("\\s+"), " ").trim(),
            )
        open.clear()
    }
    cues.sortedBy { it.startMs }.forEach { cue ->
        val text = cue.text.trim()
        if (text.isEmpty()) return@forEach
        if (NON_SPEECH.matches(text)) {
            close()
            open += cue
            close()
            return@forEach
        }
        open += cue
        val tooLong = open.size >= MAX_CUES_PER_SENTENCE || open.last().endMs - open.first().startMs >= MAX_SENTENCE_MS
        if (SENTENCE_END.containsMatchIn(text) || tooLong) close()
    }
    close()
    return spans
}

/**
 * Display rows that follow [spans]: each source row (English words) joins the span it overlaps most; a row
 * outside every span (an untranslated part) stays on its own. Rows keep the span's times, so the stored
 * translation lands on them whole.
 */
internal fun importedSentenceRows(
    source: List<SubtitleSegment>,
    spans: List<TranslatedSentenceSpan>,
): List<SubtitleSegment> {
    val members = Array(spans.size) { mutableListOf<SubtitleSegment>() }
    val alone = mutableListOf<SubtitleSegment>()
    var first = 0
    source.sortedBy { it.startMs }.forEach { row ->
        while (first < spans.size && spans[first].endMs <= row.startMs) first += 1
        var best = -1
        var bestOverlap = 0L
        var index = maxOf(0, first - 1)
        while (index < spans.size && spans[index].startMs < row.endMs) {
            val overlap = minOf(row.endMs, spans[index].endMs) - maxOf(row.startMs, spans[index].startMs)
            if (overlap > bestOverlap) {
                best = index
                bestOverlap = overlap
            }
            index += 1
        }
        if (best >= 0) members[best] += row else alone += row
    }
    val rows =
        spans.mapIndexed { index, span ->
            val english = members[index]
            SubtitleSegment(
                id = 0,
                startMs = span.startMs,
                endMs = span.endMs,
                originalText = english.joinToString(" ") { it.originalText.trim() }.trim(),
                words = english.flatMap { it.words },
            )
        } + alone
    return rows.sortedBy { it.startMs }.mapIndexed { index, row -> row.copy(id = index.toLong()) }
}

/** The display track of a lesson with a stored translation: one row per translated sentence. */
internal suspend fun prepareImportedSentenceStore(
    source: SubtitleStore,
    cues: List<RawCaptionCue>,
    directory: File,
): SubtitleStore {
    val rows = importedSentenceRows(source.read(0 until source.size), translatedSentenceSpans(cues))
    return SubtitleStore.create(directory, rows)
}
