package com.kienhoang.dualsubreplay.dubbing

import com.kienhoang.dualsubreplay.data.SubtitleSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeakableTextTest {
    @Test
    fun musicMarksAreNotRead() {
        assertNull(speakableText("[Musiqa]"))
        assertNull(speakableText("♪"))
        assertNull(speakableText("♪ ♪"))
        assertNull(speakableText("[Music]"))
        assertNull(speakableText("Musiqa."))
        assertNull(speakableText("[Qarsaklar]"))
        assertEquals("Jigar parenximasi bir xil.", speakableText("[Musiqa] Jigar parenximasi bir xil. ♪"))
        assertEquals("Bugun musiqa terapiyasi haqida emas.", speakableText("Bugun musiqa terapiyasi haqida emas."))
    }

    @Test
    fun aMusicRowIsNotDubbed() {
        val rows =
            listOf(
                SubtitleSegment(0, 0, 2_000, "[Music]", translatedText = "[Musiqa]"),
                SubtitleSegment(1, 2_000, 4_000, "hello", translatedText = "Salom."),
            )
        assertEquals(listOf("Salom."), dubLines(rows).map { it.text })
        assertEquals(listOf("Salom."), prefetchLines(listOf("[Musiqa]", "♪", "Salom.", "Salom.")))
    }
}
