package com.kienhoang.dualsubreplay.translation

import com.kienhoang.dualsubreplay.data.UZI_GLOSSARY_ASSET
import com.kienhoang.dualsubreplay.data.UziGlossary
import com.kienhoang.dualsubreplay.data.parseUziGlossary
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

class GeminiTranslationTest {
    private val glossary = UziGlossary(parseUziGlossary(File("src/main/assets/$UZI_GLOSSARY_ASSET").readText()).entries)
    private val video = (1..40).map { "sentence $it" }

    @Test
    fun aBatchStartsAtTheSentenceWithEarlierOnesAsContext() {
        val batch = sentenceBatch(video, "sentence 10", size = 5, before = 3)
        assertEquals(listOf("sentence 7", "sentence 8", "sentence 9"), batch.context)
        assertEquals(listOf("sentence 10", "sentence 11", "sentence 12", "sentence 13", "sentence 14"), batch.sentences)
    }

    @Test
    fun aBatchSkipsTranslatedRepeatedAndBlankSentences() {
        val all = listOf("a", "b", "", "b", "c", "d", "e")
        val batch = sentenceBatch(all, "a", size = 3, before = 2) { it == "c" }
        assertEquals(emptyList<String>(), batch.context)
        assertEquals(listOf("a", "b", "d"), batch.sentences)
        // A text that is not one of the video's sentences (a tapped word) goes alone.
        assertEquals(SentenceBatch(emptyList(), listOf("transducer")), sentenceBatch(all, "transducer"))
        assertEquals(listOf("sentence 40"), sentenceBatch(video, "sentence 40", size = 25).sentences)
    }

    @Test
    fun sentencesAfterLooksAhead() {
        assertEquals(listOf("sentence 39", "sentence 40"), sentencesAfter(video, "sentence 38", count = 8))
        assertEquals(emptyList<String>(), sentencesAfter(video, "unknown"))
    }

    @Test
    fun theBatchGlossaryHoldsEachApprovedTermOnce() {
        val terms =
            glossaryForBatch(
                glossary,
                listOf("using the transducer on the liver", "the same transducer and the portal vein"),
            ).map { it.english }
        // "liver" is a common word, so it is left out; "transducer" is listed once.
        assertEquals(listOf("transducer", "portal vein"), terms)
        assertEquals(emptyList<String>(), glossaryForBatch(null, listOf("transducer")).map { it.english })
    }

    @Test
    fun theRequestCarriesGlossaryContextSentencesAndAJsonSchema() {
        val batch = SentenceBatch(listOf("Earlier words"), listOf("Some liver shots with the transducer", "Next one"))
        val body = JSONObject(geminiRequestBody(batch, glossaryForBatch(glossary, batch.sentences)))
        val prompt =
            JSONObject(
                body
                    .getJSONArray("contents")
                    .getJSONObject(0)
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text"),
            )
        assertEquals(listOf("Some liver shots with the transducer", "Next one"), strings(prompt.getJSONArray("sentences")))
        assertEquals(listOf("Earlier words"), strings(prompt.getJSONArray("context")))
        val term = prompt.getJSONArray("glossary").getJSONObject(0)
        assertEquals("transducer", term.getString("en"))
        // Only the Uzbek words before the parentheses: "datchik (zond)" -> "datchik".
        assertEquals("datchik", term.getString("uz"))
        val config = body.getJSONObject("generationConfig")
        assertEquals("application/json", config.getString("responseMimeType"))
        assertEquals("ARRAY", config.getJSONObject("responseSchema").getString("type"))
        // Little thinking by default (it is most of the wait); left out when the model rejects it.
        assertEquals("low", config.getJSONObject("thinkingConfig").getString("thinkingLevel"))
        val plain = JSONObject(geminiRequestBody(batch, emptyList(), lowThinking = false)).getJSONObject("generationConfig")
        assertFalse(plain.has("thinkingConfig"))
        assertTrue(
            body
                .getJSONObject("systemInstruction")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .contains("exactly one Uzbek translation per item"),
        )
    }

    @Test
    fun theReplyGivesOneTranslationPerSentence() {
        val reply = reply("[\"Bugun bizda yaxshi bemor bor.\", \"Datchikni qo‘llaymiz.\"]")
        assertEquals(listOf("Bugun bizda yaxshi bemor bor.", "Datchikni qo‘llaymiz."), parseGeminiTranslations(reply, 2))
        // A reply wrapped in a code fence is read too.
        assertEquals(listOf("Bir"), parseGeminiTranslations(reply("```json\n[\"Bir\"]\n```"), 1))
    }

