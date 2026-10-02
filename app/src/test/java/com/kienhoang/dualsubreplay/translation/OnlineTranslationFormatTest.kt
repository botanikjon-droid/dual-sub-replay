package com.kienhoang.dualsubreplay.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class OnlineTranslationFormatTest {
    @Test
    fun joinsGtxPiecesInOrder() {
        val body =
            """[[["Umumiy o't yo'li ","The common bile duct ",null,null,10],""" +
                """["kengaymagan.","is not dilated.",null,null,10]],null,"en"]"""
        assertEquals("Umumiy o't yo'li kengaymagan.", parseGtxTranslation(body))
    }

    @Test
    fun skipsNonTextGtxPieces() {
        val body = """[[["Jigar",null],[null,null,"transliteration"]],null,"en"]"""
        assertEquals("Jigar", parseGtxTranslation(body))
    }

    @Test
    fun readsCloudTranslation() {
        val body = """{"data":{"translations":[{"translatedText":"Portal vena"}]}}"""
        assertEquals("Portal vena", parseCloudTranslation(body))
    }

    @Test
    fun reportsCloudApiErrorMessage() {
        val body = """{"error":{"code":403,"message":"API key not valid."}}"""
        assertEquals("API key not valid.", apiErrorMessage(body))
        assertFailsWithMessage("API key not valid.") { parseCloudTranslation(body) }
    }

    @Test
    fun rejectsEmptyOrUnreadableReplies() {
        assertFailsWithMessage("no translation") { parseGtxTranslation("""[[],null,"en"]""") }
        assertFailsWithMessage("unreadable") { parseGtxTranslation("<html>429</html>") }
        assertFailsWithMessage("no translation") { parseCloudTranslation("""{"data":{"translations":[]}}""") }
        assertNull(apiErrorMessage("<html>Too Many Requests</html>"))
    }

    @Test
    fun encodesRequestsWithNormalizedLanguageCodes() {
        val query = gtxTranslateQuery("en-US", "uz", "Liver & spleen: 12 cm?")
        assertTrue(query.startsWith("client=gtx&sl=en&tl=uz&dt=t"))
        assertTrue(query.endsWith("q=Liver+%26+spleen%3A+12+cm%3F"))
        assertEquals(
            "q=Hi&source=en&target=uz&format=text",
            cloudTranslateBody("en", "uz", "Hi"),
        )
    }

    private fun assertFailsWithMessage(
        expected: String,
        block: () -> Unit,
    ) {
        try {
            block()
            fail("Expected an IOException containing \"$expected\"")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(expected))
        }
    }
}
