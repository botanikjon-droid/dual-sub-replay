package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SectionStudyTest {
    private fun question(text: String) = QuizQuestion(text, listOf("$text-a", "$text-b", "$text-c", "$text-d"), 2, "")

    private fun lesson(
        id: String,
        count: Int,
    ) = PrintableLesson(id, "Dars $id", LessonNotes(listOf("x"), emptyList(), (1..count).map { question("$id$it") }))

    @Test
    fun everyLessonIsAskedBeforeAnyIsAskedTwice() {
        val quiz = sectionQuiz(listOf(lesson("a", 10), lesson("b", 10), lesson("c", 1)), size = 3, random = Random(1))
        assertEquals(setOf("a", "b", "c"), quiz.map { it.videoId }.toSet())
    }

    @Test
    fun theTestStopsAtItsSizeOrWhenQuestionsRunOut() {
        assertEquals(20, sectionQuiz(listOf(lesson("a", 15), lesson("b", 15)), random = Random(2)).size)
        assertEquals(7, sectionQuiz(listOf(lesson("a", 4), lesson("b", 3), lesson("c", 0)), random = Random(3)).size)
    }

    @Test
    fun shuffledAnswersKeepTheRightOne() {
        repeat(20) { seed ->
            val original = question("q")
            val shuffled = shuffledOptions(original, Random(seed))
            assertEquals(original.options[original.answer], shuffled.options[shuffled.answer])
            assertEquals(original.options.toSet(), shuffled.options.toSet())
        }
    }

    @Test
    fun verdictNamesTheScore() {
        assertEquals("18 / 20 (90 %) — a’lo", sectionTestVerdict(18, 20))
        assertTrue(sectionTestVerdict(5, 20).startsWith("5 / 20 (25 %)"))
        assertEquals("", sectionTestVerdict(0, 0))
    }
}
