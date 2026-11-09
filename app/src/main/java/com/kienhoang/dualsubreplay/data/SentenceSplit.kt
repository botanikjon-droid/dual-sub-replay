package com.kienhoang.dualsubreplay.data

/*
 * A stored Uzbek cue often ends one sentence and starts the next ("…deyishardi. Mana, bu…"), so rows built
 * from whole cues could only close in the middle of a sentence. Such a cue is cut at each sentence end into
 * pieces that share its time in proportion to their length, so a row can end exactly where a sentence ends.
 */

/** A sentence end followed by the start of the next sentence (a capital letter, a digit or a quote). */
private val INNER_SENTENCE_END = Regex("""(?<=[.!?…]["”»')\]]{0,3})\s+(?=["“«(]?[\p{Lu}\d])""")

/** Pieces shorter than this are left joined to their neighbour ("Ha. Bu…", "Dr. Zoghbi" stay whole). */
private const val MIN_PIECE_CHARS = 4

/** [cue] cut at the sentence ends inside it; a cue with none comes back alone. */
internal fun splitAtSentenceEnds(cue: RawCaptionCue): List<RawCaptionCue> {
    val text = cue.text.trim()
    val pieces = mutableListOf<String>()
    var start = 0
    INNER_SENTENCE_END.findAll(text).forEach { match ->
        val piece = text.substring(start, match.range.first).trim()
        if (piece.length >= MIN_PIECE_CHARS && text.length - match.range.last - 1 >= MIN_PIECE_CHARS) {
            pieces += piece
            start = match.range.last + 1
        }
    }
    if (pieces.isEmpty()) return listOf(cue)
    pieces += text.substring(start).trim()
    val total = pieces.sumOf { it.length }.coerceAtLeast(1)
    val duration = (cue.endMs - cue.startMs).coerceAtLeast(0)
    var at = cue.startMs
    var chars = 0
    return pieces.mapIndexed { index, piece ->
        chars += piece.length
        val end = if (index == pieces.lastIndex) cue.endMs else cue.startMs + duration * chars / total
        RawCaptionCue(at, end, piece).also { at = end }
    }
}

/** Every cue cut at its inner sentence ends. */
internal fun splitCuesAtSentenceEnds(cues: List<RawCaptionCue>): List<RawCaptionCue> = cues.flatMap(::splitAtSentenceEnds)
