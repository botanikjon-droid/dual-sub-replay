package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AsrFixesTest {
    private val shipped = parseAsrFixes(File("src/main/assets/$UZI_ASR_FIXES_ASSET").readText())

    @Test
    fun indianAccentMishearingsAreFixedBeforeTranslation() {
        val fixer = AsrFixer(shipped)
        assertEquals(
            "activate the spectral Doppler to measure the pulsatility index",
            fixer.fix("activate the spectral doubler to measure the pulse ability index"),
        )
        assertEquals("we can insonate by color Doppler", fixer.fix("we can insinuate by Color Doubler"))
        assertEquals(
            "a 2 MHz phased array probe above the zygomatic arch",
            fixer.fix("a 2 mahz phase R probe above the zygote  matic arch"),
        )
    }

    @Test
    fun onlyWholeWordsChange() {
        val fixer = AsrFixer(listOf(AsrFix("doubler", "Doppler")))
        assertEquals("redoubler doublers", fixer.fix("redoubler doublers"))
        assertEquals("Doppler.", fixer.fix("doubler."))
        assertEquals("no change here", AsrFixer(emptyList()).fix("no change here"))
    }

    @Test
    fun theDoctorsOwnPairsWin() {
        val merged = mergeAsrFixes(listOf(AsrFix("doubler", "Doppler")), listOf(AsrFix("Doubler", "Doppler ultrasound")))
        assertEquals(listOf(AsrFix("Doubler", "Doppler ultrasound")), merged)
        assertEquals(merged, parseAsrFixes(formatAsrFixes(merged)))
        assertTrue(shipped.any { it.heard == "doubler" && it.correct == "Doppler" })
    }
}
