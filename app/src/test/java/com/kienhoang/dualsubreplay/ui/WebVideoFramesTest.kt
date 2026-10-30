package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebVideoFramesTest {
    @Test
    fun frameScriptsReverifyTheYouTubeOrigin() {
        listOf(webSeekPausedScript(12f), webSeekPausedScript(null), WEB_VIDEO_RECT_SCRIPT).forEach { script ->
            assertTrue(script.contains("window.location.protocol !== 'https:'"))
            assertTrue(script.contains("host.endsWith('.youtube.com')"))
        }
        assertTrue(webSeekPausedScript(12.5f).contains("video.currentTime = 12.5;"))
        assertFalse(webSeekPausedScript(null).contains("currentTime ="))
    }

    @Test
    fun videoBoxIsInDevicePixels() {
        val box = parseVideoBox("""{"x":0,"y":56,"w":360,"h":202.5,"dpr":3,"ready":true,"t":754.6}""")
        assertEquals(VideoBox(left = 0, top = 168, width = 1080, height = 608, ready = true, seconds = 754), box)
        assertNull(parseVideoBox("null"))
        assertNull(parseVideoBox("""{"x":0,"y":0,"w":0,"h":0,"dpr":3}"""))
    }

    @Test
    fun blackFramesAreRecognised() {
        assertTrue(looksBlack(IntArray(225) { 0xFF000000.toInt() }))
        val ultrasound = IntArray(225) { if (it % 7 == 0) 0xFFB0B0B0.toInt() else 0xFF050505.toInt() }
        assertFalse(looksBlack(ultrasound))
    }
}
