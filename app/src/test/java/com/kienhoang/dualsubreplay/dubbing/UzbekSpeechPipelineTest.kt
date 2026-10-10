package com.kienhoang.dualsubreplay.dubbing

import com.kienhoang.dualsubreplay.data.UZI_GLOSSARY_ASSET
import com.kienhoang.dualsubreplay.data.UziGlossary
import com.kienhoang.dualsubreplay.data.glossaryAcronymExpansions
import com.kienhoang.dualsubreplay.data.parseUziGlossary
import com.kienhoang.dualsubreplay.data.translationUsesApprovedTerm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UzbekSpeechPipelineTest {
    private val okina = "ʻ"
    private val entries = parseUziGlossary(File("src/main/assets/$UZI_GLOSSARY_ASSET").readText()).entries
    private val acronyms = glossaryAcronymExpansions(entries)

    @Test
    fun displayTextIsNeverChanged() {
        val display = "Umumiy o‘t yo'li va o`ng bo’lak"
        val prepared = prepareUzbekSpeech(display, SpeechOptions(readUnits = true, expandAcronyms = true), acronyms)
        assertEquals(display, prepared.display)
        assertEquals("Umumiy o${okina}t yo${okina}li va o${okina}ng bo${okina}lak", prepared.tts)
    }

    @Test
    fun plainOAndGAndDigraphsAreLeftAlone() {
        val text = "jigar venasining portal venaning shilliq chiziq ng sh ch go‘zal emas, goh-goh"
        assertEquals(text.replace("go‘", "go$okina"), prepareUzbekSpeech(text).tts)
        listOf("jigar venasining", "portal venaning", "Ganglion", "og‘iz").forEach { word ->
            val tts = prepareUzbekSpeech(word).tts
            assertEquals(word.replace("‘", okina), tts)
        }
    }

    @Test
    fun theRequestedWordsGetTheOfficialLetter() {
        val words = listOf("O'zbekiston", "to'qima", "o’tkazuvchanlik", "boʼylama", "g`ovak", "o‘ng", "so'nggi")
        val expected =
            listOf(
                "O${okina}zbekiston",
                "to${okina}qima",
                "o${okina}tkazuvchanlik",
                "bo${okina}ylama",
                "g${okina}ovak",
                "o${okina}ng",
                "so${okina}nggi",
            )
        assertEquals(expected, words.map { prepareUzbekSpeech(it).tts })
    }

    @Test
    fun everyChangeIsTraced() {
        val prepared = prepareUzbekSpeech("O'ng bo'lak, ma’no")
        assertEquals(listOf("okina", "tutuq"), prepared.applied.map { it.rule.id })
        assertEquals("O'ng bo'lak, ma’no", prepared.applied[0].before)
        assertEquals("O${okina}ng bo${okina}lak, ma’no", prepared.applied[0].after)
        assertEquals("O${okina}ng bo${okina}lak, ma'no", prepared.applied[1].after)
        assertTrue(prepared.applied.all { it.rule.confirmed })
        assertEquals("o‘zgarishsiz", prepareUzbekSpeech("Portal vena 12 mm.").ruleSummary())
    }

    @Test
    fun experimentsAreOffByDefault() {
        val text = "Qattiqlik 12 kPa, RI 0,7, diametri 4 mm."
        assertEquals(text, prepareUzbekSpeech(text, acronyms = acronyms).tts)
    }

    @Test
    fun unitsAfterNumbersAreReadAsWords() {
        val options = SpeechOptions(readUnits = true)
        assertEquals("Qattiqlik 12 kilopaskal.", prepareUzbekSpeech("Qattiqlik 12 kPa.", options).tts)
        assertEquals("4 millimetr va 1,2 santimetr", prepareUzbekSpeech("4mm va 1,2 sm", options).tts)
        assertEquals("30 santimetr sekundiga, 2 metr sekundiga", prepareUzbekSpeech("30 sm/s, 2 m/s", options).tts)
        assertEquals("5 megagers, 40 foiz", prepareUzbekSpeech("5 MHz, 40%", options).tts)
        // A unit is changed only after a number, never inside words.
        assertEquals("smena mm kPa", prepareUzbekSpeech("smena mm kPa", options).tts)
        assertEquals(listOf("birliklar"), prepareUzbekSpeech("12 kPa", options).applied.map { it.rule.id })
    }

    @Test
    fun acronymsComeFromTheGlossary() {
        assertEquals("rezistivlik indeksi", acronyms["RI"])
        assertEquals("vaqt bo‘yicha kuchaytirishni kompensatsiya qilish", acronyms["TGC"])
        assertEquals("to‘lqin siljish elastografiyasi", acronyms["SWE"])
        assertEquals("umumiy o‘t yo‘li", acronyms["CBD"])
        // Its Uzbek name already contains the abbreviation, so it is never expanded.
        assertTrue("TAPSE" !in acronyms && "TI-RADS" !in acronyms && "METAVIR" !in acronyms)
        val options = SpeechOptions(expandAcronyms = true)
        assertEquals(
            "rezistivlik indeksi 0,7. Bu rezistivlik indeksi.",
            prepareUzbekSpeech("RI 0,7. Bu rezistivlik indeksi (RI).", options, acronyms).tts,
        )
        // Words that merely contain the letters are untouched; added words get oʻ too.
        assertEquals("RIM TGCX", prepareUzbekSpeech("RIM TGCX", options, acronyms).tts)
        assertEquals("vaqt bo${okina}yicha kuchaytirishni kompensatsiya qilish", prepareUzbekSpeech("TGC", options, acronyms).tts)
    }

    @Test
    fun isIdempotent() {
        val options = SpeechOptions(readUnits = true, expandAcronyms = true)
        val once = prepareUzbekSpeech("O'ng bo'lak 12 kPa, RI 0,7", options, acronyms).tts
        assertEquals(once, prepareUzbekSpeech(once, options, acronyms).tts)
    }

    /** 32 sample lecture captions: glossary terms, approved Uzbek terms, and a speech copy that keeps the text. */
    @Test
    fun sampleUltrasoundCaptions() {
        val glossary = UziGlossary(entries)
        val cases =
            File("src/test/resources/uzi_translation_cases.tsv")
                .readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .drop(1)
                .map { it.split('\t') }
        assertEquals(32, cases.size)
        cases.forEach { (id, english, uzbek, expectedTerms) ->
            val found = glossary.findTerms(english)
            assertEquals("case $id terms", expectedTerms.split(';').map(String::trim), found.map { it.entry.english })
            found.forEach { match ->
                assertTrue("case $id uses the approved term for ${match.entry.english}", translationUsesApprovedTerm(match.entry, uzbek))
            }
            val tts = prepareUzbekSpeech(uzbek).tts
            // The speech copy differs only in the apostrophe of oʻ and gʻ.
            assertEquals(
                "case $id",
                uzbek.replace("‘", "").replace("’", "").replace("'", ""),
                tts.replace(okina, "").replace("'", ""),
            )
        }
    }
}
