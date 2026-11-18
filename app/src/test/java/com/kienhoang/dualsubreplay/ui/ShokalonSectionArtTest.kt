package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ShokalonSectionArtTest {
    @Test
    fun everyCatalogSectionGetsItsOwnPicture() {
        val expected =
            mapOf(
                "Ultratovush asoslari: fizika va apparat" to "physics",
                "Tekshiruv metodikasi va xulosa yozish" to "method",
                "Qorin bo‘shlig‘i a’zolari" to "abdomen",
                "Ginekologiya" to "gyn",
                "Urologiya" to "uro",
                "Neyrosonografiya" to "neuro",
                "Pediatrik ultratovush" to "peds",
                "Yurak exokardiografiyasi" to "heart",
                "Qalqonsimon bez" to "thyroid",
                "Ko‘krak bezi" to "breast",
                "Yuzaki tuzilmalar" to "superficial",
                "Homila skriningi" to "fetal",
                "Qon tomir doppleri" to "vascular",
                "Transkranial doppler" to "tcd",
                "Mushak, bo‘g‘im va nerv tizimi" to "msk",
                "Shoshilinch UZI va POCUS" to "pocus",
                "Elastografiya, kontrastli UZI va intervension UZI" to "elasto",
                "Sifat nazorati va kasbiy rivojlanish" to "quality",
            )
        expected.forEach { (name, key) -> assertEquals(name, key, sectionArtKey(name)) }
        assertEquals("physics", sectionArtKey("Yangi bo‘lim"))
    }
}
