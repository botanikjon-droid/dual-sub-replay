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
