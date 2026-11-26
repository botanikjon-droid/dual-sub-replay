package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class UziOralTest {
    private fun oral(
        n: Int,
        level: String,
        q: String,
    ) = """{"n":$n,"l":"$level","t":"jigar","q":"$q","o":[],"a":"Qisqa javob.","e":"Ta’rif: …","f":"","s":0}"""

    private val bank =
        parseUziTestBank(
            """{"version":1,"lessons":[],"questions":[${oral(
                1,
                "2-3",
                "Jigar anatomiyasi",
            )},${oral(2, "1", "Jigar anatomiyasi")},${oral(3, "oliy", "Jigar sirrozi")}]}""",
        )

    @Test
    fun levelsFilterAndRepeatedQuestionsShowOnce() {
        assertEquals(listOf(1, 3), bank.filter(null).map { it.number })
        assertEquals(listOf(2), bank.filter(null, level = "1").map { it.number })
        assertEquals("1", bank.questions[1].level)
        assertTrue(bank.questions.all { it.options.isEmpty() })
    }

    @Test
    fun notesSurviveTheirFileAndBlankOnesAreDropped() {
        val notes = mapOf(uziNoteKey(UziTestBankKind.ORAL, 12) to "Kuino: 8 segment", "QUALIFICATION:3" to "")
        val back = uziNotesFromJson(uziNotesToJson(notes))
        assertEquals(mapOf("ORAL:12" to "Kuino: 8 segment"), back)
        assertEquals(emptyMap<String, String>(), uziNotesFromJson("{broken"))
    }

    @Test
    fun shippedOralBankHasAnAnswerForEveryQuestion() {
        val file = File("src/main/assets/$UZI_ORAL_ASSET")
        // The bank is added in its own commit once every answer is written.
        assumeTrue(file.isFile)
        val shipped = parseUziTestBank(file.readText(Charsets.UTF_8))
        assertEquals((1..450).toList(), shipped.questions.map { it.number })
        assertEquals(emptyList<Int>(), shipped.questions.filter { it.answer.isBlank() || it.explanation.isBlank() }.map { it.number })
        assertEquals(emptyList<Int>(), shipped.questions.filter { it.level !in UZI_ORAL_LEVELS }.map { it.number })
        assertEquals(emptyList<Int>(), shipped.questions.filter { it.topic !in UZI_TEST_TOPICS }.map { it.number })
    }
}
