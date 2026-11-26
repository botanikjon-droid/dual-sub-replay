package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RecordedCaptionsTest {
    private var revision = 0L

    private fun CaptionRecording.show(
        timeMs: Long,
        text: String,
        seek: Boolean = false,
    ) = record(timeMs, text, text.isNotBlank(), ++revision, "en", seek)

    /** YouTube's rolling auto captions: words appear one by one and the top line scrolls away. */
    private fun rollingLecture() =
        CaptionRecording("x6hXhLNc-jU").apply {
            show(1_000, "so today we")
            show(1_500, "so today we will look")
            show(2_000, "so today we will look at the liver")
            show(2_600, "will look at the liver and")
            show(3_000, "at the liver and its vessels")
            show(6_000, "")
            show(9_000, "Next slide.")
            show(10_000, "")
        }

    @Test
    fun rollingCaptionsBecomeEachWordOnceWithItsTime() {
        val cues = rollingLecture().cues()
        assertEquals(listOf("so today we will look at the liver and its vessels", "Next slide."), cues.map { it.text })
        assertEquals(listOf(1_000L to 6_000L, 9_000L to 10_000L), cues.map { it.startMs to it.endMs })
        val words = cues.first().words
        assertEquals(listOf("so", "today", "we", "will"), words.take(4).map { it.text })
        assertEquals(2_600L, words.single { it.text == "and" }.startMs)
        assertTrue(words.zipWithNext().all { (a, b) -> a.startMs < b.startMs && a.endMs <= b.startMs })
    }

    @Test
    fun theSameTextAndTheSameRevisionAreNotRecordedTwice() {
        val recording = CaptionRecording("x6hXhLNc-jU")
        assertTrue(recording.record(1_000, "the portal vein", true, 7, "en"))
        assertFalse(recording.record(1_033, "the portal vein", true, 7, "en"))
        assertFalse(recording.record(1_100, "the portal vein", true, 8, "en"))
        assertEquals(listOf("the portal vein"), recording.cues().map { it.text })
    }

    @Test
    fun watchingAPartAgainReplacesItInsteadOfRepeatingIt() {
        val recording = rollingLecture()
        recording.show(9_500, "Next slide.", seek = true)
        // The seek sample still shows the old text; the pass starts with the next change.
        recording.show(2_000, "at the liver")
        recording.show(2_600, "at the liver and")
        recording.show(3_000, "at the liver and its vessels")
        recording.show(6_000, "")
        assertEquals(
            listOf("so today we will look at the liver and its vessels", "Next slide."),
            recording.cues().map { it.text },
        )
    }

    @Test
    fun aSkippedPartStaysEmptyAndCoverageShowsWhatWasWatched() {
        val recording =
            CaptionRecording("x6hXhLNc-jU").apply {
                show(0, "first part")
                show(4_000, "")
                show(4_200, "ignored", seek = true)
                show(60_000, "later part")
                show(63_000, "")
            }
        assertEquals(listOf("first part", "later part"), recording.cues().map { it.text })
        assertEquals(listOf(0L..4_000L, 60_000L..63_000L), recording.coveredRanges())
        assertEquals("0:00–0:04, 1:00–1:03", describeRanges(recording.coveredRanges()))
    }

    @Test
    fun longTextIsSplitIntoCuesOfAtMostTwoLinesWithTheirOwnTime() {
        val text = "the common bile duct is measured at the level of the portal vein and it should be less than six millimetres wide"
        val cues =
            CaptionRecording("x6hXhLNc-jU")
                .apply {
                    show(5_000, text)
                    show(9_000, "")
                }.cues()
        assertEquals(2, cues.size)
        assertTrue(cues.all { it.text.length <= 84 })
        assertEquals(text, cues.joinToString(" ") { it.text })
        assertTrue(cues[0].startMs == 5_000L && cues[0].endMs == cues[1].startMs && cues[1].startMs < cues[1].endMs)
    }

    @Test
    fun sentencesAndPausesStartANewCue() {
        val cues =
            CaptionRecording("x6hXhLNc-jU")
                .apply {
                    show(0, "This is the liver.")
                    show(1_000, "This is the liver. Now the kidney")
                    show(5_000, "Now the kidney is here")
                }.cues()
        assertEquals(listOf("This is the liver.", "Now the kidney", "is here"), cues.map { it.text })
    }

    @Test
    fun overlapComparesWithoutCaseAndPunctuation() {
        assertEquals(2, overlap(listOf("look", "at", "The", "liver,"), listOf("the", "liver", "and")))
        assertEquals(0, overlap(listOf("hello"), listOf("world")))
        assertEquals(listOf(0L, 500L, 1_000L, 2_000L), spreadTimes(listOf(0, 0, 1_000, 2_000), 3_000))
    }

    @Test
    fun encodedRecordingsReadBackTheSame() {
        val recording = rollingLecture().apply { complete = true }
        val copy = CaptionRecording.decode("x6hXhLNc-jU", recording.encode())!!
        assertEquals(recording.cues(), copy.cues())
        assertEquals(recording.coveredRanges(), copy.coveredRanges())
        assertTrue(copy.complete)
        assertEquals("en", copy.language)
        assertNull(CaptionRecording.decode("U50O8_fsemY", recording.encode()))
        assertNull(CaptionRecording.decode("x6hXhLNc-jU", "not a recording"))
    }

    @Test
    fun theStoreKeepsOneFilePerVideoAndUsesOnlyCompleteRecordings() {
        val folder = Files.createTempDirectory("recorded").toFile()
        try {
            val store = RecordedCaptionStore(File(folder, RecordedCaptionStore.FOLDER))
            val recording = rollingLecture()
            store.save(recording.videoId, recording.encode())
            assertEquals(2, store.load(recording.videoId)?.cues()?.size)
            assertNull(store.completeCues(recording.videoId))
            recording.complete = true
            store.save(recording.videoId, recording.encode())
            assertEquals("en" to recording.cues(), store.completeCues(recording.videoId))
            store.save("../escape", recording.encode())
            assertNull(store.load("../escape"))
            store.delete(recording.videoId)
            assertNull(store.load(recording.videoId))
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun recordedCuesWriteAsAnOrdinarySrtFile() {
        val srt = formatSrt(rollingLecture().cues().toSegments())
        assertTrue(srt.startsWith("1\n00:00:01,000 --> 00:00:06,000\nso today we will look"))
        assertEquals(rollingLecture().cues().map { it.text }, parseSubtitleFile(srt).map { it.text })
        assertEquals(2, rollingLecture().summary().cues)
    }
}
