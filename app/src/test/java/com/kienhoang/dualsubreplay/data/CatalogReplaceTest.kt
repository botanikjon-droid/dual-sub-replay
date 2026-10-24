package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogReplaceTest {
    private val catalog =
        listOf(
            CatalogSection(
                "Ginekologiya",
                listOf(
                    CatalogTopic(
                        "Tuxumdon kistalari",
                        listOf(CatalogVideo("vSba7T95CXQ", "Adnexal cysts"), CatalogVideo("Bg2LHJoIgao", "Hindi lecture")),
                    ),
                ),
            ),
            CatalogSection("Urologiya", listOf(CatalogTopic("Buyrak", listOf(CatalogVideo("aaaaaaaaaaa", "Kidney"))))),
        )

    @Test
    fun theReplacementTakesTheSamePlace() {
        val result = replaceCatalogVideo(catalog, "Bg2LHJoIgao", CatalogVideo("bbbbbbbbbbb", "Ovarian cysts (English)"))
        assertEquals(
            listOf("vSba7T95CXQ", "bbbbbbbbbbb"),
            result[0].topics[0].videos.map { it.videoId },
        )
        assertEquals("Ovarian cysts (English)", result[0].topics[0].videos[1].title)
    }

    @Test
    fun aReplacementAlreadyElsewhereIsMoved() {
        val result = replaceCatalogVideo(catalog, "Bg2LHJoIgao", CatalogVideo("aaaaaaaaaaa", "Kidney"))
        assertEquals(listOf("vSba7T95CXQ", "aaaaaaaaaaa"), result[0].topics[0].videos.map { it.videoId })
        assertEquals(listOf("Ginekologiya"), result.map { it.name })
    }
}
