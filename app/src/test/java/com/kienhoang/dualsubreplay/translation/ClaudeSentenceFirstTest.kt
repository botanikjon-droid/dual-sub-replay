package com.kienhoang.dualsubreplay.translation

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeSentenceFirstTest {
    @Test
    fun claudeWritesWholeSentencesBeforeTheItems() {
        val schema = claudeToolSchema()
        val keys =
            schema
                .getJSONObject("properties")
                .keys()
                .asSequence()
                .toList()
        assertTrue(keys.indexOf("sentences") in 0 until keys.indexOf("translations"))
        assertTrue(schema.getJSONArray("required").toString().contains("sentences"))
        val body = JSONObject(claudeRequestBody("m", "{}"))
        assertTrue(body.getString("system").contains("\"sentences\": every whole sentence"))
    }

    @Test
    fun geminiKeepsItsShorterAnswer() {
        assertFalse(claudeToolSchema(sentences = false).getJSONObject("properties").has("sentences"))
    }

    @Test
    fun everyPromptCarriesTheDoctorsRulesWithoutStrayIndent() {
        val lesson = claudeSystemPrompt()
        assertTrue(lesson.startsWith("You are"))
        assertTrue(lesson.contains("So‘zma-so‘z emas, asl ma’noga sodiq tarjima qil."))
        assertTrue(
            lesson.trimEnd().endsWith(
                "translating the items again one by one. The items' Uzbek joined in order must read as those sentences.",
            ),
        )
        assertFalse(lesson.contains("{{"))
        assertTrue(SENTENCE_FIX_SYSTEM.contains("Sabab-natija, inkor, shart"))
        val chat = shokalonChatSystem("Open lesson: X")
        assertTrue(chat.contains("Open lesson: X") && chat.contains("ehtimollik darajasini"))
        assertFalse(chat.lines().any { it.startsWith("    You") })
    }
}
