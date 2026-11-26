package com.kienhoang.dualsubreplay.data

import java.io.File
import java.util.Locale
import java.util.TreeMap

/**
 * Captions recorded from the YouTube page while a video plays, for videos whose caption track the app cannot
 * download. The page shows only the current one or two lines and then removes them; this keeps every word with
 * the media time it appeared at, so the whole lecture becomes an ordinary subtitle file.
 *
 * Only rendered caption text is kept (never audio or video). Watching a part again replaces what was recorded
 * for that part, so a pass over a skipped or badly recorded stretch fixes it.
 */
internal class CaptionRecording(
    val videoId: String,
) {
    /** Caption language as the page reports it (`en`, `en-US`), or null until a caption is seen. */
    var language: String? = null
        private set

    /** The user chose to use this recording as the lesson's subtitles. */
    var complete: Boolean = false

    /** Words by the media time they appeared at; an empty list marks the moment the caption was hidden. */
    private val events = TreeMap<Long, MutableList<String>>()
    private val covered = mutableListOf<LongRange>()
    private var passFrom: Long? = null
    private var passTo = 0L
    private var lastRevision: Long? = null

    val isEmpty: Boolean get() = events.values.none { it.isNotEmpty() }

    /**
     * One page sample: [text] is everything the caption window shows at media time [timeMs]. [revision] changes
     * whenever the page text changes; a repeated revision is ignored. [seek] starts a new pass. Returns true when
     * something new was recorded.
     */
    fun record(
        timeMs: Long,
        text: String,
        present: Boolean,
        revision: Long,
        languageCode: String?,
        seek: Boolean = false,
    ): Boolean {
        if (seek) {
            // The sample taken at a seek still shows the text from before it; the next change starts the new pass.
            endPass()
            lastRevision = revision
            return false
        }
        if (revision == lastRevision) return false
        lastRevision = revision
        if (timeMs < 0) return false
        val from = passFrom
        if (from == null || timeMs < passTo) {
            endPass()
            passFrom = timeMs
            passTo = timeMs
            // A new pass replaces only what it actually plays, starting here.
            events.subMap(timeMs, true, timeMs, true).clear()
        } else {
            events.subMap(passTo, false, timeMs, true).clear()
        }
        passTo = timeMs
        val words = if (present) text.split(WHITESPACE).filter(String::isNotEmpty) else emptyList()
        if (words.isEmpty()) {
            val before = events.floorEntry(timeMs)
            if (before == null || before.value.isNotEmpty()) events[timeMs] = mutableListOf()
            return before?.value?.isNotEmpty() == true
        }
        languageCode?.takeIf { it.isNotBlank() }?.let { language = it }
        val fresh = words.drop(overlap(recentWords(timeMs), words))
        if (fresh.isEmpty()) return false
        val slot = events.getOrPut(timeMs) { mutableListOf() }
        slot += fresh
        return true
    }

    /** Ends the current pass (seek, pause of the recording, another video). */
    fun endPass() {
        val from = passFrom ?: return
        addCovered(from..maxOf(from, passTo))
        passFrom = null
        lastRevision = null
    }

    /** The parts of the video watched while recording, merged, in milliseconds. */
    fun coveredRanges(): List<LongRange> {
        val ranges = covered.toMutableList()
        passFrom?.let { ranges += it..maxOf(it, passTo) }
        return mergeRanges(ranges)
    }

    /** The words on screen just before [timeMs]: since the caption was last hidden, at most 30 s and 80 words. */
    private fun recentWords(timeMs: Long): List<String> {
        val result = ArrayDeque<String>()
        for ((time, words) in events.headMap(timeMs, true).descendingMap()) {
            if (words.isEmpty() || time < timeMs - CONTEXT_MS) break
            words.asReversed().forEach(result::addFirst)
            if (result.size >= CONTEXT_WORDS) break
        }
        return result.toList()
    }

    /** The recorded subtitles: words grouped into cues of at most 2 lines, split at sentences and pauses. */
    fun cues(): List<RawCaptionCue> {
        val built = mutableListOf<MutableList<SubtitleWord>>()
        val ends = mutableListOf<Long>()
        var current = mutableListOf<SubtitleWord>()
        var sentenceEnded = false

        fun close(end: Long) {
            if (current.isEmpty()) return
            built += current
            ends += end
            current = mutableListOf()
            sentenceEnded = false
        }
        for ((time, words) in events) {
            if (words.isEmpty()) {
                close(time)
                continue
            }
            for (word in words) {
                if (current.isNotEmpty()) {
                    val last = current.last().startMs
                    val length = current.sumOf { it.text.length + 1 } + word.length
                    when {
                        time - last > PAUSE_MS -> close(minOf(time, last + TAIL_MS))
                        sentenceEnded || length > MAX_CUE_CHARS || time - current.first().startMs > MAX_CUE_MS -> close(time)
                    }
                }
                current += SubtitleWord(word, time, time)
                sentenceEnded = current.size >= MIN_SENTENCE_WORDS && word.last() in SENTENCE_END
            }
        }
        close((current.lastOrNull()?.startMs ?: 0L) + TAIL_MS)
        return timedCues(built, ends)
    }

    /** Gives every cue a real duration: cues that start together share the time until the next start. */
    private fun timedCues(
        groups: List<List<SubtitleWord>>,
        ends: List<Long>,
    ): List<RawCaptionCue> {
        val starts = groups.map { it.first().startMs }.toMutableList()
        val stops = ends.toMutableList()
        var index = 0
        while (index < groups.size) {
            var last = index
            while (last + 1 < groups.size && starts[last + 1] == starts[index]) last++
            if (last > index) {
                val limit = (if (last + 1 < groups.size) starts[last + 1] else ends[last]).coerceAtLeast(starts[index] + TAIL_MS)
                val total = (index..last).sumOf { groups[it].sumOf { word -> word.text.length + 1 } }.coerceAtLeast(1)
                var start = starts[index]
                val from = starts[index]
                for (item in index..last) {
                    starts[item] = start
                    start += (limit - from) * groups[item].sumOf { it.text.length + 1 } / total
                }
                for (item in index until last) stops[item] = starts[item + 1]
            }
            index = last + 1
        }
        return groups.mapIndexed { item, group ->
            val start = starts[item]
            val next = if (item + 1 < groups.size) starts[item + 1] else Long.MAX_VALUE
            val end = minOf(maxOf(stops[item], start + MIN_CUE_MS), next).coerceAtLeast(start + 1)
            val times = spreadTimes(group.map { maxOf(it.startMs, start) }, end)
            val words =
                group.mapIndexed { position, word ->
                    SubtitleWord(word.text, times[position], times.getOrNull(position + 1) ?: end)
                }
            RawCaptionCue(start, end, group.joinToString(" ") { it.text }, words)
        }
    }

    private fun addCovered(range: LongRange) {
        covered += range
        val merged = mergeRanges(covered)
        covered.clear()
        covered += merged
    }

    /** Text form kept on the phone: a small header, then one line per moment (`time<TAB>words`, empty = hidden). */
    fun encode(): String =
        buildString {
            append(HEADER).append('\n')
            append("video\t").append(videoId).append('\n')
            append("language\t").append(language.orEmpty()).append('\n')
            append("complete\t").append(if (complete) "1" else "0").append('\n')
            append("covered\t").append(coveredRanges().joinToString(",") { "${it.first}-${it.last}" }).append('\n')
            events.forEach { (time, words) -> append(time).append('\t').append(words.joinToString(" ")).append('\n') }
        }

    companion object {
        const val HEADER = "# DualSub recorded captions v1"
        private val WHITESPACE = Regex("\\s+")
        private const val CONTEXT_MS = 30_000L
        private const val CONTEXT_WORDS = 80
        private const val PAUSE_MS = 2_500L
        private const val TAIL_MS = 1_500L
        private const val MIN_CUE_MS = 700L
        private const val MAX_CUE_MS = 7_000L
        private const val MAX_CUE_CHARS = 84
        private const val MIN_SENTENCE_WORDS = 3
        private const val SENTENCE_END = ".?!"

        /** Reads [encode]d text; null when it is not a recording of [videoId]. */
        fun decode(
            videoId: String,
            text: String,
        ): CaptionRecording? {
            val lines = text.removePrefix("").lines()
            if (lines.firstOrNull() != HEADER) return null
            val recording = CaptionRecording(videoId)
            for (line in lines.drop(1)) {
                if (line.isEmpty()) continue
                val key = line.substringBefore('\t')
                val value = line.substringAfter('\t', "")
                when (key) {
                    "video" -> if (value != videoId) return null
                    "language" -> recording.language = value.takeIf { it.isNotBlank() }
                    "complete" -> recording.complete = value == "1"
                    "covered" ->
                        value
                            .split(',')
                            .mapNotNull { part ->
                                val from = part.substringBefore('-').toLongOrNull()
                                val to = part.substringAfter('-', "").toLongOrNull()
                                if (from != null && to != null && to >= from) from..to else null
                            }.forEach(recording::addCovered)
                    else -> {
                        val time = key.toLongOrNull() ?: continue
                        recording.events[time] = value.split(WHITESPACE).filter(String::isNotEmpty).toMutableList()
                    }
                }
            }
            return recording
        }
    }
}

