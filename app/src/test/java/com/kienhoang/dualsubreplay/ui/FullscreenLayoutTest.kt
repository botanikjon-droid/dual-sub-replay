package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullscreenLayoutTest {
    @Test
    fun fullScreenLeavesNoRoomForTheSubtitlePanel() {
        assertTrue(isPanelPlaceFree(fullscreen = false, nativeDialogVisible = false, sideBySide = false, activeVideoId = "abcdefghijk"))
        // The app's own full screen gives the whole screen to the video (the subtitles go on it as a line).
        assertFalse(isPanelPlaceFree(fullscreen = true, nativeDialogVisible = false, sideBySide = false, activeVideoId = "abcdefghijk"))
        assertFalse(isPanelPlaceFree(fullscreen = false, nativeDialogVisible = false, sideBySide = true, activeVideoId = "abcdefghijk"))
        assertFalse(isPanelPlaceFree(fullscreen = false, nativeDialogVisible = false, sideBySide = false, activeVideoId = null))
    }
}
