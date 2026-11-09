package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

class SubtitleHarvestTest {
    private val ids =
        parseShokalonIds(
            listOf(
                "# comment",
                "video_id\tcode\tvid\tlanguage",
                "U50O8_fsemY\t03.11.06\tV004\tIngliz",
                "x6hXhLNc-jU\t03.10.01\tV001\tIngliz",
                "short\t03.10.01\tV002\tIngliz",
                "JSUsM8aN1XU\t3.10.1\tV002\tIngliz",
                "GvvGZENUvWc\t03.10.02\tX3\tIngliz",
            ).joinToString("\n"),
        )

    private val catalog =
        listOf(
            CatalogSection(
                "Qorin",
                listOf(
                    CatalogTopic("Jigar", listOf(CatalogVideo("x6hXhLNc-jU", "Liver"), CatalogVideo("LsK7ykAjHog", "Mine"))),
                    CatalogTopic("CBD", listOf(CatalogVideo("U50O8_fsemY", "CBD"), CatalogVideo("x6hXhLNc-jU", "Liver again"))),
                ),
            ),
        )

    @Test
    fun idsKeepOnlyWellFormedRows() {
        assertEquals(setOf("U50O8_fsemY", "x6hXhLNc-jU"), ids.keys)
        assertEquals(ShokalonId("U50O8_fsemY", "03.11.06", "V004"), ids["U50O8_fsemY"])
    }

    @Test
    fun itemsFollowTheCatalogOnceEach() {
        val items = harvestItems(catalog, ids)
        assertEquals(listOf("x6hXhLNc-jU", "LsK7ykAjHog", "U50O8_fsemY"), items.map { it.videoId })
        assertNull(items[1].id)
    }

    @Test
    fun fileNamesFollowTheShokalonRule() {
        val (liver, mine, cbd) = harvestItems(catalog, ids)
        assertEquals("SHK_031106_V004_ORIGINAL.srt", harvestFileName(cbd, subtitleVariant("en", generated = false)))
        assertEquals("SHK_031001_V001_ASR-EN.srt", harvestFileName(liver, subtitleVariant("en-US", generated = true)))
        assertEquals("SHK_000000_YT-LsK7ykAjHog_ORIGINAL-ES.srt", harvestFileName(mine, subtitleVariant("es-419", generated = false)))
        assertEquals("ASR-XX", subtitleVariant("", generated = true))
    }

    @Test
    fun recordsSurviveARoundTripAndDecideWhatRunsAgain() {
        val records =
            listOf(
                HarvestRecord("x6hXhLNc-jU", HarvestStatus.OK, "SHK_031001_V001_ASR-EN.srt", "en", true, 812),
                HarvestRecord("U50O8_fsemY", HarvestStatus.NO_CAPTIONS, note = "no-tracks"),
                HarvestRecord("LsK7ykAjHog", HarvestStatus.FAILED, note = "timeout\twith tab"),
            )
        val parsed = parseHarvestRecords(encodeHarvestRecords(records) + "broken line\n")
        assertEquals(records[0], parsed["x6hXhLNc-jU"])
        assertEquals("timeout with tab", parsed["LsK7ykAjHog"]?.note)
        assertFalse(needsHarvest(parsed["x6hXhLNc-jU"], retryNoCaptions = true))
        assertFalse(needsHarvest(parsed["U50O8_fsemY"], retryNoCaptions = false))
        assertTrue(needsHarvest(parsed["U50O8_fsemY"], retryNoCaptions = true))
        assertTrue(needsHarvest(parsed["LsK7ykAjHog"], retryNoCaptions = false))
        assertTrue(needsHarvest(null, retryNoCaptions = false))
        assertEquals(HarvestCounts(total = 3, ok = 1, noCaptions = 1, failed = 1), harvestCounts(harvestItems(catalog, ids), parsed))
    }

    @Test
    fun zipHoldsSavedFilesManifestAndReadme() {
        val items = harvestItems(catalog, ids)
        val records =
            mapOf(
                "x6hXhLNc-jU" to HarvestRecord("x6hXhLNc-jU", HarvestStatus.OK, "SHK_031001_V001_ORIGINAL.srt", "en", false, 2),
                "U50O8_fsemY" to HarvestRecord("U50O8_fsemY", HarvestStatus.OK, "SHK_031106_V004_ORIGINAL.srt", "en", false, 1),
            )
        val out = ByteArrayOutputStream()
        val written =
            writeHarvestZip(out, items, records, "readme") { name ->
                if (name.contains("V001")) "1\n00:00:00,000 --> 00:00:01,000\nHi\n".toByteArray() else null
            }
        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes().toString(Charsets.UTF_8) }
        }
        assertEquals(1, written)
        assertEquals(listOf("03_SUBTITLES_ORIGINAL/SHK_031001_V001_ORIGINAL.srt", "manifest.tsv", "README.txt"), entries.keys.toList())
        val manifest = entries.getValue("manifest.tsv").trimEnd().lines()
        assertEquals(4, manifest.size)
        assertTrue(manifest[1].startsWith("x6hXhLNc-jU\tV001\t03.10.01\tbor\tSHK_031001_V001_ORIGINAL.srt"))
        assertTrue(manifest[2].startsWith("LsK7ykAjHog\t\t\tyig‘ilmagan"))
    }

    @Test
    fun everyShippedCatalogLectureHasAShokalonCode() {
        val shipped = parseShokalonIds(File("src/main/assets/$SHOKALON_IDS_ASSET").readText(Charsets.UTF_8))
        val catalogIds =
            parseUziCatalog(File("src/main/assets/$UZI_CATALOG_ASSET").readText(Charsets.UTF_8))
                .flatMap { section -> section.topics.flatMap { topic -> topic.videos.map { it.videoId } } }
        assertTrue(catalogIds.isNotEmpty())
        assertEquals("catalog lectures without a SHOKALON code", emptyList<String>(), catalogIds.filterNot(shipped::containsKey))
        assertEquals(
            "Video IDs are unique",
            shipped.size,
            shipped.values
                .map { it.vid }
                .toSet()
                .size,
        )
    }
}
