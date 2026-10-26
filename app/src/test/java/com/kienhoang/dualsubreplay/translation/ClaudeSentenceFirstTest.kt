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
}
