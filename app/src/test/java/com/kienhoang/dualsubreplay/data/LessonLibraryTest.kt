package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LessonLibraryTest {
    @Test
    fun fileFormRoundTripsAndSkipsBrokenLines() {
        val records =
            listOf(
                LessonRecord("abcDEF12345", "Pancreas\tultrasound\nlecture", "Gemini", 2_000L, 120),
                LessonRecord("zyx-_987654", "", "Fayl", 1_000L, 30),
            )
        val decoded = decodeLessonLibrary(encodeLessonLibrary(records) + "\nbroken line\n../x\tt\tGemini\t1\t2")
        assertEquals(2, decoded.size)
        assertEquals("Pancreas ultrasound lecture", decoded[0].title)
        assertEquals(records[1], decoded[1])
    }

    @Test
    fun upsertKeepsNewestFirstAndAKnownTitle() {
        val old =
            listOf(LessonRecord("aaaaaaaaaaa", "Liver", "Fayl", 1_000L, 10), LessonRecord("bbbbbbbbbbb", "Kidney", "Claude", 2_000L, 20))
        val next = upsertLessonRecord(old, LessonRecord("aaaaaaaaaaa", "", "Gemini", 3_000L, 11))
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), next.map { it.videoId })
        assertEquals("Liver", next[0].title)
        assertEquals("Gemini", next[0].engine)
        assertEquals("Video ccccccccccc", LessonRecord("ccccccccccc", "", "Fayl", 0L, 0).displayTitle())
    }
}
