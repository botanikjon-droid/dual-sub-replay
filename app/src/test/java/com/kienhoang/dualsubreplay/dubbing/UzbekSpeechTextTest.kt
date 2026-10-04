package com.kienhoang.dualsubreplay.dubbing

import org.junit.Assert.assertEquals
import org.junit.Test

class UzbekSpeechTextTest {
    private val okina = "\u02BB"

    @Test
    fun writesOAndGWithTheOfficialApostrophe() {
        assertEquals("o${okina}zbek", uzbekSpeechText("o'zbek"))
        assertEquals("To${okina}g${okina}ri", uzbekSpeechText("To'g'ri"))
        assertEquals("G${okina}oya va O${okina}zbekiston", uzbekSpeechText("G'oya va O'zbekiston"))
    }

    @Test
    fun acceptsEveryApostropheLookAlike() {
        listOf("'", "\u2018", "\u2019", "\u02BC", "\u02B9", "\u2032", "`", "\u00B4", "\uFF07").forEach { mark ->
            assertEquals("bo${okina}lim", uzbekSpeechText("bo${mark}lim"))
            assertEquals("tog$okina", uzbekSpeechText("tog$mark"))
        }
    }

    @Test
    fun leavesTheGlottalStopAndQuotationMarksAlone() {
        assertEquals("ma'no", uzbekSpeechText("ma'no"))
        assertEquals("sa'nat va she'r", uzbekSpeechText("sa\u2019nat va she\u2018r"))
        assertEquals("\u201Csalom\u201D deb aytdi", uzbekSpeechText("\u201Csalom\u201D deb aytdi"))
        assertEquals("\u2018salom\u2019", uzbekSpeechText("\u2018salom\u2019"))
    }

    @Test
    fun isIdempotentAndKeepsPlainText() {
        val once = uzbekSpeechText("Jigar o'lchami me'yorda, o\u2018ng bo'lak g\u2019ayri-oddiy.")
        assertEquals(once, uzbekSpeechText(once))
        assertEquals("Umumiy o${okina}t yo${okina}li kengaymagan.", uzbekSpeechText("Umumiy o't yo'li kengaymagan."))
        assertEquals("Portal vena 12 mm.", uzbekSpeechText("Portal vena 12 mm."))
    }
}
