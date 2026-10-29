package com.kienhoang.dualsubreplay.translation

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class DeepSeekTranslationTest {
    private val chunk = ClaudeChunk(0, listOf("first sentence", "second sentence"))

    private fun answer(): String =
        JSONObject()
            .put(
                "translations",
                JSONArray()
                    .put(JSONObject().put("i", 0).put("uz", "birinchi gap"))
                    .put(JSONObject().put("i", 1).put("uz", "ikkinchi gap")),
            ).put("new_terms", JSONArray())
            .put("uncertain", JSONArray())
            .toString()

    private fun completion(
        content: String,
        finishReason: String = "stop",
    ): String =
        JSONObject()
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put("finish_reason", finishReason)
                        .put("message", JSONObject().put("role", "assistant").put("content", content)),
                ),
            ).toString()

    @Test
    fun requestAsksForJsonObjectWithTheSharedPrompt() {
        val body = JSONObject(deepSeekRequestBody("{}"))
        assertEquals(DEEPSEEK_MODEL, body.getString("model"))
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
        val system = body.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(system.contains("JSON schema"))
    }

    @Test
    fun replyIsReadAsLessonAnswer() {
        val result = parseDeepSeekReply(completion("```json\n" + answer() + "\n```"), chunk)
        assertEquals("birinchi gap", result.translations[0])
        assertEquals("ikkinchi gap", result.translations[1])
    }

    @Test
    fun cutReplyIsReportedSoTheChunkIsSplit() {
        try {
            parseDeepSeekReply(completion(answer(), finishReason = "length"), chunk)
            fail("a cut reply must not be accepted")
        } catch (expected: ClaudeReplyException) {
            assertTrue(expected.message.orEmpty().contains("kesilib"))
        }
    }

    @Test
    fun failingPrimaryIsAnsweredByTheFallback() =
        runBlocking {
            val primary = LessonBackend { _, _ -> throw IOException("DeepSeek HTTP 402") }
            val reasons = mutableListOf<String>()
            val backend =
                FallbackLessonBackend(
                    primary,
                    LessonBackend { _, _ -> parseDeepSeekReply(completion(answer()), chunk) },
                    onFallback = { reasons += it },
                )
            val result = backend.translate("{}", chunk)
            assertEquals("birinchi gap", result.translations[0])
            assertEquals(listOf("DeepSeek HTTP 402"), reasons)
        }

    @Test
    fun failureWithoutFallbackIsReported() =
        runBlocking {
            val primary = LessonBackend { _, _ -> throw IOException("DeepSeek HTTP 401") }
            try {
                FallbackLessonBackend(primary, fallback = null).translate("{}", chunk)
                fail("the failure must reach the caller")
            } catch (expected: IOException) {
                assertEquals("DeepSeek HTTP 401", expected.message)
            }
        }

    @Test
    fun transportSendsTheKeyAsBearerToken() =
        runBlocking {
            var seenKey = ""
            val backend =
                DeepSeekBackend("sk-test") { key, _ ->
                    seenKey = key
                    completion(answer())
                }
            backend.translate("{}", chunk)
            assertEquals("sk-test", seenKey)
        }
}
