package com.kienhoang.dualsubreplay.dubbing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AzureSpeechTest {
    @Test
    fun regionIsNormalizedIntoTheUrl() {
        assertEquals("https://westeurope.tts.speech.microsoft.com/cognitiveservices/v1", azureTtsUrl(" West Europe "))
    }

    @Test
    fun ssmlUsesTheShortVoiceNameAndEscapesText() {
        val ssml = azureSsml("uz-UZ-MadinaNeural", "jigar <5 mm> & taloq\u0001")
        assertTrue(ssml.contains("<voice name='uz-UZ-MadinaNeural'>jigar &lt;5 mm&gt; &amp; taloq </voice>"))
        assertTrue(ssml.contains("xml:lang='uz-UZ'"))
    }

    @Test
    fun errorsAreExplainedInUzbek() {
        assertTrue(azureSpeechError(401).contains("kaliti"))
        assertTrue(edgeVoiceFailureMessage("403").contains("Azure Speech"))
    }
}
