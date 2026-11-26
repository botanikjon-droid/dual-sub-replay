package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TranslationZipImportTest {
    private val ids =
        mapOf(
            "x6hXhLNc-jU" to ShokalonId("x6hXhLNc-jU", "03.10.01", "V001"),
            "abcdefghijk" to ShokalonId("abcdefghijk", "01.04.06", "V041"),
        )

    private fun zip(vararg files: Pair<String, String>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            files.forEach { (name, text) ->
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    @Test
    fun namesGiveTheVideoId() {
        assertEquals("V041", translatedSubtitleVid("SHOKALON_01/SHK_010406_V041_UZ-LATIN.srt"))
        assertNull(translatedSubtitleVid("SHK_010406_V041_ASR-EN.srt"))
        assertNull(translatedSubtitleVid("HISOBOT.md"))
    }

    @Test
    fun translationsGoToTheirLecturesAndTheRestIsSkipped() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nJigar parenximasi\n"
        val contents =
            readTranslationZip(
                zip(
                    "uz/SHK_010406_V041_UZ-LATIN.srt" to "\uFEFF$srt",
                    "uz/SHK_031001_V001_UZ-LATIN.srt" to srt,
                    "uz/SHK_999999_V999_UZ-LATIN.srt" to srt,
                    "HISOBOT.md" to "# hisobot",
                ),
                ids,
            )
        assertEquals(listOf("abcdefghijk", "x6hXhLNc-jU"), contents.translations.map { it.videoId })
        assertEquals(srt, contents.translations.first().text)
        assertEquals(listOf("SHK_999999_V999_UZ-LATIN.srt"), contents.unknown)
    }

    @Test
    fun summaryCountsLessons() {
        assertTrue(translationZipSummary(2, 120, emptyList(), emptyList()).startsWith("Yuklandi: 2 ta dars, 120 ta blok."))
        assertTrue(translationZipSummary(0, 0, emptyList(), emptyList()).contains("topilmadi"))
        assertTrue(translationZipSummary(1, 5, listOf("SHK_999999_V999_UZ-LATIN.srt"), emptyList()).contains("Mundarijada yo"))
    }
}
