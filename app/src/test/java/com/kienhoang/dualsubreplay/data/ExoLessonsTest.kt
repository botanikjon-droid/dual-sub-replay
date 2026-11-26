package com.kienhoang.dualsubreplay.data

import com.kienhoang.dualsubreplay.ui.exoQuestionLines
import com.kienhoang.dualsubreplay.ui.jetTowardsProbe
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExoLessonsTest {
    private val course by lazy { parseExoCourse(File("src/main/assets/$EXO_LESSONS_ASSET").readText(Charsets.UTF_8)) }

    @Test
    fun shippedCourseHasItsModulesAndLessons() {
        assertEquals(listOf("views", "defects", "basics"), course.modules.map { it.id })
        assertTrue(course.lessons.size >= 30)
        assertEquals(
            course.lessons.size,
            course.lessons
                .map { it.id }
                .toSet()
                .size,
        )
        assertTrue(course.lessons.all { it.paragraphs.isNotEmpty() && it.questions.isNotEmpty() })
    }

    @Test
    fun everyQuestionHasFourDistinctOptionsAndAValidAnswer() {
        course.lessons.flatMap { it.questions }.forEach { question ->
            assertEquals(question.question, 4, question.options.toSet().size)
            assertTrue(question.question, question.answer in question.options.indices)
        }
    }

    @Test
    fun everyPictureRefersToAKnownViewAndDefect() {
        course.lessons.forEach { lesson ->
            lesson.view?.let { assertNotNull(lesson.id, course.views[it]) }
            lesson.defect?.let { defect ->
                val overlay = course.defects[defect]
                assertNotNull(lesson.id, overlay)
                assertEquals(lesson.id, lesson.view, overlay?.view)
            }
        }
        course.defects.values.forEach { overlay -> overlay.jets.forEach { assertEquals(4, it.size) } }
    }

    @Test
    fun defectChangesApplyOverTheViewShape() {
        val shape = parseExoShape(JSONObject("""{"k":"e","id":"LV","x":250,"rx":46,"lab":"LV","tis":true}"""))
        val change = parseExoShape(JSONObject("""{"rx":16,"lab":"mRV"}"""))
        val changed = shape.with(change)
        assertEquals("e", changed.kind)
        assertEquals(16f, changed.n("rx"))
        assertEquals(250f, changed.n("x"))
        assertEquals("mRV", changed.text("lab"))
        assertTrue(changed.has("tis"))
        assertFalse(changed.has("cav"))
    }

    @Test
    fun avCanalOverlayUsesTheCommonValve() {
        assertTrue(course.defects.getValue("avsd").commonValve)
        assertFalse(course.defects.getValue("asd2").commonValve)
    }

    @Test
    fun jetColourFollowsTheProbeAtTheApex() {
        // Upwards on the screen, towards the apex probe: red; downwards: blue.
        assertTrue(jetTowardsProbe(listOf(200f, 200f, 200f, 100f)))
        assertFalse(jetTowardsProbe(listOf(200f, 100f, 200f, 200f)))
    }

    @Test
    fun spokenQuestionGivesOptionsAPauseAndTheAnswer() {
        val question = ExoQuestion("Qaysi kesim?", listOf("A1", "B1", "C1", "D1"), 2, "Sababi shu.")
        val lines = exoQuestionLines(3, question)
        assertEquals("Savol 3. Qaysi kesim?", lines.first().text)
        assertTrue(lines.any { it.text == null })
        assertTrue(lines.any { it.text?.contains("C1") == true && it.text.startsWith("To") })
        assertEquals("Sababi shu.", lines.mapNotNull { it.text }.last())
    }
}