/** How many leading words of [words] are the last words of [recent], compared without case and punctuation. */
internal fun overlap(
    recent: List<String>,
    words: List<String>,
): Int {
    val left = recent.map(::comparable)
    val right = words.map(::comparable)
    for (size in minOf(left.size, right.size) downTo 1) {
        if (left.subList(left.size - size, left.size) == right.subList(0, size)) return size
    }
    return 0
}

private fun comparable(word: String): String = word.lowercase().filter(Char::isLetterOrDigit).ifEmpty { word }

/**
 * Word start times inside a cue that ends at [end]: words that appeared in the same page update share the time
 * until the next update evenly, so each word still gets its own moment for highlighting.
 */
internal fun spreadTimes(
    starts: List<Long>,
    end: Long,
): List<Long> {
    val result = starts.toMutableList()
    var index = 0
    while (index < result.size) {
        var last = index
        while (last + 1 < starts.size && starts[last + 1] <= starts[index]) last++
        val from = starts[index]
        val until = maxOf(if (last + 1 < starts.size) starts[last + 1] else end, from + (last - index + 1))
        val count = last - index + 1
        for (item in index..last) result[item] = from + (until - from) * (item - index) / count
        index = last + 1
    }
    return result
}

private fun mergeRanges(ranges: List<LongRange>): List<LongRange> {
    val merged = mutableListOf<LongRange>()
    ranges.sortedBy { it.first }.forEach { range ->
        val last = merged.lastOrNull()
        if (last != null && range.first <= last.last + 1_000) {
            merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
        } else {
            merged += range
        }
    }
    return merged
}

