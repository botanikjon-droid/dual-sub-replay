package com.kienhoang.dualsubreplay.data

/*
 * Checking single sentences of a stored translation. The doctor marks a sentence he doubts while watching;
 * the marked sentences go out as one plain-text file (English above Uzbek, with their times) to be checked
 * in another app, by another AI or by hand, and the corrected file comes back: each "UZ:" text replaces the
 * stored translation of its sentence, found by its time. Times are SRT-style, so the file stays readable.
 */

/** One sentence of a lesson: its time, the English heard and the stored Uzbek. */
internal data class ReviewSentence(
    val startMs: Long,
    val endMs: Long,
    val english: String,
    val uzbek: String,
)

private const val HEADER = "SHOKALON — tarjimani tekshirish"
private const val HINT =
    "Har bir gapda faqat “UZ:” qatorini tuzating. Raqam va vaqt qatoriga tegmang, keyin faylni SHOKALON'ga qayta yuklang."
private val BLOCK_START = Regex("""^\[(\d+)]\s+(\d{1,2}:\d{2}:\d{2}[,.]\d{3})\s*-->\s*(\d{1,2}:\d{2}:\d{2}[,.]\d{3})\s*$""")
private val VIDEO_LINK = Regex("""youtu\.be/([A-Za-z0-9_-]{11})""")

private const val MS_PER_SECOND = 1_000L
private const val MS_PER_MINUTE = 60_000L
private const val MS_PER_HOUR = 3_600_000L

internal fun reviewTime(ms: Long): String =
    "%02d:%02d:%02d,%03d".format(ms / MS_PER_HOUR, ms % MS_PER_HOUR / MS_PER_MINUTE, ms % MS_PER_MINUTE / MS_PER_SECOND, ms % MS_PER_SECOND)

private fun parseReviewTime(text: String): Long {
    val (clock, millis) = text.replace('.', ',').split(',')
    val (hours, minutes, seconds) = clock.split(':').map(String::toLong)
    return hours * MS_PER_HOUR + minutes * MS_PER_MINUTE + seconds * MS_PER_SECOND + millis.toLong()
}

/** The file sent out for checking. */
internal fun reviewFileText(
    title: String?,
    videoId: String,
    sentences: List<ReviewSentence>,
): String =
    buildString {
        append(HEADER).append('\n')
        title?.takeIf(String::isNotBlank)?.let { append("Dars: ").append(it).append('\n') }
        append("https://youtu.be/").append(videoId).append('\n')
        append(HINT).append("\n\n")
        sentences.forEachIndexed { index, sentence ->
            append('[').append(index + 1).append("] ")
            append(reviewTime(sentence.startMs)).append(" --> ").append(reviewTime(sentence.endMs)).append('\n')
            append("EN: ").append(sentence.english.trim()).append('\n')
            append("UZ: ").append(sentence.uzbek.trim()).append("\n\n")
        }
    }

/** The lesson a returned file belongs to (its link), or null. */
internal fun reviewFileVideoId(text: String): String? = VIDEO_LINK.find(text)?.groupValues?.get(1)

/** The corrected sentences of a returned file: time and the "UZ:" text (which may run over several lines). */
internal fun parseReviewFile(text: String): List<ReviewSentence> {
    val sentences = mutableListOf<ReviewSentence>()
    var start = -1L
    var end = -1L
    var english = StringBuilder()
    var uzbek: StringBuilder? = null

    fun close() {
        val corrected = uzbek?.toString()?.trim()
        if (start >= 0 && !corrected.isNullOrEmpty()) sentences += ReviewSentence(start, end, english.toString().trim(), corrected)
        start = -1L
        uzbek = null
        english = StringBuilder()
    }
    text.removePrefix("\uFEFF").lineSequence().map(String::trimEnd).forEach { line ->
        val block = BLOCK_START.matchEntire(line.trim())
        when {
            block != null -> {
                close()
                start = parseReviewTime(block.groupValues[2])
                end = parseReviewTime(block.groupValues[3])
            }
            start < 0 -> Unit
            line.trimStart().startsWith("UZ:") -> uzbek = StringBuilder(line.trimStart().removePrefix("UZ:").trim())
            line.trimStart().startsWith("EN:") -> english.append(line.trimStart().removePrefix("EN:").trim())
            uzbek != null && line.isNotBlank() -> uzbek?.append(' ')?.append(line.trim())
        }
    }
    close()
    return sentences
}

