package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UziGlossaryTest {
    private val parsed = parseUziGlossary(File("src/main/assets/$UZI_GLOSSARY_ASSET").readText())
    private val glossary = UziGlossary(parsed.entries)

    private fun terms(text: String) = glossary.findTerms(text).map { it.entry.english }

    private fun entry(english: String) = parsed.entries.single { it.english == english }

    @Test
    fun assetHoldsEveryReviewedTerm() {
        assertEquals(emptyList<String>(), parsed.errors)
        assertEquals(228, parsed.entries.size)
        assertEquals(171, parsed.entries.count { it.status == GlossaryStatus.APPROVED })
        assertEquals(56, parsed.entries.count { it.status == GlossaryStatus.CORRECTED })
        assertEquals(1, parsed.entries.count { it.status == GlossaryStatus.NEW })
        assertEquals(20, parsed.entries.count { it.general })
        assertEquals((1..228).toList(), parsed.entries.map { it.id })
        assertTrue(parsed.entries.all { it.uzbek.isNotBlank() })
    }

    @Test
    fun correctedRowsUseTheDoctorsValues() {
        assertEquals("echogenlik", entry("echogenicity").uzbek)
        assertEquals("rezistivlik indeksi (RI)", entry("resistive index").uzbek)
        assertEquals("(PSV − EDV) / PSV.", entry("resistive index").note)
        assertEquals("vaqt bo‘yicha kuchaytirishni kompensatsiya qilish (TGC)", entry("time gain compensation").uzbek)
        assertEquals("to‘lqin siljish elastografiyasi (SWE)", entry("shear wave elastography").uzbek)
    }

    @Test
    fun emptyCorrectionCellsKeepTheApprovedDraft() {
        // Row 70 "cirrhosis" is "Tuzatildi" with the same Uzbek; row 82 is approved with an empty note.
        assertEquals("sirroz", entry("cirrhosis").uzbek)
        assertEquals("umumiy o‘t yo‘li (xoledox)", entry("common bile duct").uzbek)
        assertEquals("", entry("common bile duct").note)
    }

    @Test
    fun keepsTheGeneralFlag() {
        assertTrue(entry("liver").general)
        assertTrue(entry("lesion").general)
        assertFalse(entry("nodule").general)
        assertEquals(emptyList<String>(), terms("The liver is normal."))
        assertEquals("liver", glossary.lookup("liver")?.english)
    }

    @Test
    fun hepaticAndPortalVeinsStayApart() {
        assertEquals(listOf("hepatic vein", "portal vein"), terms("Compare the hepatic veins with the portal vein."))
        assertEquals("jigar venasi", glossary.lookup("hepatic vein")?.uzbek)
        assertEquals("darvoza venasi", glossary.lookup("Portal Vein")?.uzbek)
        assertNotEquals(glossary.lookup("hepatic vein"), glossary.lookup("portal vein"))
    }

    @Test
    fun longestTermWins() {
        assertEquals(listOf("main portal vein"), terms("Measure the main portal vein diameter."))
        assertEquals(listOf("shear wave elastography"), terms("Shear-wave elastography of the liver"))
        assertEquals(listOf("intrahepatic bile duct", "dilatation"), terms("intrahepatic bile duct dilatation is absent"))
    }

    @Test
    fun lesionMassNoduleAndTumorStayDistinct() {
        val uzbek = listOf("lesion", "mass", "nodule", "tumor").map { glossary.lookup(it)?.uzbek }
        assertEquals(listOf("o‘choq", "hosila (massa)", "tugun", "o‘sma"), uzbek)
        assertEquals(4, uzbek.toSet().size)
    }

    @Test
    fun echogenicityTermsAreDifferent() {
        val uzbek = listOf("echogenicity", "hypoechoic", "hyperechoic", "anechoic", "isoechoic").map { glossary.lookup(it)?.uzbek }
        assertEquals(listOf("echogenlik", "gipoexogen", "giperexogen", "anexogen", "izoexogen"), uzbek)
        assertEquals(listOf("echogenic focus"), terms("A small echogenic focus is seen."))
    }

    @Test
    fun dopplerKindsKeepTheirMeaning() {
        assertEquals(
            listOf("color Doppler", "power Doppler", "spectral Doppler"),
            terms("Use color Doppler, then power Doppler and spectral Doppler."),
        )
        assertEquals("energetik (power) Doppler", glossary.lookup("power Doppler")?.uzbek)
        assertEquals("color Doppler", glossary.lookup("colour Doppler")?.english)
    }

    @Test
    fun acronymsMatchOnlyInCapitals() {
        assertEquals(listOf("time gain compensation", "resistive index", "SWE"), terms("Adjust the TGC; the RI and SWE values"))
        assertEquals("SWE", glossary.lookup("SWE")?.english)
        // "ri" and "la" in ordinary text are not acronyms.
        assertEquals(emptyList<String>(), terms("la ri swe"))
        assertEquals("TI-RADS", glossary.lookup("TI-RADS")?.english)
    }

    @Test
    fun pluralsPossessivesAndPunctuation() {
        assertEquals(listOf("thyroid nodule"), terms("Thyroid nodules, (multiple)."))
        assertEquals(listOf("common bile duct"), terms("the common bile duct's diameter"))
        assertEquals(listOf("kidney stone"), terms("Kidney stones?"))
    }

    @Test
    fun termAtFindsTheTermAroundATappedWord() {
        val text = "The common bile duct is 4 mm."
        val start = text.indexOf("bile")
        assertEquals("common bile duct", glossary.termAt(text, start, start + 4)?.entry?.english)
        val unit = text.indexOf(" mm") + 1
        assertNull(glossary.termAt(text, unit, unit + 2))
    }

    @Test
    fun checksWhetherTheTranslationUsedTheApprovedTerm() {
        val checks =
            checkGlossaryTerms(
                glossary,
                "The portal vein and the common bile duct are normal.",
                "Darvoza venasining diametri va umumiy o’t yo‘li normal.",
            )
        assertEquals(listOf(true, true), checks.map { it.approvedTermUsed })
        val wrong = checkGlossaryTerms(glossary, "A hypoechoic lesion", "Gipoekoik shikastlanish")
        assertEquals(listOf(false), wrong.map { it.approvedTermUsed })
        assertEquals(listOf(null), checkGlossaryTerms(glossary, "hypoechoic", null).map { it.approvedTermUsed })
        // The alternative in parentheses also counts: "xoledox".
        assertTrue(translationUsesApprovedTerm(entry("common bile duct"), "Xoledox kengaymagan"))
        assertTrue(translationUsesApprovedTerm(entry("hepatic vein"), "jigar venalarida oqim"))
        assertFalse(translationUsesApprovedTerm(entry("hepatic vein"), "darvoza venasi"))
    }

    @Test
    fun parserSkipsBrokenLinesWithoutBlankingOthers() {
        val header = "id\tcategory\tenglish\taliases\tuzbek\tnote\tstatus\tgeneral"
        val result =
            parseUziGlossary(
                "# comment\n$header\n1\tc\tliver\t\tjigar\t\tapproved\t1\n2\tc\tbad\t\t\t\tapproved\t0\n" +
                    "3\tc\tx\t\ty\t\tunreviewed\t0\n4\tc\tshort\n",
            )
        assertEquals(listOf("liver"), result.entries.map { it.english })
        assertEquals(3, result.errors.size)
        assertEquals(listOf("missing header"), parseUziGlossary("").errors)
    }

    @Test
    fun appliesOnlyFromEnglishToUzbek() {
        assertTrue(glossaryApplies("en", "uz"))
        assertTrue(glossaryApplies("auto", "uz"))
        assertTrue(glossaryApplies(null, "uz"))
        assertFalse(glossaryApplies("en", "vi"))
        assertFalse(glossaryApplies("ru", "uz"))
    }
}

