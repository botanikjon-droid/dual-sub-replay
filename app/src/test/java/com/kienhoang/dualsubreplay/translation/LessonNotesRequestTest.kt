package com.kienhoang.dualsubreplay.translation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LessonNotesRequestTest {
    private val notesJson =
        """{"summary": ["Bir"], "key_terms": [], "quiz": [{"q": "S?", "options": ["a","b","c","d"], "answer": 2, "explain": "chunki"}]}"""

    @Test
    fun promptPairsEnglishWithUzbekAndCutsLongLectures() {
        val paired = lessonNotesUserPrompt("Liver", listOf("Hello.", "Bye."), listOf("Salom.", "Xayr."))
        assertTrue(paired.contains("Lecture title: Liver"))
        assertTrue(paired.contains("Hello. ||| Salom."))
        val mismatched = lessonNotesUserPrompt(null, listOf("Hello.", "Bye."), listOf("Salom."))
        assertFalse(mismatched.contains("|||"))
        val long = lessonNotesUserPrompt(null, List(5_000) { "x".repeat(100) }, null)
        assertTrue(long.length <= NOTES_MAX_TRANSCRIPT_CHARS + 200)
        assertTrue(long.contains("cut"))
    }

    @Test
    fun geminiRequestUsesTheSchemaDialectAndReplyIsRead() {
        val body = JSONObject(geminiNotesRequestBody("p"))
        val schema = body.getJSONObject("generationConfig").getJSONObject("responseSchema")
        assertEquals("OBJECT", schema.getString("type"))
        val quizItem = schema.getJSONObject("properties").getJSONObject("quiz").getJSONObject("items")
        assertEquals("INTEGER", quizItem.getJSONObject("properties").getJSONObject("answer").getString("type"))
        val reply =
            JSONObject().put(
                "candidates",
                JSONArray().put(
                    JSONObject().put(
                        "content",
                        JSONObject().put(
                            "parts",
                            JSONArray()
                                .put(
                                    JSONObject().put("text", "o‘ylash").put("thought", true),
                                ).put(JSONObject().put("text", "```json\n$notesJson\n```")),
                        ),
                    ),
                ),
            )
        val notes = parseGeminiNotesReply(reply.toString())
        assertEquals(listOf("Bir"), notes.summary)
        assertEquals(2, notes.quiz[0].answer)
    }

    @Test
    fun claudeRequestForcesTheNotesToolAndReplyIsRead() {
        val body = JSONObject(claudeNotesRequestBody("claude-sonnet-5-5", "p"))
        assertEquals("submit_lesson_notes", body.getJSONObject("tool_choice").getString("name"))
        val reply =
            JSONObject()
                .put("stop_reason", "tool_use")
                .put(
                    "content",
                    JSONArray().put(
                        JSONObject().put("type", "tool_use").put("name", "submit_lesson_notes").put("input", JSONObject(notesJson)),
                    ),
                )
        assertEquals("S?", parseClaudeNotesReply(reply.toString()).quiz[0].question)
        val cut = runCatching { parseClaudeNotesReply(JSONObject().put("stop_reason", "max_tokens").toString()) }
        assertTrue(cut.exceptionOrNull() is ClaudeReplyException)
    }

    @Test
    fun promptCarriesLineTimesAndSchemaAsksForFrames() {
        val prompt = lessonNotesUserPrompt(null, listOf("As you can see, the wall is thick."), null, listOf(754))
        assertTrue(prompt.contains("[12:34] As you can see"))
        val frames = lessonNotesSchema().getJSONObject("properties").getJSONObject("key_frames")
        assertEquals(
            "integer",
            frames
                .getJSONObject("items")
                .getJSONObject("properties")
                .getJSONObject("t")
                .getString("type"),
        )
        assertTrue(lessonNotesSystemPrompt("x").contains("key_frames"))
    }
}
