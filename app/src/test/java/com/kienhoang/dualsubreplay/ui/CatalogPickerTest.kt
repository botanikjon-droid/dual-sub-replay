package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.YouTubeSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogPickerTest {
    private fun video(id: String) = YouTubeSearchResult(id, "Video $id", "Kanal", "10:00", "", "")

    @Test
    fun ticksSurviveWatchingAndTheSessionReopensOnce() {
        CatalogPicker.finish()
        CatalogPicker.begin("Homila skriningi")
        CatalogPicker.keep("fetal echo", listOf(video("aaaaaaaaaaa"), video("bbbbbbbbbbb")))
        CatalogPicker.toggle(video("aaaaaaaaaaa"))
        CatalogPicker.toggle(video("bbbbbbbbbbb"))
        CatalogPicker.toggle(video("aaaaaaaaaaa"))
        CatalogPicker.watch()
        assertEquals(CatalogEdit.Add("Homila skriningi"), CatalogPicker.takeResume())
        assertNull(CatalogPicker.takeResume())
        CatalogPicker.begin("Homila skriningi")
        assertEquals(
            listOf("bbbbbbbbbbb"),
            CatalogPicker.state.value.selected
                .map { it.videoId },
        )
        assertEquals("fetal echo", CatalogPicker.state.value.query)
        CatalogPicker.begin("Ginekologiya")
        assertEquals(emptyList<YouTubeSearchResult>(), CatalogPicker.state.value.selected)
        CatalogPicker.finish()
    }
}
