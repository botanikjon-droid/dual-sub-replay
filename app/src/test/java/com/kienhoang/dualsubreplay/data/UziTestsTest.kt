package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UziTestsTest {
    private val bank by lazy { parseUziTestBank(File("src/main/assets/$UZI_TESTS_ASSET").readText(Charsets.UTF_8)) }

    @Test
    fun shippedBankHasEveryQuestionWithAKeyAndATopic() {
        assertEquals(465, bank.questions.size)
        assertEquals((1..465).toList(), bank.questions.map { it.number })
        assertEquals(emptyList<Int>(), bank.questions.filter { it.correctCount == 0 }.map { it.number })
        assertEquals(emptyList<Int>(), bank.questions.filter { it.topic !in UZI_TEST_TOPICS }.map { it.number })
        assertEquals(emptyList<Int>(), bank.questions.filter { it.answer.isBlank() }.map { it.number })
        assertEquals(UZI_TEST_TOPICS.keys.take(bank.lessons.size), bank.lessons.map { it.id })
    }

    @Test
    fun attestationBankHasAll982QuestionsKeyedAndExplained() {
        val file = File("src/main/assets/$UZI_ATTESTATION_ASSET")
        // The bank is added in its own commit once every answer is checked.
        org.junit.Assume.assumeTrue(file.isFile)
        val attestation = parseUziTestBank(file.readText(Charsets.UTF_8))
        assertEquals((1..982).toList(), attestation.questions.map { it.number })
        assertEquals(emptyList<Int>(), attestation.questions.filter { it.correctCount == 0 }.map { it.number })
        assertEquals(emptyList<Int>(), attestation.questions.filter { it.options.size != 4 }.map { it.number })
        assertEquals(emptyList<Int>(), attestation.questions.filter { it.topic !in UZI_TEST_TOPICS }.map { it.number })
        assertEquals(emptyList<Int>(), attestation.questions.filter { it.answer.isBlank() || it.explanation.isBlank() }.map { it.number })
        // Written in Uzbek Latin, like the attestation itself.
        assertFalse(attestation.questions.any { q -> (q.question + q.answer + q.explanation).any { it in 'а'..'я' } })
        assertEquals(UziTestBankKind.entries.map { it.asset }, listOf(UZI_TESTS_ASSET, UZI_ATTESTATION_ASSET, UZI_ORAL_ASSET))
    }

    @Test
    fun cyrillicBecomesUzbekLatin() {
        assertEquals("Jigar segmentga boʻlinadi", cyrillicToUzbekLatin("Жигар сегментга бўлинади"))
        assertEquals("yer, poyezd, ekran", cyrillicToUzbekLatin("ер, поезд, экран"))
        assertEquals("Qon oqimi gʻovak, hujayra", cyrillicToUzbekLatin("Қон оқими ғовак, ҳужайра"))
        assertEquals("sirroz, aksent", cyrillicToUzbekLatin("цирроз, акцент"))
        assertEquals("Portal vein 12 mm", cyrillicToUzbekLatin("Portal vein 12 mm"))
    }

    @Test
    fun speechTextReadsRangesDecimalsAndBlanks() {
        assertEquals("9 dan 13 gacha millimetr", uziTestSpeechText("9-13 мм"))
        assertEquals("1 butun 5 santimetr", uziTestSpeechText("1,5 см"))
        assertEquals("7 butun 5 megagers", uziTestSpeechText("7,5 МГц"))
        assertEquals("jigar , segmentga", uziTestSpeechText("жигар _____ сегментга"))
    }

    @Test
    fun searchFindsTheSameQuestionFromBothScripts() {
        val latin = bank.filter(null, "portal vena diametri").map { it.number }
        val cyrillic = bank.filter(null, "портал вена диаметри").map { it.number }
        assertTrue(latin.isNotEmpty())
        assertEquals(latin, cyrillic)
        assertEquals(listOf(153), bank.filter(null, "#153").map { it.number })
        assertTrue(bank.filter("jigar", "").all { it.topic == "jigar" })
        assertTrue(uziMatches(uziSearchKey("Ҳомила қон оқими"), uziSearchTerms("xomila qon")))
        assertFalse(uziMatches(uziSearchKey("Жигар"), uziSearchTerms("buyrak")))
    }
}
