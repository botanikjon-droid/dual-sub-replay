package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UziSuggestionsTest {
    private fun row(
        section: String,
        id: String,
    ) = CatalogRow(section, "Mavzu", CatalogVideo(id, "Video $id"), 4)

    @Test
    fun aSectionListsOnlyItsUnseenSuggestions() {
        val all = listOf(row("Homila skriningi", "aaaaaaaaaaa"), row("Homila skriningi", "bbbbbbbbbbb"), row("Ginekologiya", "ccccccccccc"))
        val catalog = listOf(CatalogSection("Homila skriningi", listOf(CatalogTopic("T", listOf(CatalogVideo("aaaaaaaaaaa", "x"))))))
        assertEquals(emptyList<CatalogRow>(), sectionSuggestions(all, "Homila skriningi", catalog, setOf("bbbbbbbbbbb")))
        assertEquals(listOf("bbbbbbbbbbb"), sectionSuggestions(all, "Homila skriningi", catalog, emptySet()).map { it.video.videoId })
    }

    @Test
    fun theSuggestionAssetIsValidAndNotInTheCatalog() {
        val suggestions = parseUziCatalogRows(File("src/main/assets/$UZI_SUGGESTIONS_ASSET").readText(Charsets.UTF_8))
        val catalog =
            parseUziCatalogRows(
                File("src/main/assets/$UZI_CATALOG_ASSET").readText(Charsets.UTF_8),
            ).mapTo(HashSet()) { it.video.videoId }
        assertEquals(42, suggestions.size)
        assertTrue(suggestions.none { it.video.videoId in catalog })
    }
}
