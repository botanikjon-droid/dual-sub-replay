package com.kienhoang.dualsubreplay.poliglot

import org.json.JSONObject
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
    fun courseShipsWithItsPages() {
        val dir = File("src/main/assets/poliglot")
        listOf("index.html", "course.js", "classic.html", "app.js", "data.js").forEach { assertTrue("$it missing", File(dir, it).isFile) }
        // The old course keeps its progress through the bridge; the daily card keeps its own file.
        assertTrue(File(dir, "app.js").readText().contains("A.saveState"))
        val page = File(dir, "index.html").readText()
        assertTrue(page.contains("A.saveData"))
        assertTrue(page.contains("../poliglot_content.json"))
    }

    @Test
    fun onlyPlainDataNamesAreStored() {
        assertTrue(isPoliglotDataName("poliglot-v2"))
        assertFalse(isPoliglotDataName("../secret"))
        assertFalse(isPoliglotDataName("a/b"))
        assertFalse(isPoliglotDataName(""))
        assertFalse(isPoliglotDataName(null))
    }

    @Test
    fun claudeChatUrlCarriesThePrompt() {
        assertEquals("https://claude.ai/new?q=Let%27s%20talk%21%20Day%201", claudeNewChatUrl("Let's talk! Day 1"))
    }

    @Test
    fun contentHasSevenFullDaysWithSourcedHadiths() {
        val json = JSONObject(File("src/main/assets/poliglot_content.json").readText())
        assertEquals(
            listOf(1, 3, 6, 13, 29),
            json.getJSONObject("spaced_repetition").getJSONArray("review_offsets_days").let { a ->
                (
                    0 until
                        a.length()
                ).map(a::getInt)
            },
        )
        val tasks = json.getJSONArray("daily_tasks_template")
        assertEquals(6, tasks.length())
        val days = json.getJSONArray("days")
        assertEquals(7, days.length())
        val letters = mutableListOf<String>()
        for (i in 0 until days.length()) {
            val day = days.getJSONObject(i)
            assertEquals(i + 1, day.getInt("day"))
            assertEquals(6, day.getJSONArray("new_words").length())
            assertEquals(3, day.getJSONArray("questions").length())
            assertTrue(day.getString("retell_frame").contains("___"))
            assertTrue(day.getString("story").isNotBlank())
            if (!day.isNull("hadith")) assertTrue(day.getJSONObject("hadith").getString("source").isNotBlank())
            val arabic = day.getJSONObject("arabic")
            assertEquals(3, arabic.getJSONArray("words").length())
            arabic.getJSONArray("letters").let { a -> (0 until a.length()).forEach { letters += a.getString(it) } }
        }
        // Seven days cover the whole alphabet, each letter once.
        assertEquals(28, letters.size)
        assertEquals(28, letters.toSet().size)
    }

    @Test
    fun bothRemindersHaveTheirOwnAlarm() {
        val slots = PoliglotSlot.entries
        assertEquals(2, slots.size)
        assertEquals(2, slots.map { it.alarmRequest }.toSet().size)
        assertEquals(2, slots.map { it.notificationId }.toSet().size)
        // The old course's single reminder is the morning one.
        assertEquals("hour", PoliglotSlot.MORNING.hourKey)
    }
}
