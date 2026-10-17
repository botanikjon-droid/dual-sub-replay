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
            provider = "deepl",
            geminiWholeLesson = true,
            geminiKey = true,
            deeplKey = true,
        )

    @Test
    fun storedTranslationNamesItsEngine() {
        assertEquals(
            "✓ Tarjima: DeepL · saqlangan, qayta so‘rov sarflanmaydi",
            translatorLabel(base.copy(importedCues = 300, storedEngine = "DeepL")),
        )
        assertTrue(translatorLabel(base.copy(importedCues = 300, storedEngine = "Fayl")).contains("fayldan"))
        assertTrue(translatorLabel(base.copy(importedCues = 300)).contains("saqlangan tarjima"))
    }

    @Test
    fun aRunningLessonShowsProgressAndTheStandIn() {
        val run = ClaudeRunState(videoId = "abcdefghijk", engine = LessonEngine.DEEPL, running = true, done = 100, total = 400)
        assertEquals("⏳ DeepL butun darsni tarjima qilmoqda 100/400 · hozircha: Google Translate", translatorLabel(base.copy(run = run)))
    }

    @Test
    fun liveTranslatorIsNamed() {
        assertEquals("Tarjima: Google Translate", translatorLabel(base))
        assertEquals("Tarjima: DeepL (gapma-gap)", translatorLabel(base.copy(targetLanguage = "ru")))
        assertEquals("Tarjima: Gemini (gapma-gap)", translatorLabel(base.copy(provider = "gemini", geminiWholeLesson = false)))
    }
}
