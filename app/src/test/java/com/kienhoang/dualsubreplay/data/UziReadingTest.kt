package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UziReadingTest {
    private val reading = parseUziReading(File("src/main/assets/$UZI_READING_ASSET").readText())

    @Test
    fun everyCatalogSectionHasCheckedReading() {
        val sections = parseUziCatalog(File("src/main/assets/uzi_catalog.tsv").readText()).map { it.name }
        assertTrue(sections.isNotEmpty())
        sections.forEach { section -> assertTrue("no reading for $section", reading[section].orEmpty().isNotEmpty()) }
        assertTrue(reading.values.flatten().all { it.url.startsWith("https://") && it.title.isNotBlank() && it.source.isNotBlank() })
    }

    @Test
    fun searchWordsComeFromTheTitleWithoutNoise() {
        assertEquals(
            "Carotid Duplex Interpretations ultrasound",
            readingSearchQuery("Carotid Duplex Interpretations (Zsolt Garami, MD) January 18, 2022"),
        )
        assertEquals(
            "Varicose veins assessment GSV anatomy assessment ultrasound",
            readingSearchQuery("Part 1: Varicose veins ultrasound assessment: GSV anatomy assessment"),
        )
        assertEquals("Neonatal Head Spine ultrasound", readingSearchQuery("Ultrasound of the Neonatal Head and Spine | GE Healthcare"))
    }

    @Test
    fun lessonSearchesAreEncodedHttpsLinks() {
        val links = lessonSearchLinks("Liver Doppler Ultrasound")
        assertEquals(3, links.size)
        assertTrue(links.all { it.url.startsWith("https://") && !it.url.contains(' ') })
        assertTrue(links[0].url.contains("radiopaedia.org/search?q=Liver+Doppler+ultrasound"))
        assertTrue(links[2].url.contains("filter=pubt.review"))
    }

    @Test
    fun lessonInfoComesFromTheCatalog() {
        val sections =
            listOf(
                CatalogSection("Transkranial doppler", listOf(CatalogTopic("Kirish", listOf(CatalogVideo("abcdefghijk", "TCD basics"))))),
            )
        assertEquals(LessonInfo("TCD basics", "Transkranial doppler", "Kirish"), lessonInfoOf(sections, "abcdefghijk", null))
        assertEquals(LessonInfo("Other", "", ""), lessonInfoOf(sections, "zzzzzzzzzzz", "Other"))
        assertNull(lessonInfoOf(sections, "zzzzzzzzzzz", null))
        assertEquals("Transkranial doppler", catalogPlaceOf(sections, "abcdefghijk")?.first)
    }
}
