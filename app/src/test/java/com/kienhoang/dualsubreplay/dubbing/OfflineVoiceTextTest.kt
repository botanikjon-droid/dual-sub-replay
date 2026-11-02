package com.kienhoang.dualsubreplay.dubbing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineVoiceTextTest {
    @Test
    fun numbersAreSpokenInUzbek() {
        assertEquals("bir yuz yigirma besh", uzbekNumberWords(125))
        assertEquals("ikki ming yigirma olti", uzbekNumberWords(2026))
        assertEquals("o‘n ikki mm, ikki butun besh sm", speakNumbers("12 mm, 2,5 sm"))
    }

    @Test
    fun latinBecomesCyrillic() {
        assertEquals("Ўт пуфаги шиш, чап буйрак", latinToUzbekCyrillic("O‘t pufagi shish, chap buyrak"))
        assertEquals("ғовак ёғ юрак яна эхоген маъно", latinToUzbekCyrillic("g‘ovak yog‘ yurak yana exogen ma’no"))
        assertEquals("меъёрда", latinToUzbekCyrillic("me’yorda"))
    }

    @Test
    fun idsFollowVitsTokenizerWithBlanks() {
        val vocab = mapOf("|" to 0, "а" to 1, "б" to 2, " " to 3)
        assertArrayEquals(longArrayOf(0, 1, 0, 3, 0, 2, 0), mmsTokenIds("А б!", vocab, addBlank = true, blankId = 0))
        assertArrayEquals(longArrayOf(1, 2), mmsTokenIds("аб", vocab, addBlank = false, blankId = 0))
    }

    @Test
    fun wavHeaderDescribesTheSamples() {
        val wav = wavBytes(floatArrayOf(0f, 1f, -1f), 16_000)
        assertEquals(44 + 6, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(
            0x7FFF.toShort(),
            java.nio.ByteBuffer
                .wrap(wav, 46, 2)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .short,
        )
    }
}
