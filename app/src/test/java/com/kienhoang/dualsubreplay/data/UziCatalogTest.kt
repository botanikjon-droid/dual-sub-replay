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
    fun shippedCatalogHasTheEighteenSectionsInOrder() {
        val text = File("src/main/assets/$UZI_CATALOG_ASSET").readText(Charsets.UTF_8)
        val rows = text.lines().count { it.isNotBlank() && !it.startsWith("#") } - 1

        val sections = parseUziCatalog(text)

        assertEquals(
            listOf(
                "Ultratovush asoslari: fizika va apparat",
                "Tekshiruv metodikasi va xulosa yozish",
                "Qorin bo‘shlig‘i a’zolari",
                "Ginekologiya",
                "Urologiya",
                "Neyrosonografiya",
                "Pediatrik ultratovush",
                "Yurak exokardiografiyasi",
                "Qalqonsimon bez",
                "Ko‘krak bezi",
                "Yuzaki tuzilmalar",
                "Homila skriningi",
                "Qon tomir doppleri",
                "Transkranial doppler",
                "Mushak, bo‘g‘im va nerv tizimi",
                "Shoshilinch UZI va POCUS",
                "Elastografiya, kontrastli UZI va intervension UZI",
                "Sifat nazorati va kasbiy rivojlanish",
            ),
            sections.map { it.name },
        )
        assertEquals("every row is valid and unique", rows, sections.sumOf { it.videoCount })
        assertTrue(sections.all { section -> section.topics.isNotEmpty() })
        val versions = parseUziCatalogRows(text)
        assertEquals(rows, versions.size)
        assertEquals(4, catalogVersion(versions))
        // Version 1 is the 82 lectures checked on 2026-10-16, version 2 the 53 of the basics section,
        // version 3 the rest of the SHOKALON catalog (v12, 2026-10-17), version 4 the 50 fetal screening
        // lectures that fill the course plan's gaps (2026-10-27).
        assertEquals(50, versions.count { it.added == 4 })
        assertEquals(82, versions.count { it.added == 1 })
        assertEquals(53, versions.count { it.added == 2 })
        assertEquals(343, versions.count { it.added == 3 })
    }

    @Test
    fun versionsAreReadFromTheFifthColumn() {
        val rows =
            parseUziCatalogRows(
                listOf(
                    "section\ttopic\tvideo_id\ttitle\tadded",
                    "A\tT1\tveQhbLyqLyU\tone",
                    "A\tT1\tqdlb0bl2eOI\ttwo\t2",
                    "A\tT1\tgcGHNdrgYW8\tthree\tx",
                ).joinToString("\n"),
            )
        assertEquals(listOf(1, 2, 1), rows.map { it.added })
        assertEquals(2, catalogVersion(rows))
        assertEquals(1, catalogVersion(emptyList()))
    }

    @Test
    fun newLecturesJoinTheDoctorsCopyWithoutUndoingHisChanges() {
        val builtIn =
            parseUziCatalogRows(
                listOf(
                    "Asoslar\tFizika\tc6PQzbzJgUg\tphysics\t2",
                    "A\tT1\tveQhbLyqLyU\tone\t1",
                    "A\tT1\tqdlb0bl2eOI\ttwo\t1",
                    "A\tT1\tx6hXhLNc-jU\tnew in T1\t2",
                    "A\tT2\tgcGHNdrgYW8\tthree\t1",
                    "B\tT3\tji2I3Pv1R0g\tfour\t1",
                ).joinToString("\n"),
            )
        // He deleted "two", renamed nothing, moved B above A and added his own lecture.
        val own =
            listOf(
                CatalogSection("B", listOf(CatalogTopic("T3", listOf(CatalogVideo("ji2I3Pv1R0g", "four"))))),
                CatalogSection(
                    "A",
                    listOf(
                        CatalogTopic("T1", listOf(CatalogVideo("veQhbLyqLyU", "one"), CatalogVideo("LsK7ykAjHog", "mine"))),
                        CatalogTopic("T2", listOf(CatalogVideo("gcGHNdrgYW8", "three"))),
                    ),
                ),
            )

        val merged = mergeCatalogAdditions(own, builtIn, seenVersion = 1)

        assertEquals(listOf("Asoslar", "B", "A"), merged.map { it.name })
        assertEquals(
            listOf("veQhbLyqLyU", "LsK7ykAjHog", "x6hXhLNc-jU"),
            merged[2].topics[0].videos.map { it.videoId },
        )
        assertTrue(
            "a deleted old lecture stays deleted",
            merged.none { s ->
                s.topics.any { t -> t.videos.any { it.videoId == "qdlb0bl2eOI" } }
            },
        )
        // Nothing to do once his copy has version 2.
        assertEquals(own, mergeCatalogAdditions(own, builtIn, seenVersion = 2))
        // A newer lecture he already has elsewhere is not moved or repeated.
        val has = addCatalogVideo(own, "B", "T3", CatalogVideo("x6hXhLNc-jU", "already"))
        val again = mergeCatalogAdditions(has, builtIn, seenVersion = 1)
        assertEquals(
            listOf("ji2I3Pv1R0g", "x6hXhLNc-jU"),
            again
                .first { it.name == "B" }
                .topics[0]
                .videos
                .map { it.videoId },
        )
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
