package com.kienhoang.dualsubreplay.dubbing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LessonVoicePrefetchTest {
    @Test
    fun linesAreDistinctNonBlankAndInOrder() {
        assertEquals(listOf("a", "b"), prefetchLines(listOf(" a ", "", "b", "a")))
    }

    @Test
    fun summarySaysWhatHappened() {
        assertTrue(prefetchSummary(VoicePrefetchState(done = 10, total = 10), false, null).contains("10 gap"))
        assertTrue(prefetchSummary(VoicePrefetchState(done = 10, total = 10, failed = 2), false, null).contains("8 / 10"))
        assertTrue(prefetchSummary(VoicePrefetchState(done = 5, total = 10, failed = 5), true, "403").contains("to‘xtatildi"))
    }
}
