package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceReviewTest {
    @Test
    fun aCheckedFileComesBackWithItsCorrections() {
        val file =
            reviewFileText(
                "Gallbladder",
                "x6hXhLNc-jU",
                listOf(
                    ReviewSentence(271_200, 279_800, "so reflection is caused", "Sinish shuning uchun yuz beradi."),
                    ReviewSentence(3_723_004, 3_725_000, "thank you", "Rahmat."),
                ),
            )
        val edited = file.replace("UZ: Sinish shuning uchun yuz beradi.", "UZ: Sinish UTT to‘lqini\nturli muhitlardan o‘tganda yuz beradi.")
        val back = parseReviewFile(edited)
        assertEquals("x6hXhLNc-jU", reviewFileVideoId(edited))
        assertEquals(listOf(271_200L, 3_723_004L), back.map { it.startMs })
        assertEquals(279_800L, back[0].endMs)
        assertEquals("Sinish UTT to‘lqini turli muhitlardan o‘tganda yuz beradi.", back[0].uzbek)
        assertEquals("Rahmat.", back[1].uzbek)
    }

    @Test
    fun aCorrectionReplacesOnlyItsSentence() {
        val cues =
            listOf(
                RawCaptionCue(0, 2_000, "Oldingi gap."),
                RawCaptionCue(2_000, 4_000, "UTT to‘lqini"),
                RawCaptionCue(4_000, 6_000, "sinadi."),
                RawCaptionCue(5_900, 8_000, "Keyingi gap."),
            )
        val result = applySentenceCorrection(cues, 2_000, 6_000, "UTT to‘lqini sinadi va qaytadi.")
        assertEquals(listOf("Oldingi gap.", "UTT to‘lqini sinadi va qaytadi.", "Keyingi gap."), result.map { it.text })
    }
}
