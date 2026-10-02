package com.kienhoang.dualsubreplay.dubbing

import com.kienhoang.dualsubreplay.data.SentenceSlice
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingPlanTest {
    private val sentence = "the common bile duct measures four millimeters which is normal"
    private val cuts = listOf(21, 46)

    private fun row(
        id: Long,
        startMs: Long,
        endMs: Long,
        index: Int,
        translated: String?,
    ) = SubtitleSegment(
        id = id,
        startMs = startMs,
        endMs = endMs,
        originalText = "row $index",
        translatedText = translated,
        sentence = SentenceSlice(sentence, cuts, index),
    )

    @Test
    fun joinsTheRowsOfOneSentence() {
        val rows =
            listOf(
                row(1, 1_000, 2_000, 0, "umumiy o't yo'li"),
                row(2, 2_000, 3_000, 1, "to'rt millimetr,"),
                row(3, 3_000, 4_500, 2, " bu normal"),
            )
        val lines = dubLines(rows)
        assertEquals(1, lines.size)
        assertEquals("umumiy o't yo'li to'rt millimetr, bu normal", lines[0].text)
        assertEquals(1_000L, lines[0].startMs)
        assertEquals(4_500L, lines[0].endMs)
    }

    @Test
    fun waitsForEveryRowOfTheSentence() {
        val untranslated = listOf(row(1, 1_000, 2_000, 0, "a"), row(2, 2_000, 3_000, 1, null), row(3, 3_000, 4_000, 2, "c"))
        assertTrue(dubLines(untranslated).isEmpty())
        // The window starts in the middle of the sentence: its first row is missing.
        val cutOff = listOf(row(2, 2_000, 3_000, 1, "b"), row(3, 3_000, 4_000, 2, "c"))
        assertTrue(dubLines(cutOff).isEmpty())
    }

    @Test
    fun keepsRowsWithoutASentence() {
        val plain = SubtitleSegment(id = 9, startMs = 500, endMs = 900, originalText = "Hi", translatedText = " Salom ")
        val blank = plain.copy(id = 10, startMs = 950, translatedText = "  ")
        assertEquals(listOf("Salom"), dubLines(listOf(plain, blank)).map(DubLine::text))
    }

    @Test
    fun tempoFitsTheSlotWithinTheLimit() {
        assertEquals(1f, dubTempo(clipMs = 2_000, slotMs = 3_000, lateMs = 0, maxTempo = 1.5f))
        assertEquals(1.25f, dubTempo(clipMs = 2_500, slotMs = 2_000, lateMs = 0, maxTempo = 1.5f))
        assertEquals(1.5f, dubTempo(clipMs = 9_000, slotMs = 2_000, lateMs = 0, maxTempo = 1.5f))
        assertEquals(1.25f, dubTempo(clipMs = 2_000, slotMs = 2_600, lateMs = 1_000, maxTempo = 1.5f))
    }

    private fun line(
        startMs: Long,
        endMs: Long = startMs + 1_500,
    ) = DubLine("k$startMs", startMs, endMs, "t$startMs")

    @Test
    fun speaksLinesOnceAndInOrder() {
        val scheduler = DubScheduler()
        scheduler.update(listOf(line(1_000), line(3_000)))
        assertNull(scheduler.next(500, busy = false) { true })
        val first = scheduler.next(1_000, busy = false) { true }
        assertEquals(1_000L, first?.startMs)
        scheduler.started(checkNotNull(first))
        assertNull("a started line is not repeated", scheduler.next(1_100, busy = false) { true })
        assertEquals(3_000L, scheduler.next(3_020, busy = false) { true }?.startMs)
        assertEquals(2_000L, scheduler.slotAfter(first))
    }

    @Test
    fun waitsForAudioThenSkipsWhenTooLate() {
        val scheduler = DubScheduler(maxLateMs = 2_000)
        scheduler.update(listOf(line(1_000), line(5_000)))
        scheduler.next(0, busy = false) { false }
        assertNull("audio not ready yet", scheduler.next(1_500, busy = false) { false })
        assertNull("too late now: skipped", scheduler.next(3_200, busy = false) { true })
        assertEquals(5_000L, scheduler.next(5_000, busy = false) { true }?.startMs)
    }

    @Test
    fun waitsForAPlayingClipThenCutsIt() {
        val scheduler = DubScheduler(maxLateMs = 2_000, cutAfterMs = 1_200)
        scheduler.update(listOf(line(1_000)))
        scheduler.next(0, busy = false) { true }
        assertNull("previous clip still speaking", scheduler.next(1_500, busy = true) { true })
        assertEquals("1.2 s late: cut the previous clip", 1_000L, scheduler.next(2_300, busy = true) { true }?.startMs)
    }

    @Test
    fun seekForgetsEarlierLinesAndAllowsRewinding() {
        val scheduler = DubScheduler()
        scheduler.update(listOf(line(1_000), line(10_000)))
        scheduler.next(0, busy = false) { true }
        scheduler.seek(9_900)
        assertEquals(listOf(10_000L), scheduler.upcoming(9_900, 45_000).map(DubLine::startMs))
        scheduler.started(checkNotNull(scheduler.next(10_000, busy = false) { true }))
        scheduler.seek(900)
        assertEquals("rewinding speaks the line again", 1_000L, scheduler.next(1_000, busy = false) { true }?.startMs)
    }

    @Test
    fun mergesWindowsWithoutDuplicates() {
        val scheduler = DubScheduler()
        scheduler.update(listOf(line(1_000), line(3_000)))
        scheduler.update(listOf(line(3_000), line(6_000)))
        assertEquals(listOf(1_000L, 3_000L, 6_000L), scheduler.upcoming(0, 45_000).map(DubLine::startMs))
    }

    @Test
    fun volumeScriptUsesTheChosenLevelAndRestores() {
        assertTrue(dubbingVolumeScript(duck = true, videoVolumePercent = 35).contains("v.__dualsubVolume*0.35"))
        assertTrue(dubbingVolumeScript(duck = true, videoVolumePercent = 250).contains("v.__dualsubVolume*1.0"))
        assertTrue(dubbingVolumeScript(duck = false).contains("v.volume=v.__dualsubVolume;v.__dualsubVolume=null"))
    }

    @Test
    fun qualityScriptOnlyWhenACapIsChosen() {
        assertTrue(videoQualityScript(quality = "medium", available = true)!!.contains("('medium')"))
        assertNull(videoQualityScript(quality = VIDEO_QUALITY_AUTO, available = true))
        assertNull("full/fdroid builds never touch the quality", videoQualityScript(quality = "medium", available = false))
        assertNull(videoQualityScript(quality = "hd720'); alert('x", available = true))
    }
}
