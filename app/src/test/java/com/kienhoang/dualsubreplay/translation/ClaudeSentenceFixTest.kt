package com.kienhoang.dualsubreplay.translation

import com.kienhoang.dualsubreplay.data.ReviewSentence
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeSentenceFixTest {
    private val sentences =
        listOf(
            ReviewSentence(0, 2_000, "the liver is enlarged", "jigar kichraygan"),
            ReviewSentence(2_000, 4_000, "the spleen is normal", "taloq normal"),
        )

    private fun reply(vararg fixes: JSONObject): String =
        JSONObject()
            .put(
                "content",
                JSONArray().put(
                    JSONObject()
                        .put("type", "tool_use")
                        .put("name", SENTENCE_FIX_TOOL)
                        .put("input", JSONObject().put("fixes", JSONArray(fixes.toList()))),
                ),
            ).toString()

    private fun fix(
        n: Int,
        uzbek: String,
        changed: Boolean,
        reason: String = "",
    ) = JSONObject()
        .put("n", n)
        .put("uzbek", uzbek)
        .put("changed", changed)
        .put("reason", reason)

    @Test
    fun changedSentencesCarryTheNewTextAndReason() {
        val fixes = parseSentenceFixes(reply(fix(1, "jigar kattalashgan", true, "Ma’no teskari"), fix(2, "taloq normal", false)), sentences)
        assertTrue(fixes[0].changed)
        assertEquals("jigar kattalashgan", fixes[0].uzbek)
        assertEquals("Ma’no teskari", fixes[0].reason)
        assertFalse(fixes[1].changed)
    }

    @Test
    fun aSkippedOrIdenticalSentenceStaysUnchanged() {
        val fixes = parseSentenceFixes(reply(fix(2, "taloq normal", true, "x")), sentences)
        assertEquals(2, fixes.size)
        assertFalse(fixes[0].changed)
        assertEquals("jigar kichraygan", fixes[0].uzbek)
        assertFalse(fixes[1].changed)
    }

    @Test
    fun thePromptNumbersSentencesAndListsTerms() {
        val prompt = sentenceFixPrompt("Liver", sentences, emptyList(), "atamalarga e’tibor")
        assertTrue(prompt.contains("[1]\nEN: the liver is enlarged\nUZ: jigar kichraygan"))
        assertTrue(prompt.contains("[2]"))
        assertTrue(prompt.contains("atamalarga e’tibor"))
        val body = JSONObject(sentenceFixRequestBody("m", prompt))
        assertEquals(SENTENCE_FIX_TOOL, body.getJSONObject("tool_choice").getString("name"))
    }
}
