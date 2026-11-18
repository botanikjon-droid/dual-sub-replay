package com.kienhoang.dualsubreplay.translation

import com.kienhoang.dualsubreplay.data.ApiUsageEntry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ClaudePromptCachingTest {
    private fun cachedBlock(body: String): JSONObject {
        val system = JSONObject(body).getJSONArray("system")
        assertEquals(1, system.length())
        return system.getJSONObject(0)
    }

    @Test
    fun lessonRequestMarksTheFixedSystemPromptForCaching() {
        val body = claudeRequestBody("claude-sonnet-5-5", "{\"items\":[]}")
        val block = cachedBlock(body)
        assertEquals("text", block.getString("type"))
        assertEquals("ephemeral", block.getJSONObject("cache_control").getString("type"))
        assertEquals(claudeSystemPrompt(), block.getString("text"))
        // The lesson's sentences stay in the user message, outside the cached prefix.
        val message = JSONObject(body).getJSONArray("messages").getJSONObject(0)
        assertEquals("{\"items\":[]}", message.getString("content"))
    }

    @Test
    fun sentenceFixRequestIsCachedTooAndKeepsItsRules() {
        val block = cachedBlock(sentenceFixRequestBody("m", "[1] EN: x"))
        assertEquals("ephemeral", block.getJSONObject("cache_control").getString("type"))
        assertTrue(block.getString("text").contains("20 000"))
    }

    @Test
    fun usageWithCacheIsRead() {
        val body =
            JSONObject()
                .put(
                    "usage",
                    JSONObject()
                        .put("input_tokens", 120)
                        .put("output_tokens", 900)
                        .put("cache_creation_input_tokens", 3000)
                        .put("cache_read_input_tokens", 0),
                ).toString()
        assertEquals(TokenUsage(120, 900, 0, 3000, reported = true), claudeUsage(body))
        assertFalse(claudeUsage("{}").reported)
        assertFalse(claudeUsage("not json").reported)
    }

    private fun toolReply(usage: JSONObject): String {
        val input =
            JSONObject()
                .put("translations", JSONArray().put(JSONObject().put("i", 0).put("uz", "Jigar")))
                .put("new_terms", JSONArray())
                .put("uncertain", JSONArray())
        return JSONObject()
            .put("stop_reason", "tool_use")
            .put("content", JSONArray().put(JSONObject().put("type", "tool_use").put("name", CLAUDE_TOOL_NAME).put("input", input)))
            .put("usage", usage)
            .toString()
    }

    @Test
    fun lessonCallIsLoggedWithCacheReadTokens() {
        val log = mutableListOf<ApiUsageEntry>()
        val reply = toolReply(JSONObject().put("input_tokens", 80).put("output_tokens", 40).put("cache_read_input_tokens", 2900))
        val backend =
            ClaudeBackend("sk-ant-secret", "claude-sonnet-5-5", { _, _ -> reply }, emptyList(), onCall = { log += it }, now = { 7L })
        val result = runBlocking { backend.translate("{}", ClaudeChunk(0, listOf("Liver"))) }
        assertEquals("Claude", result.engine)
        val call = log.single()
        assertEquals("200", call.status)
        assertEquals("0-0", call.chunkId)
        assertEquals(2900L, call.cacheReadTokens)
        assertEquals(80L, call.inputTokens)
        assertEquals("claude-sonnet-5-5", call.model)
    }

    @Test
    fun failedCallIsLoggedWithItsStatusAndNoTokens() {
        val log = mutableListOf<ApiUsageEntry>()
        val backend =
            ClaudeBackend(
                "k",
                "m",
                { _, _ -> throw ClaudeHttpException(401, "API kaliti noto‘g‘ri", null) },
                listOf(1L),
                onCall = { log += it },
            )
        try {
            runBlocking { backend.request("{}") }
            fail("a wrong key must fail")
        } catch (expected: ClaudeHttpException) {
            assertEquals(401, expected.code)
        }
        assertEquals("401", log.single().status)
        assertFalse(log.single().tokensReported)
    }
}
