package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.SentenceSlice
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LessonSentenceGroupsTest {
    private val whole = "And this curvi-linear structure is the portal splenic confluence where the splenic vein joins the portal vein"
    private val cut = whole.indexOf("joins")
    private val rows =
        listOf(
            SubtitleSegment(1, 0, 1_000, "Here is the left lobe."),
            SubtitleSegment(2, 1_000, 2_000, whole.substring(0, cut).trim(), sentence = SentenceSlice(whole, listOf(cut), 0)),
            SubtitleSegment(3, 2_000, 3_000, whole.substring(cut), sentence = SentenceSlice(whole, listOf(cut), 1)),
            SubtitleSegment(4, 3_000, 4_000, "And these are the aorta and the IVC."),
        )

    @Test
    fun aSplitSentenceIsTranslatedWhole() {
        val groups = sentenceGroups(rows)
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3)), groups.map { it.rows })
        assertEquals(whole, groups[1].text)
    }

    @Test
    fun theTranslationIsCutBackIntoTheRows() {
        val groups = sentenceGroups(rows)
        val uzbek = "Bu egri chiziqli tuzilma portal-taloq qo‘shilish joyi, bu yerda taloq venasi darvoza venasiga qo‘shiladi."
        val spread = spreadOverRows(groups, mapOf(1 to uzbek))
        assertEquals(setOf(1, 2), spread.keys)
        assertEquals(uzbek, "${spread[1]} ${spread[2]}")
        assertTrue(spread.values.none(String::isBlank))
    }

    @Test
    fun anIncompleteSentenceStaysRowByRow() {
        val groups = sentenceGroups(rows.drop(2))
        assertEquals(listOf(listOf(0), listOf(1)), groups.map { it.rows })
    }
}