/** What the app shows about a video's recording. */
data class RecordingSummary(
    val cues: Int,
    val coveredMs: Long,
    val ranges: List<LongRange>,
    val complete: Boolean,
    val language: String?,
)

internal fun CaptionRecording.summary(): RecordingSummary {
    val ranges = coveredRanges()
    return RecordingSummary(cues().size, ranges.sumOf { it.last - it.first }, ranges, complete, language)
}

/** `0:00–6:12, 8:30–10:00` for the parts watched while recording. */
internal fun describeRanges(ranges: List<LongRange>): String = ranges.joinToString(", ") { "${clock(it.first)}–${clock(it.last)}" }

private fun clock(ms: Long): String {
    val seconds = ms / 1_000
    val hours = seconds / 3_600
    val minutes = seconds / 60 % 60
    val rest = seconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, rest)
    }
}

/** One file per video in [directory]: `{videoId}.tsv`. Call from an IO dispatcher. */
internal class RecordedCaptionStore(
    private val directory: File,
) {
    fun load(videoId: String): CaptionRecording? {
        val file = file(videoId) ?: return null
        if (!file.isFile || file.length() > MAX_BYTES) return null
        return runCatching { CaptionRecording.decode(videoId, file.readText(Charsets.UTF_8)) }.getOrNull()
    }

    /** Writes through a temporary file, so a crash mid-write keeps the previous recording. */
    fun save(
        videoId: String,
        encoded: String,
    ) {
        val target = file(videoId) ?: return
        check(directory.isDirectory || directory.mkdirs()) { "Yozib olingan subtitrni saqlab bo‘lmadi." }
        val temporary = File(directory, "${target.name}.tmp")
        temporary.writeText(encoded, Charsets.UTF_8)
        if (!temporary.renameTo(target)) {
            target.writeText(encoded, Charsets.UTF_8)
            temporary.delete()
        }
    }

    fun delete(videoId: String) {
        file(videoId)?.delete()
    }

    /** The recording of [videoId] when the user chose to use it as the lesson's subtitles. */
    fun completeCues(videoId: String): Pair<String?, List<RawCaptionCue>>? {
        val recording = load(videoId)?.takeIf { it.complete } ?: return null
        val cues = recording.cues()
        return if (cues.isEmpty()) null else recording.language to cues
    }

    private fun file(videoId: String): File? = if (VIDEO_ID.matches(videoId)) File(directory, "$videoId.tsv") else null

    companion object {
        const val FOLDER = "recorded-captions"
        private const val MAX_BYTES = 16L * 1024 * 1024
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    }
}