/**
 * Stored cues with the sentence from [startMs] to [endMs] replaced by [text] as one cue over that time. A cue
 * belongs to the sentence when its middle lies inside it, so a neighbour that only touches it stays.
 */
internal fun applySentenceCorrection(
    cues: List<RawCaptionCue>,
    startMs: Long,
    endMs: Long,
    text: String,
): List<RawCaptionCue> {
    val clean = text.replace(Regex("\\s+"), " ").trim()
    require(clean.isNotEmpty()) { "Tarjima bo‘sh bo‘lmasin." }
    val kept = cues.filterNot { (it.startMs + it.endMs) / 2 in startMs until endMs }
    return (kept + RawCaptionCue(startMs, endMs, clean)).sortedBy { it.startMs }
}

/** What a search-and-replace changed: the new cues, how many places, and a few of them for a preview. */
internal data class CueReplacement(
    val cues: List<RawCaptionCue>,
    val count: Int,
    val examples: List<String> = emptyList(),
)

private const val EXAMPLE_CONTEXT = 30
private const val MAX_EXAMPLES = 5

/** The pattern of [find]: its words in order, any spaces (or a cue boundary) between them. */
private fun phrasePattern(
    find: String,
    wholeWord: Boolean,
): Regex {
    val words = find.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    require(words.isNotEmpty()) { "Nimani almashtirishni yozing." }
    val body = words.joinToString("\\s+") { Regex.escape(it) }
    val letter = "[\\p{L}\\p{N}\u2018\u2019']"
    val pattern = if (wholeWord) "(?<!$letter)$body(?!$letter)" else body
    return Regex(pattern, RegexOption.IGNORE_CASE)
}

/**
 * Replaces every [find] (a word or a phrase) with [replacement], ignoring case. A phrase is found even when
 * its words are split over two cues ("akustik" ending one cue, "soya" starting the next): the replacement
 * goes into the cue where the phrase starts and the rest of the phrase leaves the next cue. With [wholeWord]
 * only whole words match ("jigar", not inside "jigarning"). A match that starts with a capital letter (a
 * sentence start) gets a capitalised replacement.
 */
internal fun replaceInCues(
    cues: List<RawCaptionCue>,
    find: String,
    replacement: String,
    wholeWord: Boolean,
): CueReplacement {
    val regex = phrasePattern(find, wholeWord)
    val starts = IntArray(cues.size)
    val joined =
        buildString {
            cues.forEachIndexed { index, cue ->
                if (index > 0) append(' ')
                starts[index] = length
                append(cue.text)
            }
        }

    fun cueAt(offset: Int): Int = starts.indices.last { starts[it] <= offset }
    val matches = regex.findAll(joined).toList()
    val texts = cues.map { it.text }.toMutableList()
    val examples = mutableListOf<String>()
    for (match in matches.asReversed()) {
        val first = cueAt(match.range.first)
        val last = cueAt(match.range.last)
        val text = if (match.value.first().isUpperCase()) replacement.replaceFirstChar(Char::uppercaseChar) else replacement
        val tail = texts[last].substring(match.range.last + 1 - starts[last])
        if (first == last) {
            texts[first] = texts[first].substring(0, match.range.first - starts[first]) + text + tail
        } else {
            texts[first] = texts[first].substring(0, match.range.first - starts[first]) + text
            for (middle in first + 1 until last) texts[middle] = ""
            texts[last] = tail.trimStart()
        }
    }
    matches.take(MAX_EXAMPLES).forEach { match ->
        val from = (match.range.first - EXAMPLE_CONTEXT).coerceAtLeast(0)
        val to = (match.range.last + 1 + EXAMPLE_CONTEXT).coerceAtMost(joined.length)
        examples +=
            "\u2026" + joined.substring(from, match.range.first) + "[" + match.value + "]" + joined.substring(match.range.last + 1, to) +
            "\u2026"
    }
    val changed = cues.mapIndexed { index, cue -> if (texts[index] == cue.text) cue else cue.copy(text = texts[index].trim()) }
    return CueReplacement(changed, matches.size, examples)
}
