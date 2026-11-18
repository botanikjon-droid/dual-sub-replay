package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationChecksTest {
    private fun kinds(
        english: String,
        uzbek: String,
    ) = checkTranslation(english, uzbek).map { it.kind to it.detail }

    @Test
    fun keptNumbersUnitsAndNegationsRaiseNothing() {
        assertEquals(emptyList<Any>(), kinds("The wall is 3 mm thick", "Devor qalinligi 3 mm"))
        assertEquals(emptyList<Any>(), kinds("The CBD measures 6 millimeters", "Umumiy o‘t yo‘li olti millimetr"))
        assertEquals(emptyList<Any>(), kinds("There is no free fluid", "Erkin suyuqlik yo‘q"))
        assertEquals(emptyList<Any>(), kinds("It is not dilated", "U kengaymagan"))
        assertEquals(emptyList<Any>(), kinds("Velocity is 2.5 cm/s", "Tezlik 2,5 sm/s"))
        assertEquals(emptyList<Any>(), kinds("This is not normal", "Bu normal emas"))
    }

    @Test
    fun changedOrMissingNumbersAreFlagged() {
        assertEquals(listOf(TranslationIssueKind.NUMBER to "12"), kinds("The diameter is 12 mm", "Diametri 21 mm"))
        assertEquals(listOf(TranslationIssueKind.NUMBER to "3"), kinds("Wait 3 seconds", "Bir necha soniya kuting"))
    }

    @Test
    fun missingUnitsAndNegationsAreFlagged() {
        assertEquals(listOf(TranslationIssueKind.UNIT to "mm"), kinds("about 7 mm", "taxminan 7 sm"))
        assertEquals(listOf(TranslationIssueKind.NEGATION to "no"), kinds("There is no thrombus", "Tromb bor"))
        assertEquals(listOf(TranslationIssueKind.NEGATION to "n't"), kinds("It doesn't compress", "U bosiladi"))
    }

    @Test
    fun splitRowsAreNotJudged() {
        val row = SubtitleSegment(1, 0, 1000, "There is no", "Tromb", sentence = SentenceSlice("There is no thrombus", listOf(12), 0))
        assertEquals(emptyList<TranslationIssue>(), translationChecksForRow(row))
        assertEquals(1, translationChecksForRow(SubtitleSegment(1, 0, 1000, "There is no thrombus", "Tromb bor")).size)
    }
}
