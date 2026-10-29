package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ApiUsageLogTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun entry(
        lesson: String = "abcdefghijk",
        input: Long = 100,
        success: Boolean = true,
        reported: Boolean = true,
        fallback: Boolean = false,
        cacheRead: Long = 0,
    ) = ApiUsageEntry(
        1L,
        lesson,
        "0-29",
        "DeepSeek",
        "deepseek-chat",
        input,
        40,
        cacheRead,
        0,
        reported,
        if (success) "200" else "402",
        fallback,
        success,
    )

    @Test
    fun entriesSurviveAWriteAndRead() {
        val file = ApiUsageFile(File(folder.root, "usage.jsonl"))
        file.append(entry())
        file.append(entry(success = false, reported = false, input = 0))
        assertEquals(listOf(entry(), entry(success = false, reported = false, input = 0)), file.readAll())
    }

    @Test
    fun aLineCutByACrashIsSkipped() {
        val target = File(folder.root, "usage.jsonl")
        target.writeText(apiUsageToJson(entry()) + "\n{\"t\": 5, \"provi")
        assertEquals(1, ApiUsageFile(target).readAll().size)
        assertNull(apiUsageFromJson("not json"))
    }

    @Test
    fun theOldestCallsAreDroppedPastTheLimit() {
        val file = ApiUsageFile(File(folder.root, "usage.jsonl"), maxEntries = 3)
        (1L..5L).forEach { file.append(entry(input = it)) }
        assertEquals(listOf(3L, 4L, 5L), file.readAll().map { it.inputTokens })
    }

    @Test
    fun totalsCountOneLessonAndNameUnknownTokens() {
        val entries =
            listOf(
                entry(input = 100, cacheRead = 900),
                entry(input = 0, success = false, reported = false),
                entry(input = 50, fallback = true),
                entry(lesson = "other000000", input = 1000),
            )
        val totals = apiUsageTotals(entries, "abcdefghijk")
        assertEquals(ApiUsageTotals(3, 1, 1, 150, 120, 900, 0, 1), totals)
        val line = apiUsageLine(totals)
        assertTrue(line, line.contains("3 so‘rov"))
        assertTrue(line, line.contains("keshdan o‘qildi 900"))
        assertTrue(line, line.contains("1 ta so‘rovda token soni noma’lum"))
        assertEquals(4, apiUsageTotals(entries).calls)
    }
}
