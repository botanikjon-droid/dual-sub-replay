package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslatorBadgeTest {
    private val base =
        TranslatorFacts(
            activeVideoId = "abcdefghijk",
            targetLanguage = "uz",
            importedCues = 0,
            storedEngine = null,
            run = ClaudeRunState(),
            provider = "claude",
            geminiWholeLesson = true,
            geminiKey = true,
        )

    @Test
    fun storedTranslationNamesItsEngine() {
        assertEquals(
            "✓ Tarjima: Claude · saqlangan, qayta so‘rov sarflanmaydi",
            translatorLabel(base.copy(importedCues = 300, storedEngine = "Claude")),
        )
        assertTrue(translatorLabel(base.copy(importedCues = 300, storedEngine = "Fayl")).contains("fayldan"))
        assertTrue(translatorLabel(base.copy(importedCues = 300)).contains("saqlangan tarjima"))
    }

    @Test
    fun aRunningLessonShowsProgressAndTheStandIn() {
        val run = ClaudeRunState(videoId = "abcdefghijk", engine = LessonEngine.CLAUDE, running = true, done = 100, total = 400)
        assertEquals("⏳ Claude butun darsni tarjima qilmoqda 100/400 · hozircha: Google Translate", translatorLabel(base.copy(run = run)))
    }

    @Test
    fun liveTranslatorIsNamed() {
        assertEquals("Tarjima: Google Translate", translatorLabel(base))
        assertEquals("Tarjima: Google Translate", translatorLabel(base.copy(targetLanguage = "ru")))
        assertEquals("Tarjima: Gemini (gapma-gap)", translatorLabel(base.copy(provider = "gemini", geminiWholeLesson = false)))
    }
}
