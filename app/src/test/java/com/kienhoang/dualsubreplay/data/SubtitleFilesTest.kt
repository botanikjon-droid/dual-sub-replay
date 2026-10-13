package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleFilesTest {
    @Test
    fun formatsSrtWithNumberedBlocksAndCommaMilliseconds() {
        val text =
            formatSrt(
                listOf(
                    SubtitleSegment(0, 1_500, 4_000, "Place the probe below the ribs."),
                    SubtitleSegment(1, 3_725_042, 3_727_000, "Then rotate it."),
                ),
            )

        assertEquals(
            "1\n00:00:01,500 --> 00:00:04,000\nPlace the probe below the ribs.\n\n" +
                "2\n01:02:05,042 --> 01:02:07,000\nThen rotate it.\n",
            text,
        )
    }

    @Test
    fun exportedSrtParsesBackToTheSameTimingAndText() {
        val segments =
            listOf(
                SubtitleSegment(0, 0, 2_000, "The liver is homogeneous."),
                SubtitleSegment(1, 2_000, 5_250, "Measure the portal vein."),
            )

        val cues = parseSubtitleFile(formatSrt(segments))

        assertEquals(listOf(0L to 2_000L, 2_000L to 5_250L), cues.map { it.startMs to it.endMs })
        assertEquals(listOf("The liver is homogeneous.", "Measure the portal vein."), cues.map { it.text })
    }

    @Test
    fun parsesSrtWithBomWindowsLineEndsTagsAndMultipleLines() {
        val srt =
            "\uFEFF1\r\n00:00:01,000 --> 00:00:03,000\r\n<i>Jigar</i> bir xil\r\nexogenlikda.\r\n\r\n" +
                "2\r\n00:00:04,000 --> 00:00:05,000\r\nPortal vena.\r\n"

        val cues = parseSubtitleFile(srt)

        assertEquals(2, cues.size)
        assertEquals("Jigar bir xil exogenlikda.", cues[0].text)
        assertEquals(1_000L, cues[0].startMs)
        assertEquals("Portal vena.", cues[1].text)
    }

    @Test
    fun parsesWebVttHeaderShortTimesAndCueSettings() {
        val vtt =
            "WEBVTT\n\nNOTE translated by hand\n\nintro\n00:01.000 --> 00:02.500 align:start position:10%\n" +
                "O‘t pufagi\n\n01:00:00.000 --> 01:00:01.000\nYakun.\n"

        val cues = parseSubtitleFile(vtt)

        assertEquals(listOf(1_000L to 2_500L, 3_600_000L to 3_601_000L), cues.map { it.startMs to it.endMs })
        assertEquals("O‘t pufagi", cues[0].text)
    }

    @Test
    fun skipsMalformedAndEmptyCuesAndSortsByStart() {
        val srt =
            "1\n00:00:05,000 --> 00:00:06,000\nIkkinchi\n\n" +
                "2\n00:00:09,000 --> 00:00:08,000\nOrqaga ketgan vaqt\n\n" +
                "3\n00:00:01,000 --> 00:00:02,000\n\n\n" +
                "4\n00:00:01,000 --> 00:00:02,000\nBirinchi\n"

        val cues = parseSubtitleFile(srt)

        assertEquals(listOf("Birinchi", "Ikkinchi"), cues.map { it.text })
    }

    @Test
    fun textWithoutTimecodesHasNoCues() {
        assertTrue(parseSubtitleFile("just some notes\nwithout timing").isEmpty())
        assertTrue(parseSubtitleFile("").isEmpty())
    }

    @Test
    fun parsesTimestampForms() {
        assertEquals(3_723_004L, parseTimestamp("01:02:03,004"))
        assertEquals(3_723_400L, parseTimestamp("1:02:03.4"))
        assertEquals(62_500L, parseTimestamp("01:02.500"))
        assertNull(parseTimestamp("00:61:00,000"))
        assertNull(parseTimestamp("abc"))
    }

    @Test
    fun importedCueIsShownOnTheRowItOverlapsMost() {
        val rows =
            listOf(
                SubtitleSegment(0, 0, 1_000, "Place the probe", translatedText = "machine 1"),
                SubtitleSegment(1, 1_000, 4_000, "below the right ribs", translatedText = "machine 2"),
                SubtitleSegment(2, 4_000, 6_000, "and angle up.", translatedText = "machine 3"),
            )
        val cues =
            listOf(
                RawCaptionCue(0, 4_000, "Datchikni o‘ng qovurg‘alar ostiga qo‘ying"),
                RawCaptionCue(4_000, 6_000, "va yuqoriga burang."),
            )

        val result = applyImportedTranslation(rows, cues)

        assertEquals(
            listOf("", "Datchikni o‘ng qovurg‘alar ostiga qo‘ying", "va yuqoriga burang."),
            result.map { it.translatedText },
        )
        assertEquals(rows.map { it.originalText }, result.map { it.originalText })
    }

    @Test
    fun rowsWithoutAnImportedCueGetAnEmptyTranslation() {
        val rows = listOf(SubtitleSegment(0, 10_000, 12_000, "Untranslated"))

        val result = applyImportedTranslation(rows, listOf(RawCaptionCue(0, 2_000, "Boshqa joy")))

        assertEquals("", result.single().translatedText)
    }

    @Test
    fun twoCuesOnOneRowAreJoined() {
        val rows = listOf(SubtitleSegment(0, 0, 4_000, "A long sentence"))
        val cues = listOf(RawCaptionCue(0, 2_000, "Birinchi qism,"), RawCaptionCue(2_000, 4_000, "ikkinchi qism."))

        assertEquals("Birinchi qism, ikkinchi qism.", applyImportedTranslation(rows, cues).single().translatedText)
    }

    @Test
    fun ignoresAiChatTextAndCodeFencesAroundSrt() {
        val reply =
            "Mana tarjima:\n\n```srt\n1\n00:00:01,000 --> 00:00:02,000\nJigar\n```\n\n" +
                "Yana savol bo\u2018lsa yozing."

        val cues = parseSubtitleFile(reply)

        assertEquals(listOf("Jigar"), cues.map { it.text })
    }

    @Test
    fun aPartialImportReplacesOnlyTheTimeItCovers() {
        val existing =
            listOf(
                RawCaptionCue(0, 1_000, "eski 1"),
                RawCaptionCue(1_000, 2_000, "eski 2"),
                RawCaptionCue(5_000, 6_000, "eski 3"),
            )
        val incoming = listOf(RawCaptionCue(1_000, 2_000, "yangi 2"), RawCaptionCue(2_000, 3_000, "yangi 2b"))

        val merged = mergeImportedCues(existing, incoming)

        assertEquals(listOf("eski 1", "yangi 2", "yangi 2b", "eski 3"), merged.map { it.text })
    }

    @Test
    fun translationRequestKeepsTheSrtImportable() {
        val srt = formatSrt(listOf(SubtitleSegment(0, 0, 1_000, "Liver")))

        val request = translationRequest(srt, "O\u2018zbek")

        assertTrue(request.endsWith(srt))
        assertEquals(listOf("Liver"), parseSubtitleFile(request).map { it.text })
    }

    @Test
    fun boundedReadRejectsOversizedInput() {
        assertEquals("abc", readBoundedText("abc".byteInputStream(), maxBytes = 3))
        val error = runCatching { readBoundedText("abcd".byteInputStream(), maxBytes = 3) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun subtitleFileIsNamedAfterTheVideo() {
        assertEquals("Pancreas.srt", subtitleFileName("Pancreas", "IsJeMgxTFMU"))
        assertEquals("Liver Doppler part 1 2.srt", subtitleFileName("  Liver: Doppler / part 1|2 ", "x"))
        assertEquals("IsJeMgxTFMU.srt", subtitleFileName(null, "IsJeMgxTFMU"))
        assertEquals("IsJeMgxTFMU.srt", subtitleFileName(" ?? ", "IsJeMgxTFMU"))
        assertEquals(84, subtitleFileName("a".repeat(200), "x").length)
    }

    @Test
    fun headerCarriesTitleAndLinkAndDoesNotBreakImport() {
        val header = lessonHeader("Pancreas", "IsJeMgxTFMU")
        assertEquals("Pancreas\nhttps://youtu.be/IsJeMgxTFMU\n", header)
        assertEquals("https://youtu.be/abc\n", lessonHeader(null, "abc"))

        val srt = formatSrt(listOf(SubtitleSegment(0, 0, 1_000, "Oshqozon osti bezi")))
        assertEquals(listOf("Oshqozon osti bezi"), parseSubtitleFile(header + "\n" + srt).map { it.text })
    }
}