class UziGlossaryRowTest {
    private val glossary = UziGlossary(parseUziGlossary(File("src/main/assets/$UZI_GLOSSARY_ASSET").readText()).entries)

    @Test
    fun aTermCutBetweenRowsIsListedOnceUnderItsFirstRow() {
        val text = "Look at the common bile duct and the portal vein."
        val cut = text.indexOf("duct")
        val rows =
            listOf(
                SubtitleSegment(1, 0, 1000, text.substring(0, cut).trim(), "…", sentence = SentenceSlice(text, listOf(cut), 0)),
                SubtitleSegment(2, 1000, 2000, text.substring(cut).trim(), "…", sentence = SentenceSlice(text, listOf(cut), 1)),
            )
        val first = glossaryChecksForRow(glossary, rows[0])
        val second = glossaryChecksForRow(glossary, rows[1])
        assertEquals(listOf("common bile duct"), first.map { it.match.entry.english })
        assertEquals(listOf("portal vein"), second.map { it.match.entry.english })
        // A slice of a longer translation is never judged.
        assertEquals(listOf(null), first.map { it.approvedTermUsed })
    }

    @Test
    fun aWholeSentenceRowIsCheckedAgainstItsTranslation() {
        val row = SubtitleSegment(1, 0, 1000, "A hypoechoic nodule.", "Gipoexogen tugun.")
        assertEquals(listOf(true, true), glossaryChecksForRow(glossary, row).map { it.approvedTermUsed })
    }
}

class GlossaryGuidedSourceTest {
    private val glossary = UziGlossary(parseUziGlossary(File("src/main/assets/$UZI_GLOSSARY_ASSET").readText()).entries)

    @Test
    fun approvedTermsReplaceEnglishTermsOnly() {
        assertEquals(
            "I'm going to start by using the ca1 to 7 datchik",
            glossaryGuidedSource(glossary, "I'm going to start by using the ca1 to 7 transducer"),
        )
        assertEquals(
            "Compare the jigar venasi with the darvoza venasi.",
            glossaryGuidedSource(glossary, "Compare the hepatic veins with the portal vein."),
        )
        // Common words (liver, lesion) and text without terms are left alone.
        assertEquals("The liver has a lesion.", glossaryGuidedSource(glossary, "The liver has a lesion."))
        assertEquals(
            "A gipoexogen tugun near the umumiy o‘t yo‘li.",
            glossaryGuidedSource(glossary, "A hypoechoic nodule near the common bile duct."),
        )
    }

    @Test
    fun primaryTermDropsParenthesesAndAlternatives() {
        assertEquals("umumiy o‘t yo‘li", uzbekPrimaryTerm("umumiy o‘t yo‘li (xoledox)"))
        assertEquals("polikistik tuxumdonlar", uzbekPrimaryTerm("polikistik tuxumdonlar / PCOS"))
        assertEquals("energetik Doppler", uzbekPrimaryTerm("energetik (power) Doppler"))
    }
}
