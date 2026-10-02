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

    /** Lines starting at or before this time have been spoken or skipped. */
    private var doneThroughMs = Long.MIN_VALUE

    fun update(next: List<DubLine>) {
        if (next.isEmpty()) return
        val byKey = LinkedHashMap<String, DubLine>()
        (lines + next).forEach { byKey[it.key] = it }
        lines = byKey.values.sortedBy(DubLine::startMs)
    }

    fun seek(timeMs: Long) {
        doneThroughMs = timeMs - SEEK_GRACE_MS
    }

    fun reset() {
        lines = emptyList()
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

    fun started(line: DubLine) {
        doneThroughMs = maxOf(doneThroughMs, line.startMs)
    }

    /** The gap until the following line, which the clip should fit into. */
    fun slotAfter(line: DubLine): Long {
        val following = lines.firstOrNull { it.startMs > line.startMs }
        return (following?.startMs ?: line.endMs) - line.startMs
    }

    private companion object {
        const val SEEK_GRACE_MS = 300L
        const val START_TOLERANCE_MS = 50L
    }
}