    @Test
    fun aBadReplyIsAnIOExceptionSoTheAppFallsBack() {
        assertFails("2 translations for 3") { parseGeminiTranslations(reply("[\"a\", \"b\"]"), 3) }
        assertFails("untranslated") { parseGeminiTranslations(reply("[\"a\", \" \"]"), 2) }
        assertFails("unreadable") { parseGeminiTranslations(reply("not json"), 1) }
        assertFails("API key not valid") {
            parseGeminiTranslations("{\"error\":{\"code\":400,\"message\":\"API key not valid.\"}}", 1)
        }
        assertFails("refused") { parseGeminiTranslations("{\"promptFeedback\":{\"blockReason\":\"OTHER\"}}", 1) }
        assertFails("no translation") { parseGeminiTranslations("{\"candidates\":[]}", 1) }
    }

    @Test
    fun onlyARejectedThinkingSettingIsRetriedWithoutIt() {
        assertTrue(isThinkingConfigRejected(400, "HTTP 400 - Invalid JSON payload: unknown name \"thinkingLevel\""))
        assertFalse(isThinkingConfigRejected(400, "HTTP 400 - API key not valid."))
        assertFalse(isThinkingConfigRejected(429, "HTTP 429 - thinking quota"))
    }

    @Test
    fun theWaitingRequestIsSmallerThanTheBackgroundOne() {
        assertTrue(GEMINI_FIRST_BATCH_SIZE < GEMINI_BATCH_SIZE)
        assertEquals(GEMINI_FIRST_BATCH_SIZE, sentenceBatch(video, "sentence 1", size = GEMINI_FIRST_BATCH_SIZE).sentences.size)
        assertEquals("gemini-flash-latest", GEMINI_MODELS.first())
    }

    @Test
    fun theRetryTimeOfA429IsRead() {
        val doctorsReply =
            "HTTP 429 - You exceeded your current quota. * Quota exceeded for metric: " +
                "generate_content_free_tier_requests, limit: 20, model: gemini-3.8-flash Please retry in 5h3m52.552520235s."
        assertEquals(((5 * 60 + 3) * 60 + 52.552520235).times(1000).toLong(), parseRetryDelayMs(doctorsReply))
        assertEquals(18_000L, parseRetryDelayMs("{\"retryDelay\": \"18s\"}"))
        assertEquals(90_000L, parseRetryDelayMs("Please retry in 1m30s"))
        assertEquals(null, parseRetryDelayMs("HTTP 429 - Resource exhausted"))
        assertEquals(null, parseRetryDelayMs(null))
    }

    @Test
    fun aUsedUpModelRestsAndTheNextOneIsTried() {
        val quota = GeminiModelQuota(listOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c"), quota.available(nowMs = 0))
        quota.rest("a", untilMs = 1_000)
        quota.rest("c", untilMs = 500)
        assertEquals(listOf("b"), quota.available(nowMs = 100))
        assertEquals(500L, quota.nextRetryMs())
        // After its rest a model is asked again.
        assertEquals(listOf("b", "c"), quota.available(nowMs = 600))
        assertEquals(listOf("a", "b", "c"), quota.available(nowMs = 1_000))
        // Each model of the free tier has its own quota, so there is more than one to fall back to.
        assertTrue(GEMINI_MODELS.size > 1 && GEMINI_MODELS.toSet().size == GEMINI_MODELS.size)
    }

    @Test
    fun geminiIsUsedOnlyWhenChosenWithAKeyForEnglishToUzbek() {
        assertTrue(geminiEnabled("gemini", "key", "en", "uz", pausedUntilMs = 0, nowMs = 10))
        assertFalse(geminiEnabled("google", "key", "en", "uz", 0, 10))
        assertFalse(geminiEnabled("gemini", " ", "en", "uz", 0, 10))
        assertFalse(geminiEnabled("gemini", "key", "en", "vi", 0, 10))
        // After a failure Gemini rests, and Google Translate is used meanwhile.
        assertFalse(geminiEnabled("gemini", "key", "en", "uz", pausedUntilMs = 100, nowMs = 10))
    }

    @Test
    fun aFailingGeminiFallsBackToGoogle() =
        runBlocking {
            val failures = mutableListOf<String>()
            val result =
                withFallback(
                    primary = { throw IOException("HTTP 429 - quota") },
                    onFailure = { failures += it.message.orEmpty() },
                    fallback = { "google" },
                )
            assertEquals("google", result)
            assertEquals(listOf("HTTP 429 - quota"), failures)
            assertEquals("gemini", withFallback(primary = { "gemini" }, onFailure = { fail() }, fallback = { "google" }))
        }

    private fun reply(text: String): String =
        JSONObject()
            .put(
                "candidates",
                JSONArray().put(
                    JSONObject().put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))),
                ),
            ).toString()

    private fun strings(array: JSONArray): List<String> = (0 until array.length()).map { array.getString(it) }

    private fun assertFails(
        message: String,
        block: () -> Unit,
    ) {
        try {
            block()
            fail("Expected an IOException containing '$message'")
        } catch (error: IOException) {
            assertTrue("'${error.message}' should contain '$message'", error.message.orEmpty().contains(message))
        }
    }
}
