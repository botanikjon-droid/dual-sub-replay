package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UziCatalogTest {
    @Test
    fun rowsBecomeSectionsAndTopicsInFileOrder() {
        val text =
            listOf(
                "# comment",
                "section\ttopic\tvideo_id\ttitle",
                "Ginekologiya\tMioma\tveQhbLyqLyU\tFibroids",
                "Urologiya\tBuyrak\tgcGHNdrgYW8\tNormal kidney",
                "Ginekologiya\tMioma\tqdlb0bl2eOI\tPolyps",
                "Ginekologiya\tO-RADS\tji2I3Pv1R0g\t",
            ).joinToString("\n")

        val sections = parseUziCatalog(text)

        assertEquals(listOf("Ginekologiya", "Urologiya"), sections.map { it.name })
        assertEquals(listOf("Mioma", "O-RADS"), sections[0].topics.map { it.name })
        assertEquals(listOf("veQhbLyqLyU", "qdlb0bl2eOI"), sections[0].topics[0].videos.map { it.videoId })
        assertEquals(
            "ji2I3Pv1R0g",
            sections[0]
                .topics[1]
                .videos
                .single()
                .title,
        )
        assertEquals(3, sections[0].videoCount)
    }

    @Test
    fun badRowsAndRepeatedVideosAreSkipped() {
        val text =
            listOf(
                "A\tT\tshort\tbad id",
                "A\tT\tveQhbLyqLyU",
                "\tT\tveQhbLyqLyU\tno section",
                "A\tT\tveQhbLyqLyU\tfirst",
                "B\tT\tveQhbLyqLyU\trepeat",
            ).joinToString("\n")

        val sections = parseUziCatalog(text)

        assertEquals(1, sections.size)
        assertEquals(
            "first",
            sections
                .single()
                .topics
                .single()
                .videos
                .single()
                .title,
        )
    }

    @Test
    fun shippedCatalogHasTheElevenSectionsInOrder() {
        val text = File("src/main/assets/$UZI_CATALOG_ASSET").readText(Charsets.UTF_8)
        val rows = text.lines().count { it.isNotBlank() && !it.startsWith("#") } - 1

        val sections = parseUziCatalog(text)

        assertEquals(
            listOf(
                "Qorin bo‘shlig‘i a’zolari",
                "Ginekologiya",
                "Urologiya",
                "Neyrosonografiya",
                "Yurak exokardiografiyasi",
                "Qalqonsimon bez",
                "Ko‘krak bezi",
                "Homila skriningi",
                "Qon tomir doppleri",
                "Transkranial doppler",
                "Mushak, bo‘g‘im va nerv tizimi",
            ),
            sections.map { it.name },
        )
        assertEquals("every row is valid and unique", rows, sections.sumOf { it.videoCount })
        assertTrue(sections.all { section -> section.topics.isNotEmpty() })
    }

    private val sample =
        parseUziCatalog(
            listOf(
                "A\tT1\tveQhbLyqLyU\tone",
                "A\tT1\tqdlb0bl2eOI\ttwo",
                "A\tT2\tgcGHNdrgYW8\tthree",
                "B\tT3\tji2I3Pv1R0g\tfour",
            ).joinToString("\n"),
        )

    @Test
    fun encodedCatalogReadsBackTheSame() {
        assertEquals(sample, parseUziCatalog(encodeUziCatalog(sample)))
    }

    @Test
    fun addingCreatesSectionAndTopicOrAppendsAndNeverRepeats() {
        val added = addCatalogVideo(sample, "C", "New", CatalogVideo("J5gRFeZ7cZU", "liver\tlecture"))
        assertEquals(listOf("A", "B", "C"), added.map { it.name })
        assertEquals(
            "liver lecture",
            added[2]
                .topics
                .single()
                .videos
                .single()
                .title,
        )

        val appended = addCatalogVideo(sample, "a", "t2", CatalogVideo("J5gRFeZ7cZU", ""))
        assertEquals(listOf("gcGHNdrgYW8", "J5gRFeZ7cZU"), appended[0].topics[1].videos.map { it.videoId })
        assertEquals("J5gRFeZ7cZU", appended[0].topics[1].videos[1].title)

        val moved = addCatalogVideo(sample, "B", "T3", CatalogVideo("veQhbLyqLyU", "one"))
        assertEquals(listOf("qdlb0bl2eOI"), moved[0].topics[0].videos.map { it.videoId })
        assertEquals(listOf("ji2I3Pv1R0g", "veQhbLyqLyU"), moved[1].topics[0].videos.map { it.videoId })
    }

    @Test(expected = IllegalArgumentException::class)
    fun addingWithoutATopicIsRefused() {
        addCatalogVideo(sample, "A", " ", CatalogVideo("J5gRFeZ7cZU", "x"))
    }

    @Test
    fun removingTheLastLectureDropsItsTopicAndSection() {
        val withoutB = removeCatalogVideo(sample, "ji2I3Pv1R0g")
        assertEquals(listOf("A"), withoutB.map { it.name })
        val withoutT2 = removeCatalogVideo(sample, "gcGHNdrgYW8")
        assertEquals(listOf("T1"), withoutT2[0].topics.map { it.name })
        assertEquals(listOf("B"), removeCatalogSection(sample, "A").map { it.name })
    }

    @Test
    fun movingStaysInsideTheList() {
        assertEquals(listOf("B", "A"), moveCatalogSection(sample, "B", -1).map { it.name })
        assertEquals(sample, moveCatalogSection(sample, "A", -1))
        assertEquals(
            listOf("qdlb0bl2eOI", "veQhbLyqLyU"),
            moveCatalogVideo(sample, "veQhbLyqLyU", 1)[0].topics[0].videos.map { it.videoId },
        )
        assertEquals(sample, moveCatalogVideo(sample, "gcGHNdrgYW8", 1))
    }
}
