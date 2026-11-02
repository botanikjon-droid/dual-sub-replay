package com.kienhoang.dualsubreplay.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URLEncoder

/*
 * "Mavzuni o‘qish": reading for a lecture, because a video rarely covers its topic whole.
 * Two layers: checked references of every catalog section (society guidelines, StatPearls, PMC, Radiopaedia;
 * assets/uzi_reading.tsv), and searches built from the lecture's own title in Radiopaedia, the NCBI bookshelf
 * (StatPearls) and PubMed (free review articles). The searches never go stale; the references are checked by hand.
 */

internal data class ReadingLink(
    val title: String,
    val source: String,
    val url: String,
)

internal const val UZI_READING_ASSET = "uzi_reading.tsv"
private const val READING_FIELDS = 4

/** Section name -> its references, in file order; comment, header and non-https lines are skipped. */
internal fun parseUziReading(text: String): Map<String, List<ReadingLink>> {
    val bySection = linkedMapOf<String, MutableList<ReadingLink>>()
    text.lineSequence().forEach { line ->
        if (line.isBlank() || line.startsWith("#")) return@forEach
        val fields = line.split('\t').map(String::trim)
        if (fields.size < READING_FIELDS || fields[0] == "section") return@forEach
        val (section, title, source, url) = fields
        if (!url.startsWith("https://") || title.isEmpty()) return@forEach
        bySection.getOrPut(section) { mutableListOf() } += ReadingLink(title, source, url)
    }
    return bySection
}

private val BRACKETED_PART = Regex("""\([^)]*\)|\[[^]]*]""")
private val SERIES_PREFIX = Regex("""^(part|lecture|lesson|session|module|chapter|episode)\s*\d+\s*[:.\-–]\s*""", RegexOption.IGNORE_CASE)
private val DATE = Regex("""\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+\d{1,2},?\s+\d{4}\b""", RegexOption.IGNORE_CASE)
private val YEAR_OR_HASHTAG = Regex("""\b(19|20)\d{2}\b|#\S+""")
private val NOT_A_WORD = Regex("""[^\p{L}\p{N}'’\-/ ]""")
private val NOISE_WORDS =
    setOf(
        "how",
        "to",
        "the",
        "a",
        "an",
        "of",
        "and",
        "for",
        "in",
        "on",
        "with",
        "by",
        "your",
        "you",
        "need",
        "here's",
        "breakdown",
        "tutorial",
        "lecture",
        "video",
        "webinar",
        "explained",
        "made",
        "easy",
        "simple",
        "simplified",
        "step",
        "basics",
        "introduction",
        "intro",
        "part",
        "full",
        "complete",
        "course",
        "class",
        "hot",
        "tip",
        "tips",
        "md",
        "dr",
        "usg",
        "ultrasound",
        "sonography",
        "scan",
        "|",
    )
private const val MAX_QUERY_WORDS = 7

/**
 * The search words of a lecture title: the first part before " | ", without speakers in brackets, dates,
 * series numbers and filler, plus "ultrasound" so the results stay in the field.
 * "Carotid Duplex Interpretations (Zsolt Garami, MD) January 18, 2022" -> "Carotid Duplex Interpretations ultrasound".
 */
internal fun readingSearchQuery(videoTitle: String): String {
    val head =
        videoTitle
            .substringBefore(" | ")
            .replace(BRACKETED_PART, " ")
            .replace(DATE, " ")
            .replace(YEAR_OR_HASHTAG, " ")
            .trim()
            .replace(SERIES_PREFIX, "")
            .replace(NOT_A_WORD, " ")
    val words = head.split(Regex("\\s+")).filter { it.isNotBlank() && it.lowercase() !in NOISE_WORDS }.take(MAX_QUERY_WORDS)
    return (words + "ultrasound").joinToString(" ")
}

private fun encoded(query: String): String = URLEncoder.encode(query, "UTF-8")

/** Searches for one lecture: Radiopaedia articles, the NCBI bookshelf (StatPearls) and free PubMed reviews. */
internal fun lessonSearchLinks(videoTitle: String): List<ReadingLink> {
    val query = readingSearchQuery(videoTitle)
    val term = encoded(query)
    return listOf(
        ReadingLink("Radiopaedia: “$query” bo‘yicha maqolalar", "Radiopaedia", "https://radiopaedia.org/search?q=$term&scope=articles"),
        ReadingLink("StatPearls va NCBI kitoblari: “$query”", "NCBI Bookshelf", "https://www.ncbi.nlm.nih.gov/books/?term=$term"),
        ReadingLink(
            "PubMed: bepul sharh maqolalar, “$query”",
            "PubMed",
            "https://pubmed.ncbi.nlm.nih.gov/?term=$term&filter=pubt.review&filter=simsearch2.ffrft",
        ),
    )
}

/** The catalog section and title of [videoId], or null when the lecture is not in the catalog. */
internal fun catalogPlaceOf(
    sections: List<CatalogSection>,
    videoId: String,
): Pair<String, CatalogVideo>? {
    sections.forEach { section ->
        section.topics.forEach { topic ->
            topic.videos.firstOrNull { it.videoId == videoId }?.let { return section.name to it }
        }
    }
    return null
}

/** What the translator is told about a lecture, so a misheard word is read in the lecture's own topic. */
internal data class LessonInfo(
    val title: String,
    val section: String,
    val topic: String,
)

/** The lecture's title, section and topic from the catalog; only the title (if known) for one outside it. */
internal fun lessonInfoOf(
    sections: List<CatalogSection>,
    videoId: String,
    fallbackTitle: String?,
): LessonInfo? {
    sections.forEach { section ->
        section.topics.forEach { topic ->
            topic.videos.firstOrNull { it.videoId == videoId }?.let { return LessonInfo(it.title, section.name, topic.name) }
        }
    }
    return fallbackTitle?.takeIf(String::isNotBlank)?.let { LessonInfo(it, "", "") }
}

internal object UziReadingStore {
    private val _links = MutableStateFlow<Map<String, List<ReadingLink>>>(emptyMap())
    val links: StateFlow<Map<String, List<ReadingLink>>> = _links

    suspend fun load(context: Context) {
        if (_links.value.isNotEmpty()) return
        _links.value =
            withContext(Dispatchers.IO) {
                try {
                    context.assets.open(UZI_READING_ASSET).use { parseUziReading(it.readBytes().toString(Charsets.UTF_8)) }
                } catch (error: IOException) {
                    Log.w("UziReading", "Reading list not read", error)
                    emptyMap()
                }
            }
    }
}
