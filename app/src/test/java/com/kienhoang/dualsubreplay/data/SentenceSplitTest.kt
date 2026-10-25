package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceSplitTest {
    @Test
    fun aCueIsCutAtTheSentenceEndInside() {
        val cue = RawCaptionCue(0, 10_000, "Bu mitral regurgitatsiya haqidagi ma’ruza. Ilgari shunday deyishardi:")
        val pieces = splitAtSentenceEnds(cue)
        assertEquals(listOf("Bu mitral regurgitatsiya haqidagi ma’ruza.", "Ilgari shunday deyishardi:"), pieces.map { it.text })
        assertEquals(0L, pieces.first().startMs)
        assertEquals(pieces[0].endMs, pieces[1].startMs)
        assertEquals(10_000L, pieces.last().endMs)
    }

    @Test
    fun shortPiecesAndAbbreviationsStayWhole() {
        val short = RawCaptionCue(0, 2_000, "Ha. Bu jigarning o‘ng bo‘lagi.")
        assertEquals(listOf(short), splitAtSentenceEnds(short))
        val lower = RawCaptionCue(0, 2_000, "o‘lchami 5 sm. va undan katta bo‘lsa")
        assertEquals(listOf(lower), splitAtSentenceEnds(lower))
    }

    @Test
    fun sentenceRowsCloseAtTheRealSentenceEnd() {
        val cues =
            listOf(
                RawCaptionCue(0, 4_000, "Xayrli tong, hammaga. Sizlarni yana ko‘rganimdan"),
                RawCaptionCue(4_000, 8_000, "xursandman. Bu birinchi ma’ruza va"),
                RawCaptionCue(8_000, 12_000, "u mitral regurgitatsiya haqida."),
            )
        val texts =
            com.kienhoang.dualsubreplay.ui
                .translatedSentenceSpans(splitCuesAtSentenceEnds(cues))
                .map { it.text }
        assertEquals(
            listOf(
                "Xayrli tong, hammaga.",
                "Sizlarni yana ko‘rganimdan xursandman.",
                "Bu birinchi ma’ruza va u mitral regurgitatsiya haqida.",
            ),
            texts,
        )
    }
}
