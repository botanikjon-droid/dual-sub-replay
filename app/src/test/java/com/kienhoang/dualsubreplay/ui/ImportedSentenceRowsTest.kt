package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.RawCaptionCue
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportedSentenceRowsTest {
    private fun cue(
        start: Long,
        end: Long,
        text: String,
    ) = RawCaptionCue(start, end, text)

    private fun row(
        start: Long,
        end: Long,
        text: String,
    ) = SubtitleSegment(0, start, end, text)

    @Test
    fun cuesJoinUntilTheUzbekSentenceEnds() {
        val spans =
            translatedSentenceSpans(
                listOf(
                    cue(0, 2_000, "Demak, akustik soyani tushunish oson: UTT to‘lqini"),
                    cue(2_000, 4_000, "bu yerda qaytadi va uning ostida soya hosil qiladi."),
                    cue(4_000, 5_000, "[Musiqa]"),
                    cue(5_000, 7_000, "Rahmat!"),
                ),
            )
        assertEquals(3, spans.size)
        assertEquals(
            "Demak, akustik soyani tushunish oson: UTT to‘lqini bu yerda qaytadi va uning ostida soya hosil qiladi.",
            spans[0].text,
        )
        assertEquals(0L to 4_000L, spans[0].startMs to spans[0].endMs)
        assertEquals("[Musiqa]", spans[1].text)
    }

    @Test
    fun aSentenceWithoutPunctuationStopsAtEightCues() {
        val cues = (0 until 10).map { cue(it * 1_000L, it * 1_000L + 1_000, "so‘z") }
        assertEquals(listOf(8, 2), translatedSentenceSpans(cues).map { it.text.split(" ").size })
    }

    @Test
    fun englishRowsFollowTheTranslatedSentences() {
        val spans = translatedSentenceSpans(listOf(cue(0, 2_000, "UTT to‘lqini"), cue(2_000, 4_000, "qaytadi.")))
        val rows =
            importedSentenceRows(
                listOf(row(0, 2_000, "the outer sound wave"), row(2_000, 4_000, "reflected here"), row(9_000, 10_000, "untranslated")),
                spans,
            )
        assertEquals(listOf("the outer sound wave reflected here", "untranslated"), rows.map { it.originalText })
        assertEquals(listOf(0L, 1L), rows.map { it.id })
        assertEquals(4_000L, rows[0].endMs)
    }
}
