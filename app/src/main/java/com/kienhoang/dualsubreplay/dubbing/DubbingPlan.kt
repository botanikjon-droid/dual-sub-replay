package com.kienhoang.dualsubreplay.dubbing

import com.kienhoang.dualsubreplay.data.SubtitleSegment

/*
 * Pure dubbing logic, kept free of Android so the default unit-test run covers it.
 * Only the "uz" build supplies a voice; elsewhere the controller stays idle.
 */

/** One spoken translation: a whole caption sentence, never a display-row slice. */
internal data class DubLine(
    val key: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/**
 * Whole-sentence lines from a caption window. Rows of one sentence carry consecutive slices of
 * its translation, so joining them restores the sentence. A sentence whose rows are not all in
 * the window, or not all translated yet, is left out until a later window completes it.
 */
internal fun dubLines(rows: List<SubtitleSegment>): List<DubLine> {
    val lines = mutableListOf<DubLine>()
    var index = 0
    while (index < rows.size) {
        val row = rows[index]
        val sentence = row.sentence
        if (sentence == null) {
            row.translatedText?.trim()?.takeIf(String::isNotEmpty)?.let { text ->
                lines += DubLine("${row.startMs}|${row.originalText}", row.startMs, row.endMs, text)
            }
            index++
            continue
        }
        var end = index
        while (end + 1 < rows.size && rows[end + 1].sentence?.let { it.text == sentence.text && it.cuts == sentence.cuts } == true) {
            end++
        }
        val group = rows.subList(index, end + 1)
        val complete =
            group.map { it.sentence?.index } == (0..sentence.cuts.size).toList() &&
                group.all { it.translatedText != null }
        val text = group.joinToString(" ") { it.translatedText.orEmpty().trim() }.replace(WHITESPACE, " ").trim()
        if (complete && text.isNotEmpty()) {
            lines += DubLine("${row.startMs}|${sentence.text}", row.startMs, group.last().endMs, text)
        }
        index = end + 1
    }
    return lines
}

private val WHITESPACE = Regex("\\s+")

/**
 * Start times of sentences whose rows are all in the window but not all translated yet. A held
 * video waits for these like it waits for speech that is not ready: the sentence will be spoken
 * once its translation arrives.
 */
internal fun dubPending(rows: List<SubtitleSegment>): List<Long> {
    val starts = mutableListOf<Long>()
    var index = 0
    while (index < rows.size) {
        val row = rows[index]
        val sentence = row.sentence
        if (sentence == null) {
            if (row.translatedText == null) starts += row.startMs
            index++
            continue
        }
        var end = index
        while (end + 1 < rows.size && rows[end + 1].sentence?.let { it.text == sentence.text && it.cuts == sentence.cuts } == true) {
            end++
        }
        val group = rows.subList(index, end + 1)
        val whole = group.map { it.sentence?.index } == (0..sentence.cuts.size).toList()
        if (whole && group.any { it.translatedText == null }) starts += row.startMs
        index = end + 1
    }
    return starts
}

/** How fast to speak a clip of [clipMs] so it ends before the next line, within [maxTempo]. */
internal fun dubTempo(
    clipMs: Long,
    slotMs: Long,
    lateMs: Long,
    maxTempo: Float,
): Float {
    val room = (slotMs - lateMs).coerceAtLeast(MIN_SLOT_MS)
    return (clipMs.toFloat() / room).coerceIn(1f, maxTempo)
}

private const val MIN_SLOT_MS = 500L

/** What to do at one playback tick when the video may be held until the dub catches up. */
internal sealed interface DubStep {
    /** Nothing is due, or a clip is about to end: keep playing. */
    data object Idle : DubStep

    /** The next line is due but another clip is still speaking: hold the video until it ends. */
    data object HoldForClip : DubStep

    /** The next line is due but its speech or translation is not ready: hold the video. */
    data object HoldForAudio : DubStep

    /** Gave up waiting for the next line; it is skipped. */
    data object Skipped : DubStep

    data class Start(
        val line: DubLine,
    ) : DubStep
}

/**
 * Decides which line to speak at each playback tick. A line plays once, in order. A line that
 * would start more than [maxLateMs] late is skipped, and a clip still playing [cutAfterMs] after
 * the next line was due is cut short, so the voice never drifts far behind the video. A seek
 * forgets everything before the new position.
 */
internal class DubScheduler(
    private val maxLateMs: Long = 2_000L,
    private val cutAfterMs: Long = 1_200L,
) {
    private var lines: List<DubLine> = emptyList()
    private var pending: List<Long> = emptyList()

    /** Lines starting at or before this time have been spoken or skipped. */
    private var doneThroughMs = Long.MIN_VALUE

    fun update(next: List<DubLine>) {
        if (next.isEmpty()) return
        val byKey = LinkedHashMap<String, DubLine>()
        (lines + next).forEach { byKey[it.key] = it }
        lines = byKey.values.sortedBy(DubLine::startMs)
    }

    /** Replaces the starts of sentences still waiting for their translation. */
    fun updatePending(starts: List<Long>) {
        pending = starts.sorted()
    }

    fun seek(timeMs: Long) {
        doneThroughMs = timeMs - SEEK_GRACE_MS
    }

    fun reset() {
        lines = emptyList()
        pending = emptyList()
        doneThroughMs = Long.MIN_VALUE
    }

    /** Lines worth preparing: not yet spoken and starting within [aheadMs]. */
    fun upcoming(
        timeMs: Long,
        aheadMs: Long,
    ): List<DubLine> = lines.filter { it.startMs > doneThroughMs && it.endMs >= timeMs && it.startMs <= timeMs + aheadMs }

    /**
     * The line to start now, or null to keep waiting. [busy] means a clip is still playing, which
     * the caller stops before starting the returned line; [ready] tells whether its audio exists.
     */
    fun next(
        timeMs: Long,
        busy: Boolean,
        ready: (DubLine) -> Boolean,
    ): DubLine? {
        if (doneThroughMs == Long.MIN_VALUE) seek(timeMs)
        for (line in lines) {
            if (line.startMs <= doneThroughMs) continue
            if (line.startMs > timeMs + START_TOLERANCE_MS) return null
            val late = timeMs - line.startMs
            if (late > maxLateMs) {
                doneThroughMs = line.startMs // too late to sound natural: skip it
                continue
            }
            return if (!ready(line) || (busy && late < cutAfterMs)) null else line
        }
        return null
    }

    /**
     * The next step when the video can be held. Lines play whole and in order: a clip that is
     * still speaking when the next line is due holds the video (unless it ends within
     * [SHORT_WAIT_MS]), and a line whose speech or translation is not ready holds it too, for at
     * most [maxWaitMs] ([waitedMs] is how long it has waited) before that line is skipped.
     * [clipRemainingMs] is null when no clip is speaking; [holding] is whether the video is
     * already held.
     */
    fun decide(
        timeMs: Long,
        clipRemainingMs: Long?,
        waitedMs: Long,
        maxWaitMs: Long,
        ready: (DubLine) -> Boolean,
        failed: (DubLine) -> Boolean = { false },
        holding: Boolean = false,
    ): DubStep {
        if (doneThroughMs == Long.MIN_VALUE) seek(timeMs)
        var line: DubLine?
        var itemStart: Long
        while (true) {
            line = lines.firstOrNull { it.startMs > doneThroughMs }
            val pendingStart = pending.firstOrNull { it > doneThroughMs }
            itemStart = listOfNotNull(line?.startMs, pendingStart).minOrNull() ?: return DubStep.Idle
            if (itemStart >= timeMs - STALE_MS) break
            // The video moved well past it (not while held): too late to be worth speaking.
            doneThroughMs = itemStart
        }
        if (itemStart > timeMs + START_TOLERANCE_MS) return DubStep.Idle
        if (clipRemainingMs != null) {
            // A hold lasts until the clip ends; stopping and starting again every few hundred
            // milliseconds would make the video stutter.
            return if (holding || clipRemainingMs > SHORT_WAIT_MS) DubStep.HoldForClip else DubStep.Idle
        }
        if (line != null && line.startMs == itemStart && ready(line)) return DubStep.Start(line)
        if (waitedMs > maxWaitMs || (line != null && line.startMs == itemStart && failed(line))) {
            doneThroughMs = itemStart
            return DubStep.Skipped
        }
        return DubStep.HoldForAudio
    }

    fun started(line: DubLine) {
        doneThroughMs = maxOf(doneThroughMs, line.startMs)
    }

    /** The gap until the following line, which the clip should fit into. */
    fun slotAfter(line: DubLine): Long {
        val following = lines.firstOrNull { it.startMs > line.startMs }
        return (following?.startMs ?: line.endMs) - line.startMs
    }

    internal companion object {
        const val SEEK_GRACE_MS = 300L
        const val START_TOLERANCE_MS = 50L

        /** A line that started longer ago than this is skipped silently, never spoken late. */
        const val STALE_MS = 3_000L

        /** A clip ending sooner than this after the next line is due is not worth a pause; fewer, longer holds look smoother than many short ones. */
        const val SHORT_WAIT_MS = 1_200L
    }
}
