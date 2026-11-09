package com.kienhoang.dualsubreplay.translation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShokalonChatTest {
    @Test
    fun textAndToolCallsAreRead() {
        val body =
            JSONObject()
                .put("stop_reason", "tool_use")
                .put(
                    "content",
                    JSONArray()
                        .put(JSONObject().put("type", "text").put("text", " Tekshiraman. "))
                        .put(
                            JSONObject()
                                .put("type", "tool_use")
                                .put("id", "t1")
                                .put("name", ChatTools.LESSON_SENTENCES)
                                .put("input", JSONObject().put("from", 1)),
                        ),
                ).toString()
        val reply = parseChatReply(body)
        assertTrue(reply.wantsTools)
        assertEquals(ChatBlock.Text("Tekshiraman."), reply.blocks[0])
        val call = reply.blocks[1] as ChatBlock.ToolUse
        assertEquals("t1", call.id)
        assertEquals(1, call.input.getInt("from"))
    }

    @Test
    fun theRequestCarriesEveryTool() {
        val body = JSONObject(chatRequestBody("m", "s", JSONArray().put(userMessage("salom"))))
        assertEquals(SHOKALON_CHAT_TOOLS.size, body.getJSONArray("tools").length())
        assertEquals("salom", body.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test
    fun historyStartsWithAPlainUserMessage() {
        val messages =
            (1..40).map { index ->
                if (index % 4 == 1) userMessage("q$index") else toolResultsMessage(listOf("id$index" to "r"))
            }
        val kept = trimmedHistory(messages, keep = 10)
        assertTrue(kept.size <= 10)
        assertTrue(kept.first().opt("content") is String)
    }

    @Test
    fun longToolResultsAreCut() {
        assertTrue(boundedToolResult("x".repeat(MAX_TOOL_RESULT_CHARS + 50)).endsWith("range]"))
        assertEquals("ok", boundedToolResult("ok"))
    }
}
