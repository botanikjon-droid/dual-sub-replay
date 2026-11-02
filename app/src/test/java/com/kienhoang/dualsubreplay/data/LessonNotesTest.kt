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

    @Test
    fun framesAreOrderedDedupedAndKeptWithTheirPictures() {
        val json =
            JSONObject(answer).put(
                "key_frames",
                org.json
                    .JSONArray()
                    .put(JSONObject().put("t", 120).put("caption", "Jigar"))
                    .put(JSONObject().put("t", 30).put("caption", "O‘t pufagi"))
                    .put(JSONObject().put("t", 32).put("caption", "Yaqin").put("image", "32.jpg"))
                    .put(JSONObject().put("t", 40).put("caption", " "))
                    .put(JSONObject().put("t", 50).put("caption", "Yomon fayl").put("image", "../x.jpg")),
            )
        val notes = lessonNotesFromJson(json)
        assertEquals(listOf(32, 50, 120), notes.frames.map { it.seconds })
        assertEquals("32.jpg", notes.frames[0].image)
        assertEquals("", notes.frames[1].image)
        val taken = notes.withFrameImage(120, "120.jpg")
        assertEquals(taken, lessonNotesFromJson(JSONObject(taken.toJson().toString())))
    }

    @Test
    fun theDoctorsPicturesNeedNoWrittenNotes() {
        val own = addManualFrame(null, NoteFrame(75, "Kadr", "75.jpg"))
        val stored = lessonNotesFromJson(JSONObject(own.toJson().toString()), requireStudy = false)
        assertEquals(listOf(NoteFrame(75, "Kadr", "75.jpg")), stored.frames)
        assertTrue(runCatching { lessonNotesFromJson(JSONObject(own.toJson().toString())) }.isFailure)
        val replaced = addManualFrame(stored, NoteFrame(77, "Yangi", "77.jpg"))
        assertEquals(listOf(77), replaced.frames.map { it.seconds })
        assertEquals("1:02:03", formatVideoTime(3_723))
        assertEquals("4:05", formatVideoTime(245))
    }

    @Test
    fun plannedFramesGiveWayToTakenOnesAtTheLimit() {
        val taken = (0 until MAX_NOTE_FRAMES).map { NoteFrame(it * 10, "t", "${it * 10}.jpg") }
        val planned = listOf(NoteFrame(1_000, "p"))
        assertEquals(taken, distinctFrames(taken + planned))
    }
}
