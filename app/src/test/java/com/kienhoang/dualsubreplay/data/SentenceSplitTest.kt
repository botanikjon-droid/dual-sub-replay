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

    @Test
    fun eachRowGetsOnlyItsOwnSentenceAndACorrectionIsNotSpokenTwice() {
        val cues =
            listOf(
                RawCaptionCue(0, 3_000, "Xayrli tong, hammaga. Mana, tushundingiz,"),
                RawCaptionCue(3_000, 6_000, "tushundingiz. Umid qilamanki, yoqdi."),
            )
        val english = (0 until 6).map { SubtitleSegment(it.toLong(), it * 1_000L, it * 1_000L + 1_000, "w$it") }

        fun rows(stored: List<RawCaptionCue>) =
            applyImportedTranslation(
                com.kienhoang.dualsubreplay.ui.importedSentenceRows(
                    english,
                    com.kienhoang.dualsubreplay.ui
                        .translatedSentenceSpans(stored),
                ),
                stored,
            )
        val before = rows(cues)
        assertEquals(
            listOf("Xayrli tong, hammaga.", "Mana, tushundingiz, tushundingiz.", "Umid qilamanki, yoqdi."),
            before.map {
                it.translatedText
            },
        )
        val fixed = applySentenceCorrection(cues, before[1].startMs, before[1].endMs, "Mana, bo‘ldi.")
        assertEquals(listOf("Xayrli tong, hammaga.", "Mana, bo‘ldi.", "Umid qilamanki, yoqdi."), rows(fixed).map { it.translatedText })
        val deleted = applySentenceCorrection(fixed, before[1].startMs, before[1].endMs, "")
        assertEquals(listOf("Xayrli tong, hammaga.", "Umid qilamanki, yoqdi."), deleted.map { it.text })
    }
}
