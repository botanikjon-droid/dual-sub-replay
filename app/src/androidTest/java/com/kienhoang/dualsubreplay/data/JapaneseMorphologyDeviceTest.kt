package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kuromoji reads its dictionary as Java resources; this proves that works inside the APK on ART. */
class JapaneseMorphologyDeviceTest {
    @Test
    fun analyzerLoadsFromTheApkAndGroupsWholeWords() {
        assertTrue("Kuromoji should load on device", JapaneseMorphology.awaitReady(timeoutMs = 60_000))

        val words = LanguageAwareTokenizer.tokenize("毎日お母さんに手伝ってもらって、", "ja").map { it.text }

        assertEquals(listOf("毎日", "お母さん", "に", "手伝ってもらって", "、"), words)
    }
}
