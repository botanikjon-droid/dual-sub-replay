package com.kienhoang.dualsubreplay.dubbing

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechLexiconTest {
    @Test
    fun theVoiceSaysTheDoctorsWords() {
        val rules = mapOf("UTT" to "ultratovush tekshiruvi", "akusherlik UTT" to "akusherlik ultratovush tekshiruvi")
        assertEquals(
            "Doktor Wuning akusherlik ultratovush tekshiruvi tarixi. Bemor yonidagi ultratovush tekshiruvi.",
            applySpeechLexicon("Doktor Wuning akusherlik UTT tarixi. Bemor yonidagi UTT.", rules),
        )
        assertEquals(
            "ultratovush tekshiruvida, ultratovush tekshiruvining",
            applySpeechLexicon("UTTda, UTTning", rules),
        )
        assertEquals("jigarning JIGAR", applySpeechLexicon("jigarning jigar", mapOf("jigar" to "JIGAR")))
    }

    @Test
    fun rulesSurviveStorage() {
        val rules = mapOf("UTT" to "ultratovush tekshiruvi")
        assertEquals(rules, speechLexiconFromJson(speechLexiconToJson(rules)))
        assertEquals(emptyMap<String, String>(), speechLexiconFromJson("not json"))
    }
}
