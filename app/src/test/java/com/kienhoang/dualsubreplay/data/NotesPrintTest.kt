package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesPrintTest {
    @Test
    fun lessonsPrintOldestFirstThenUnlisted() {
        val records =
            listOf(
                LessonRecord("bbbbbbbbbbb", "Kidney", "DeepL", 2_000L, 1),
                LessonRecord("aaaaaaaaaaa", "Liver", "Gemini", 1_000L, 1),
            )
        assertEquals(
            listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"),
            printOrder(listOf("ccccccccccc", "bbbbbbbbbbb", "aaaaaaaaaaa"), records),
        )
    }

    @Test
    fun aLessonPrintsPicturesNotesTermsQuizAndAnswers() {
        val notes =
            LessonNotes(
                summary = listOf("Normal o‘t yo‘li 6 mm dan kam."),
                terms = listOf(NoteTerm("common bile duct", "umumiy o‘t yo‘li")),
                quiz = listOf(QuizQuestion("Normal?", listOf("<6 mm", "<10 mm", "<15 mm", "<20 mm"), 1, "Ma’ruzada.")),
                frames = listOf(NoteFrame(754, "Devor", "754.jpg"), NoteFrame(800, "Olinmagan")),
            )
        val blocks = notesPrintBlocks(listOf(PrintableLesson("aaaaaaaaaaa", "Liver", notes)), "1 ta dars")
        assertTrue(blocks.first() is PrintBlock.BookTitle)
        assertEquals(listOf(PrintBlock.Picture("aaaaaaaaaaa", "754.jpg", "12:34 · Devor")), blocks.filterIsInstance<PrintBlock.Picture>())
        val texts = blocks.filterIsInstance<PrintBlock.Paragraph>().map { it.text }
        assertTrue("b) <10 mm" in texts)
        assertEquals("1-b — Ma’ruzada.", texts.last())
        assertTrue(blocks.any { it == PrintBlock.Heading("Javoblar") })
    }
}
