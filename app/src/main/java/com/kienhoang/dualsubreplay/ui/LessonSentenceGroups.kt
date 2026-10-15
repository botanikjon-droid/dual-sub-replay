package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SubtitleSegment

/*
 * DeepL translates what it is given, one text at a time. A sentence the captions split over two rows
 * ("…where the splenic vein" / "joins the portal vein…") translated row by row leaves a dangling half
 * in each. So DeepL gets whole sentences, and each translation is cut back into the sentence's rows with
 * the same slicing the player uses for live translation.
 */

/** One sentence of the lesson and the rows that show it. */
internal data class SentenceGroup(
    val rows: List<Int>,
    val text: String,
    /** Where the sentence's rows after the first start, as character offsets into [text]. */
    val cuts: List<Int>,
)

/** Rows of a complete split sentence become one group; every other row is a group of its own. */
internal fun sentenceGroups(segments: List<SubtitleSegment>): List<SentenceGroup> {
    val groups = mutableListOf<SentenceGroup>()
    var index = 0
    while (index < segments.size) {
        val sentence = segments[index].sentence
        var end = index
        if (sentence != null && sentence.index == 0 && sentence.cuts.isNotEmpty()) {
            while (end + 1 < segments.size &&
                segments[end + 1].sentence?.let { it.text == sentence.text && it.cuts == sentence.cuts } == true
            ) {
                end++
            }
        }
        val rows = (index..end).toList()
        val whole = sentence != null && rows.map { segments[it].sentence?.index } == (0..sentence.cuts.size).toList()
        if (whole && rows.size > 1) {
            groups += SentenceGroup(rows, sentence!!.text, sentence.cuts)
            index = end + 1
        } else {
            groups += SentenceGroup(listOf(index), segments[index].originalText, emptyList())
            index++
        }
    }
    return groups
}

/** Each group's translation cut into its rows; rows of a group without a translation stay out. */
internal fun spreadOverRows(
    groups: List<SentenceGroup>,
    translations: Map<Int, String>,
): Map<Int, String> {
    val rows = linkedMapOf<Int, String>()
    groups.forEachIndexed { groupIndex, group ->
        val translated = translations[groupIndex] ?: return@forEachIndexed
        val slices = translationSlices(translated, group.text.length, group.cuts)
        group.rows.forEachIndexed { position, row -> rows[row] = slices.getOrNull(position)?.takeIf(String::isNotBlank) ?: "…" }
    }
    return rows
}
