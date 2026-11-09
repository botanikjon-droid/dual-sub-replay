package com.kienhoang.dualsubreplay.translation

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GeminiLessonTranslationTest {
    private val lesson = (0 until 6).map { "sentence $it" }

    private fun answer(indices: List<Int>): JSONObject =
        JSONObject()
            .put("translations", JSONArray().apply { indices.forEach { put(JSONObject().put("i", it).put("uz", "gap $it")) } })
            .put("new_terms", JSONArray().put(JSONObject().put("en", "splenic window").put("uz", "taloq oynasi")))
            .put("uncertain", JSONArray())

    private fun reply(
        indices: List<Int>,
        finishReason: String = "STOP",
    ): String {
        val parts =
            JSONArray()
                .put(JSONObject().put("text", "thinking...").put("thought", true))
                .put(JSONObject().put("text", "```json\n" + answer(indices) + "\n```"))
        return JSONObject()
            .put(
                "candidates",
                JSONArray().put(JSONObject().put("finishReason", finishReason).put("content", JSONObject().put("parts", parts))),
            ).toString()
    }

    private fun itemsOf(body: String): List<Int> {
        val text =
            JSONObject(body)
                .getJSONArray("contents")
                .getJSONObject(0)
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
        val items = JSONObject(text).getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it).getInt("i") }
    }

    @Test
    fun requestUsesGeminiSchemaAndTheSharedPrompt() {
        val body = JSONObject(geminiLessonRequestBody("{}"))
        val config = body.getJSONObject("generationConfig")
        val schema = config.getJSONObject("responseSchema")
        assertEquals("OBJECT", schema.getString("type"))
        assertEquals(
            "INTEGER",
            schema
                .getJSONObject("properties")
                .getJSONObject("translations")
                .getJSONObject("items")
                .getJSONObject("properties")
                .getJSONObject("i")
                .getString("type"),
        )
        assertEquals(GEMINI_LESSON_MAX_OUTPUT_TOKENS, config.getInt("maxOutputTokens"))
        val system =
            body
                .getJSONObject("systemInstruction")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
        assertTrue(system.contains("dangling"))
        assertTrue(system.contains("response schema"))
    }

    @Test
    fun replyIsReadSkippingThoughtsAndFences() {
        val chunk = ClaudeChunk(0, lesson.take(2))
        val result = parseGeminiLessonReply(reply(listOf(0, 1)), chunk)
        assertEquals(mapOf(0 to "gap 0", 1 to "gap 1"), result.translations)
        assertEquals("splenic window", result.newTerms.single().english)
        try {
            parseGeminiLessonReply(reply(listOf(0, 1), finishReason = "MAX_TOKENS"), chunk)
            fail("a cut reply must be split")
        } catch (expected: ClaudeReplyException) {
            // expected
        }
    }

    @Test
    fun wholeLessonIsOneRequestAndAUsedUpModelHandsOverToTheNext() =
        runBlocking {
            val asked = mutableListOf<Pair<String, List<Int>>>()
            val transport =
                GeminiTransport { _, model, body ->
                    asked += model to itemsOf(body)
                    if (model == GEMINI_MODELS[0]) {
                        throw GeminiLessonHttpException(429, "HTTP 429 - quota. Please retry in 2h.")
                    }
                    reply(itemsOf(body))
                }
            val quota = GeminiModelQuota()
            var used = ""
            val result =
                LessonTranslator(GeminiBackend("key", quota, transport, now = { 0L }, onModel = { used = it }), GEMINI_LESSON_CHUNK_SIZE)
                    .translate(lesson, { emptyList() }, { emptyList() }) { _, _ -> }
            assertEquals(lesson.indices.associateWith { "gap $it" }, result.translations)
            // The whole lesson went in one request per model tried.
            assertEquals(listOf(GEMINI_MODELS[0] to lesson.indices.toList(), GEMINI_MODELS[1] to lesson.indices.toList()), asked)
            assertEquals(GEMINI_MODELS[1], used)
            // The used-up model rests for the time Gemini named.
            assertEquals(GEMINI_MODELS.drop(1), quota.available(0L))
        }

    @Test
    fun everyModelUsedUpIsReported() =
        runBlocking {
            val transport = GeminiTransport { _, _, _ -> throw GeminiLessonHttpException(429, "HTTP 429") }
            try {
                LessonTranslator(GeminiBackend("key", GeminiModelQuota(), transport, now = { 0L }), GEMINI_LESSON_CHUNK_SIZE)
                    .translate(lesson, { emptyList() }, { emptyList() }) { _, _ -> }
                fail("expected the quota error")
            } catch (expected: GeminiQuotaExhausted) {
                assertEquals(GEMINI_DEFAULT_REST_MS, expected.untilMs)
            }
        }

    @Test
    fun aWrongKeyStopsAtOnce() =
        runBlocking {
            var calls = 0
            val transport =
                GeminiTransport { _, _, _ ->
                    calls++
                    throw GeminiLessonHttpException(400, "HTTP 400 - API key not valid")
                }
            try {
                LessonTranslator(GeminiBackend("key", GeminiModelQuota(), transport, now = { 0L }), GEMINI_LESSON_CHUNK_SIZE)
                    .translate(lesson, { emptyList() }, { emptyList() }) { _, _ -> }
                fail("expected the key error")
            } catch (expected: GeminiLessonHttpException) {
                assertEquals(1, calls)
            }
        }
}
