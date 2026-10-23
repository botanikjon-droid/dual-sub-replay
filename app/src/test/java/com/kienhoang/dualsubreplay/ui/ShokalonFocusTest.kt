package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.CatalogSection
import com.kienhoang.dualsubreplay.data.CatalogTopic
import com.kienhoang.dualsubreplay.data.CatalogVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShokalonFocusTest {
    private val lessons = setOf("x6hXhLNc-jU")
    private val isLesson: (String) -> Boolean = { it in lessons }

    @Test
    fun lessonsAndTheHomePageStay() {
        assertEquals(FocusVerdict.ALLOW, focusVerdict("https://m.youtube.com/watch?v=x6hXhLNc-jU", isLesson))
        assertEquals(FocusVerdict.ALLOW, focusVerdict("https://m.youtube.com/", isLesson))
        assertEquals(FocusVerdict.ALLOW, focusVerdict("https://m.youtube.com/account", isLesson))
    }

    @Test
    fun otherVideosAndListingPagesAreLeft() {
        assertEquals(FocusVerdict.BLOCK, focusVerdict("https://m.youtube.com/watch?v=abcdefghijk", isLesson))
        assertEquals(FocusVerdict.BLOCK, focusVerdict("https://m.youtube.com/shorts/x6hXhLNc-jU", isLesson))
        assertEquals(FocusVerdict.BLOCK, focusVerdict("https://m.youtube.com/results?search_query=liver", isLesson))
        assertEquals(FocusVerdict.BLOCK, focusVerdict("https://m.youtube.com/@SonoNerds", isLesson))
        assertEquals(FocusVerdict.BLOCK, focusVerdict("https://m.youtube.com/feed/subscriptions", isLesson))
    }

    @Test
    fun catalogLookupKnowsNothingBeforeLoading() {
        val sections = listOf(CatalogSection("Fizika", listOf(CatalogTopic("Tovush", listOf(CatalogVideo("x6hXhLNc-jU", "Sound"))))))
        assertEquals(true, catalogHasVideo(sections, "x6hXhLNc-jU"))
        assertEquals(false, catalogHasVideo(sections, "abcdefghijk"))
        assertNull(catalogHasVideo(null, "abcdefghijk"))
    }

    @Test
    fun theStyleHidesRelatedVideosAndShorts() {
        val css = focusModeCss()
        assertTrue(css.contains("ytm-item-section-renderer[section-identifier=\"related-items\"]"))
        assertTrue(css.contains("ytm-reel-shelf-renderer"))
        assertTrue(css.endsWith("{ display: none !important; }"))
        assertTrue(focusModeScript(enabled = true).contains(FOCUS_STYLE_ID))
    }

    @Test
    fun stepsStayInsideTheSection() {
        val sections =
            listOf(
                CatalogSection(
                    "Fizika",
                    listOf(
                        CatalogTopic("Tovush", listOf(CatalogVideo("aaaaaaaaaaa", "A"), CatalogVideo("bbbbbbbbbbb", "B"))),
                        CatalogTopic("Datchik", listOf(CatalogVideo("ccccccccccc", "C"))),
                    ),
                ),
                CatalogSection("Jigar", listOf(CatalogTopic("Kirish", listOf(CatalogVideo("ddddddddddd", "D"))))),
            )
        val middle = lessonSteps(sections, "bbbbbbbbbbb")!!
        assertEquals("aaaaaaaaaaa", middle.previous?.videoId)
        assertEquals("ccccccccccc", middle.next?.videoId)
        assertEquals(2, middle.position)
        assertEquals(3, middle.total)
        val last = lessonSteps(sections, "ccccccccccc")!!
        assertNull(last.next)
        assertNull(lessonSteps(sections, "ddddddddddd")!!.previous)
        assertNull(lessonSteps(sections, "eeeeeeeeeee"))
        assertNull(lessonSteps(null, "aaaaaaaaaaa"))
    }

    @Test
    fun stepScriptCarriesTheNeighbours() {
        val steps = LessonSteps("bbbbbbbbbbb", "Fizika", 2, 3, CatalogVideo("aaaaaaaaaaa", "A"), null)
        val script = focusStepScript(steps)
        assertTrue(script.contains("active: true, prev: \"aaaaaaaaaaa\", next: \"\""))
        assertTrue(focusStepScript(null).contains("active: false"))
    }
}
