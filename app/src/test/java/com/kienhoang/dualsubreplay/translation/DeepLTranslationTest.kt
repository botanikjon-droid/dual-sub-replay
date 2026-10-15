package com.kienhoang.dualsubreplay.translation

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DeepLTranslationTest {
    @Test
    fun freeKeysUseTheFreeHost() {
        assertEquals("https://api-free.deepl.com/v2/translate", deeplEndpoint("abc-123:fx"))
        assertEquals("https://api.deepl.com/v2/translate", deeplEndpoint("abc-123"))
    }

    @Test
    fun requestNamesDeepLLanguages() {
        val body = JSONObject(deeplRequestBody("en", "uz", listOf("The liver is normal.")))
        assertEquals("EN", body.getString("source_lang"))
        assertEquals("UZ", body.getString("target_lang"))
        assertEquals("The liver is normal.", body.getJSONArray("text").getString(0))
        assertEquals("EN-US", deeplLanguage("en", asTarget = true))
        assertEquals("PT", deeplLanguage("pt-BR", asTarget = false))
    }

    @Test
    fun replyIsReadAndAnEmptyOneRejected() {
        val reply = """{"translations": [{"detected_source_language": "EN", "text": "Jigar normal."}]}"""
        assertEquals(listOf("Jigar normal."), parseDeepLTranslations(reply, 1))
        assertTrue(runCatching { parseDeepLTranslations("""{"translations": []}""", 1) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { parseDeepLTranslations("<html>", 1) }.exceptionOrNull() is IOException)
    }

    @Test
    fun errorsAreExplainedAndRestAccordingly() {
        assertTrue(deeplErrorMessage(456, """{"message": "Quota exceeded"}""").contains("limit"))
        assertTrue(deeplErrorMessage(403, "").contains("kalit"))
        assertTrue(deeplRestMs(456) > deeplRestMs(500))
        assertTrue(deeplEnabled(TRANSLATION_PROVIDER_DEEPL, "k", pausedUntilMs = 0, nowMs = 1))
        assertFalse(deeplEnabled(TRANSLATION_PROVIDER_DEEPL, "", pausedUntilMs = 0, nowMs = 1))
        assertFalse(deeplEnabled(TRANSLATION_PROVIDER_GOOGLE, "k", pausedUntilMs = 0, nowMs = 1))
        assertFalse(deeplEnabled(TRANSLATION_PROVIDER_DEEPL, "k", pausedUntilMs = 5, nowMs = 1))
    }
}

class DeepLLessonTest {
    private fun reply(body: String): String {
        val texts = JSONObject(body).getJSONArray("text")
        val array = org.json.JSONArray()
        for (i in 0 until texts.length()) array.put(JSONObject().put("text", "uz'" + texts.getString(i)))
        return JSONObject().put("translations", array).toString()
    }

    @Test
    fun aLessonGoesInRequestsOfFiftyAndIsGuided() =
        kotlinx.coroutines.runBlocking {
            val bodies = mutableListOf<String>()
            val backend =
                DeepLBackend("k:fx", prepare = { it.uppercase() }, transport = { _, body ->
                    bodies += body
                    reply(body)
                })
            val sentences = List(120) { "s$it" }
            val result =
                LessonTranslator(
                    backend,
                    DEEPL_LESSON_CHUNK_SIZE,
                ).translate(sentences, { emptyList() }, { emptyList() }) { _, _ -> }
            assertEquals(3, bodies.size)
            assertFalse(JSONObject(bodies[0]).has("source_lang"))
            assertEquals(120, result.translations.size)
            assertEquals("uz’S119", result.translations[119])
        }

    @Test
    fun aBusyServiceIsRetriedAndAWrongKeyIsNot() =
        kotlinx.coroutines.runBlocking {
            var calls = 0
            val busyOnce =
                DeepLBackend("k", transport = { _, body ->
                    calls++
                    if (calls == 1) throw DeepLHttpException(429, "busy") else reply(body)
                }, retryPausesMs = listOf(0L, 0L))
            busyOnce.translate("", ClaudeChunk(0, listOf("a")))
            assertEquals(2, calls)
            val wrongKey = DeepLBackend("k", transport = { _, _ -> throw DeepLHttpException(403, "kalit") }, retryPausesMs = listOf(0L))
            assertTrue(runCatching { wrongKey.translate("", ClaudeChunk(0, listOf("a"))) }.exceptionOrNull() is DeepLHttpException)
        }
}
