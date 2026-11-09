package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenCleanupTest {
    @Test
    fun hesitationsLeaveTheSentence() {
        assertEquals("Bu jigar kisti.", withoutHesitations("Uh, bu jigar kisti."))
        assertEquals("Bu, jigar kisti.", withoutHesitations("Bu, uh, jigar kisti."))
        assertEquals("Bu jigar kisti.", withoutHesitations("Bu um jigar kisti uh."))
        assertEquals("Muhim umumiy hujjat", withoutHesitations("Muhim umumiy hujjat"))
        assertEquals("Ahamiyati katta", withoutHesitations("Ahamiyati katta"))
        assertEquals("Kist, 12 mm.", withoutHesitations("Kist, uh, 12 mm."))
        assertEquals("Bu jigar", withoutHesitations("Bu uh... jigar"))
    }

    @Test
    fun numbersAreSpelledInUzbek() {
        assertEquals("yigirma ming", uzbekNumberWords(20_000))
        assertEquals("ming besh yuz", uzbekNumberWords(1_500))
        assertEquals("ikki ming o‘n sakkiz", uzbekNumberWords(2_018))
        assertEquals("bir million ikki yuz ming", uzbekNumberWords(1_200_000))
        assertEquals("yuz", uzbekNumberWords(100))
        assertEquals("to‘qson to‘qqiz", uzbekNumberWords(99))
    }

    @Test
    fun bigNumbersAreReadAsNumbers() {
        assertEquals("20 dan yigirma ming gacha", spokenNumbers("20 dan 20,000 gacha"))
        assertEquals("yigirma ming Gs", spokenNumbers("20 000 Gs"))
        assertEquals("o‘lchami 2,5 sm va 12 mm", spokenNumbers("o‘lchami 2,5 sm va 12 mm"))
        assertEquals("ikki ming o‘n sakkiz yil", spokenNumbers("2018 yil"))
    }
}
