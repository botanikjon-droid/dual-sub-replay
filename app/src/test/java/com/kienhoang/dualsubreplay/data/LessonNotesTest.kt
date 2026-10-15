package com.kienhoang.dualsubreplay.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LessonNotesTest {
    private val answer =
        """
        {"summary": ["Oddiy o‘t yo‘li 6 mm dan kam.", " "],
         "key_terms": [{"en": "common bile duct", "uz": "umumiy o‘t yo‘li"}, {"en": "", "uz": "x"}],
         "quiz": [
           {"q": "Normal o‘t yo‘li?", "options": ["<6 mm", "<10 mm", "<15 mm", "<20 mm"], "answer": 0, "explain": "Ma’ruzada 6 mm."},
           {"q": "Uch javob", "options": ["a", "b", "c"], "answer": 0, "explain": ""},
           {"q": "Javob chegaradan tashqari", "options": ["a", "b", "c", "d"], "answer": 4, "explain": ""},
           {"q": "Takroriy variant", "options": ["a", "a", "c", "d"], "answer": 1, "explain": ""}
         ]}
        """.trimIndent()

    @Test
    fun badQuestionsAndBlankItemsAreDropped() {
        val notes = lessonNotesFromJson(JSONObject(answer))
        assertEquals(1, notes.summary.size)
        assertEquals(listOf(NoteTerm("common bile duct", "umumiy o‘t yo‘li")), notes.terms)
        assertEquals(1, notes.quiz.size)
        assertEquals(0, notes.quiz[0].answer)
    }

    @Test
    fun storedFormRoundTrips() {
        val notes = lessonNotesFromJson(JSONObject(answer)).copy(engine = "Gemini", createdAtMs = 1_700_000_000_000L)
        assertEquals(notes, lessonNotesFromJson(JSONObject(notes.toJson().toString())))
    }

    @Test
    fun notesWithoutSummaryOrQuestionsAreRejected() {
        val noQuiz = runCatching { lessonNotesFromJson(JSONObject("""{"summary": ["a"], "quiz": []}""")) }
        assertTrue(noQuiz.exceptionOrNull() is LessonNotesException)
        val noSummary =
            runCatching {
                lessonNotesFromJson(JSONObject("""{"summary": [], "quiz": [{"q": "q", "options": ["a","b","c","d"], "answer": 1}]}"""))
            }
        assertTrue(noSummary.exceptionOrNull() is LessonNotesException)
    }

    @Test
    fun scoreCountsRightAnswers() {
        val quiz = listOf(QuizQuestion("q1", listOf("a", "b", "c", "d"), 1, ""), QuizQuestion("q2", listOf("a", "b", "c", "d"), 2, ""))
        assertEquals(1, quizScore(quiz, mapOf(0 to 1, 1 to 0)))
    }
}
