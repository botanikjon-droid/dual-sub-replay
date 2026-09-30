package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpBackPillTest {
    private val rows = listOf(LaidOutRow(4, -30, 100), LaidOutRow(5, 75, 100), LaidOutRow(6, 180, 100), LaidOutRow(7, 285, 100))

    @Test
    fun rowsOnScreenAreVisibleAndOthersAreAboveOrBelow() {
        assertEquals(ActiveRowPlacement.VISIBLE, activeRowPlacement(5, rows, 0, 320))
        assertEquals(ActiveRowPlacement.VISIBLE, activeRowPlacement(4, rows, 0, 320))
        assertEquals(ActiveRowPlacement.ABOVE, activeRowPlacement(1, rows, 0, 320))
        assertEquals(ActiveRowPlacement.BELOW, activeRowPlacement(30, rows, 0, 320))
    }

    @Test
    fun aSliverAtTheEdgeStillCountsAsOffScreen() {
        // Row 7 shows 35 of its 100 pixels at the bottom; row 4 shows 20 at the top.
        assertEquals(ActiveRowPlacement.BELOW, activeRowPlacement(7, rows, 0, 320))
        assertEquals(ActiveRowPlacement.ABOVE, activeRowPlacement(4, rows, 50, 320))
    }

    @Test
    fun aRowTallerThanThePanelIsVisibleWhenItFillsHalfOfIt() {
        val tall = listOf(LaidOutRow(3, -500, 900))
        assertEquals(ActiveRowPlacement.VISIBLE, activeRowPlacement(3, tall, 0, 300))
    }

    @Test
    fun noActiveRowOrNoLayoutMeansNothingToJumpTo() {
        assertEquals(ActiveRowPlacement.NONE, activeRowPlacement(-1, rows, 0, 320))
        assertEquals(ActiveRowPlacement.NONE, activeRowPlacement(3, emptyList(), 0, 320))
    }

    @Test
    fun pillShowsOnlyWhileBrowsingWithTheSpokenRowOffScreen() {
        assertTrue(jumpBackPillVisible(ActiveRowPlacement.ABOVE, browsing = true))
        assertTrue(jumpBackPillVisible(ActiveRowPlacement.BELOW, browsing = true))
        assertFalse(jumpBackPillVisible(ActiveRowPlacement.BELOW, browsing = false))
        assertFalse(jumpBackPillVisible(ActiveRowPlacement.VISIBLE, browsing = true))
        assertFalse(jumpBackPillVisible(ActiveRowPlacement.NONE, browsing = true))
    }

    @Test
    fun clockMatchesYouTube() {
        assertEquals("0:09", formatPlaybackClock(9_400))
        assertEquals("12:05", formatPlaybackClock(725_000))
        assertEquals("1:02:03", formatPlaybackClock(3_723_000))
        assertEquals("0:00", formatPlaybackClock(-5))
    }
}
