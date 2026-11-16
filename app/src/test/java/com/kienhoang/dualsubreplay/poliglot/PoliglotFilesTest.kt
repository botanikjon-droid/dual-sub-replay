package com.kienhoang.dualsubreplay.poliglot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.TimeZone

class PoliglotFilesTest {
    private val tashkent = TimeZone.getTimeZone("Asia/Samarkand")

    @Test
    fun recordingNameKeepsOnlySafeCharacters() {
        assertEquals("1700_day-3-en.m4a", poliglotRecordingName(1700, "day 3/en"))
        assertEquals("1700_yozuv.m4a", poliglotRecordingName(1700, null))
        assertTrue(isPoliglotRecordingName(poliglotRecordingName(1700, "../../x")))
    }

    @Test
    fun onlyPlainRecordingNamesArePlayedOrDeleted() {
        assertTrue(isPoliglotRecordingName("1700_day-3.m4a"))
        assertFalse(isPoliglotRecordingName("../poliglot-state.json"))
        assertFalse(isPoliglotRecordingName("../x.m4a"))
        assertFalse(isPoliglotRecordingName("a/b.m4a"))
        assertFalse(isPoliglotRecordingName("poliglot-state.json"))
        assertFalse(isPoliglotRecordingName(""))
        assertFalse(isPoliglotRecordingName(null))
    }

    @Test
    fun reminderRingsTodayIfAheadElseTomorrow() {
        // 2026-11-16 18:00 in Tashkent (UTC+5) = 13:00 UTC.
        val now = 1_794_834_000_000L
        val evening = nextPoliglotReminder(now, 20, 0, tashkent)
        assertEquals(2 * 3_600_000L, evening - now)
        val morning = nextPoliglotReminder(now, 7, 30, tashkent)
        assertEquals((13 * 60 + 30) * 60_000L, morning - now)
    }

    @Test
    fun onlyRealClockTimesAreKept() {
        assertTrue(isPoliglotClockTime(0, 0))
        assertTrue(isPoliglotClockTime(23, 59))
        assertFalse(isPoliglotClockTime(-1, 0))
        assertFalse(isPoliglotClockTime(24, 0))
        assertFalse(isPoliglotClockTime(8, 60))
    }

    @Test
    fun courseShipsWithItsPageAndBridgeCalls() {
        val dir = File("src/main/assets/poliglot")
        listOf("index.html", "app.js", "data.js").forEach { assertTrue("$it missing", File(dir, it).isFile) }
        val app = File(dir, "app.js").readText()
        // Progress is kept in the app's files through the bridge, browser storage is only a spare copy.
        assertTrue(app.contains("A.saveState"))
        assertTrue(app.contains("A.loadState"))
    }
}
