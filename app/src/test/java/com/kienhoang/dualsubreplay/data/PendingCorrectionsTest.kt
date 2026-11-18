package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PendingCorrectionsTest {
    @Test
    fun pendingCorrectionsRoundTripAndApplyToTheFinishedTranslation() {
        val pending = listOf(ReviewSentence(2_000, 4_000, "", "Bu anexogen."))
        val read = pendingCorrectionsFromJson(pendingCorrectionsToJson(pending))
        assertEquals(pending, read)
        assertEquals(emptyList<ReviewSentence>(), pendingCorrectionsFromJson("not json"))
        val finished = listOf(RawCaptionCue(0, 2_000, "Kist ko‘rinadi."), RawCaptionCue(2_000, 4_000, "Bu anehogen."))
        val fixed = read.fold(finished) { cues, fix -> applySentenceCorrection(cues, fix.startMs, fix.endMs, fix.uzbek) }
        assertEquals(listOf("Kist ko‘rinadi.", "Bu anexogen."), fixed.map { it.text })
    }
}
