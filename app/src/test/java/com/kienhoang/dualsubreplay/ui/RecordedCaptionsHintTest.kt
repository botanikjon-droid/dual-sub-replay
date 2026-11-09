package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.RecordingSummary
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordedCaptionsHintTest {
    private val partial = RecordingSummary(cues = 37, coveredMs = 372_000, ranges = listOf(0L..372_000L), complete = false, language = "en")

    @Test
    fun theCardAppearsOnlyForVideosWithoutADownloadableTrackOrWithARecording() {
        assertNull(recordedCaptionsHint(null, liveFallback = false))
        assertTrue(recordedCaptionsHint(null, liveFallback = true)!!.contains("yozib boriladi"))
        assertTrue(recordedCaptionsHint(partial, liveFallback = true)!!.startsWith("Yozildi: 37 qator. Ko‘rilgan qismlar: 0:00–6:12."))
        assertTrue(recordedCaptionsHint(partial.copy(complete = true), liveFallback = false)!!.contains("ekrandan yozib olingan: 37 qator"))
    }

    @Test
    fun theLiveStatusLineShowsOnlyWhileRecording() {
        assertTrue(recordingStatusLine(partial)!!.contains("37 qator"))
        assertNull(recordingStatusLine(partial.copy(complete = true)))
        assertNull(recordingStatusLine(null))
    }
}
