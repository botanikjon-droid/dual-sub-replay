package com.kienhoang.dualsubreplay.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale

/** Upper bound for a subtitle file the user imports, matching the caption download cap. */
internal const val MAX_SUBTITLE_FILE_BYTES = 8 * 1024 * 1024

private val TIMING_LINE =
    Regex(
        """^\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})""",
    )
private val TAG = Regex("<[^>]*>")

/**
 * Writes segments as SubRip (.srt). One block per segment keeps the timecodes the app uses, so a
 * file translated line by line and imported back lines up with the same sentences.
 */
internal fun formatSrt(segments: List<SubtitleSegment>): String =
    buildString {
        segments.forEachIndexed { index, segment ->
            if (index > 0) append('\n')
            append(index + 1).append('\n')
            append(srtTime(segment.startMs)).append(" --> ").append(srtTime(segment.endMs)).append('\n')
            append(segment.originalText.replace("\r", "").trim()).append('\n')
        }
    }

internal fun srtTime(ms: Long): String {
    val value = ms.coerceAtLeast(0L)
    val hours = value / 3_600_000
    val minutes = value / 60_000 % 60
    val seconds = value / 1_000 % 60
    val millis = value % 1_000
    return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
}

/**
 * Parses SubRip (.srt) or WebVTT (.vtt) text. Cue numbers, WebVTT headers, NOTE/STYLE blocks,
 * cue settings and inline tags are ignored; text lines of one cue are joined with a space.
 * Cues come back sorted by start time. Malformed blocks are skipped rather than failing the file.
 */
internal fun parseSubtitleFile(text: String): List<RawCaptionCue> {
    val lines =
        text
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
    val cues = mutableListOf<RawCaptionCue>()
    var index = 0
    while (index < lines.size) {
        val match = TIMING_LINE.find(lines[index])
        if (match == null) {
            index++
            continue
        }
        val start = parseTimestamp(match.groupValues[1])
        val end = parseTimestamp(match.groupValues[2])
        index++
        val body = mutableListOf<String>()
        while (index < lines.size && isCueTextLine(lines[index])) {
            body += lines[index]
            index++
        }
        val cueText =
            body
                .joinToString(" ") { it.replace(TAG, "").trim() }
                .replace(Regex("\\s+"), " ")
                .trim()
        if (start != null && end != null && end > start && cueText.isNotEmpty()) {
            cues += RawCaptionCue(start, end, cueText)
        }
    }
    return cues.sortedBy { it.startMs }
}

/** A blank line, the next timing line, or a Markdown code fence an AI chat wraps around SRT ends a cue. */
private fun isCueTextLine(line: String): Boolean =
    line.isNotBlank() && TIMING_LINE.find(line) == null && !line.trimStart().startsWith("```")

/** Accepts `HH:MM:SS,mmm`, `HH:MM:SS.mmm` and the WebVTT short form `MM:SS.mmm`. */
internal fun parseTimestamp(value: String): Long? {
    val parts = value.trim().replace(',', '.').split(':')
    if (parts.size !in 2..3) return null
    val secondsPart = parts.last().split('.')
    if (secondsPart.size != 2) return null
    val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
    val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
    val seconds = secondsPart[0].toLongOrNull() ?: return null
    val millis = secondsPart[1].padEnd(3, '0').take(3).toLongOrNull() ?: return null
    if (minutes >= 60 || seconds >= 60) return null
    return ((hours * 60 + minutes) * 60 + seconds) * 1_000 + millis
}

/**
 * Replaces the machine translation of the visible [rows] with the user's imported cues.
 *
 * Each imported cue is shown on the single row it overlaps the most, so a sentence split into
 * several short rows is read once instead of being repeated on every row (and spoken once by the
 * dubbing voice). Rows that receive no cue get an empty translation, which the UI shows as blank.
 */
internal fun applyImportedTranslation(
    rows: List<SubtitleSegment>,
    cues: List<RawCaptionCue>,
): List<SubtitleSegment> {
    if (rows.isEmpty()) return rows
    val assigned = Array(rows.size) { mutableListOf<String>() }
    val windowStart = rows.minOf { it.startMs }
    val windowEnd = rows.maxOf { it.endMs }
    cues.forEach { cue ->
        if (cue.endMs <= windowStart || cue.startMs >= windowEnd) return@forEach
        var best = -1
        var bestOverlap = 0L
        rows.forEachIndexed { index, row ->
            val overlap = minOf(row.endMs, cue.endMs) - maxOf(row.startMs, cue.startMs)
            if (overlap > bestOverlap) {
                best = index
                bestOverlap = overlap
            }
        }
        if (best >= 0) assigned[best] += cue.text
    }
    return rows.mapIndexed { index, row -> row.copy(translatedText = assigned[index].joinToString(" ")) }
}

/** Imported cues as segments, so they can be written back with [formatSrt]. */
internal fun List<RawCaptionCue>.toSegments(): List<SubtitleSegment> =
    mapIndexed { index, cue -> SubtitleSegment(index.toLong(), cue.startMs, cue.endMs, cue.text) }

/**
 * Adds [incoming] cues to an earlier import of the same video. Earlier cues inside the time range
 * the new part covers are replaced, so a long lecture can be translated and imported in parts,
 * and a part can be re-imported after it is corrected.
 */
internal fun mergeImportedCues(
    existing: List<RawCaptionCue>,
    incoming: List<RawCaptionCue>,
): List<RawCaptionCue> {
    if (incoming.isEmpty()) return existing
    val from = incoming.minOf { it.startMs }
    val to = incoming.maxOf { it.endMs }
    val kept = existing.filter { it.endMs <= from || it.startMs >= to }
    return (kept + incoming).sortedBy { it.startMs }
}

/**
 * The text shared to an AI chat (Claude, ChatGPT): an instruction in Uzbek followed by the SRT.
 * The instruction lines carry no timecodes, so if the reply repeats them, import ignores them.
 */
internal fun translationRequest(
    srt: String,
    targetLanguageName: String,
): String =
    buildString {
        append("Quyidagi SRT subtitr \u2014 ultratovush (UZI) bo\u2018yicha ingliz tilidagi video dars. ")
        append("Uni ").append(targetLanguageName).append(" tiliga tibbiy jihatdan aniq tarjima qil.\n")
        append("Qoidalar:\n")
        append("1. Faqat tayyor SRT qaytar: izoh, sarlavha yoki kod bloki qo\u2018shma.\n")
        append("2. Blok raqamlari va vaqt kodlarini (00:00:01,000 --> 00:00:04,000) aynan saqla.\n")
        append("3. Har bir blokni alohida tarjima qil; bloklarni birlashtirma va bo\u2018lma.\n")
        append("4. Raqamlar, o\u2018lchov birliklari va qisqartmalarni (PSV, RI, CBD, mm) o\u2018zgartirma.\n")
        append("5. Javob sig\u2018masa, to\u2018xtagan blokdan keyingisidan davom et.\n\n")
        append(srt)
    }

/** Reads at most [maxBytes] of UTF-8 text; a larger stream is rejected instead of truncated. */
internal fun readBoundedText(
    input: InputStream,
    maxBytes: Int = MAX_SUBTITLE_FILE_BYTES,
): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        output.write(buffer, 0, read)
        require(output.size() <= maxBytes) { "Fayl juda katta (8 MB dan ortiq)." }
    }
    return output.toByteArray().toString(Charsets.UTF_8)
}
