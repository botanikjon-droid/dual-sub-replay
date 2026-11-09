package com.kienhoang.dualsubreplay.data

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/*
 * SHOKALON subtitle harvest (project stages 4-6): the phone, which YouTube serves like any viewer, downloads the
 * English caption track of every catalog lecture, keeps it untouched as SubRip and packs everything into one ZIP
 * with a manifest that lists every catalog lecture, so the archive can be compared with the catalog. Naming follows
 * the SHOKALON rule SHK_{topic code without dots}_{Video ID}_{variant}.srt.
 */

internal const val SHOKALON_IDS_ASSET = "shokalon_ids.tsv"

/** Folder inside the ZIP for untouched source subtitles (SHOKALON tree: 03_SUBTITLES_ORIGINAL). */
internal const val HARVEST_ZIP_FOLDER = "03_SUBTITLES_ORIGINAL/"

internal const val HARVEST_MANIFEST = "manifest.tsv"

private val HARVEST_VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
private val SHOKALON_CODE = Regex("""\d{2}\.\d{2}\.\d{2}""")
private val SHOKALON_VID = Regex("""V\d{3,}""")

/** A catalog lecture's project identity: its curriculum topic code (BB.CC.TT) and its Video ID (V001…). */
internal data class ShokalonId(
    val videoId: String,
    val code: String,
    val vid: String,
)

/** The asset's rows keyed by YouTube video ID; comments, the header and malformed rows are skipped. */
internal fun parseShokalonIds(text: String): Map<String, ShokalonId> {
    val ids = linkedMapOf<String, ShokalonId>()
    text.lineSequence().forEach { line ->
        if (line.isBlank() || line.startsWith("#")) return@forEach
        val cells = line.split('\t').map(String::trim)
        if (cells.size < 3) return@forEach
        val (videoId, code, vid) = cells
        if (!HARVEST_VIDEO_ID.matches(videoId) || !SHOKALON_CODE.matches(code) || !SHOKALON_VID.matches(vid)) return@forEach
        ids.putIfAbsent(videoId, ShokalonId(videoId, code, vid))
    }
    return ids
}

/** One lecture to collect, in catalog order. [id] is null for a lecture the doctor added himself. */
internal data class HarvestItem(
    val videoId: String,
    val title: String,
    val id: ShokalonId?,
)

/** Every catalog lecture once, in the order the catalog shows them. */
internal fun harvestItems(
    sections: List<CatalogSection>,
    ids: Map<String, ShokalonId>,
): List<HarvestItem> {
    val seen = mutableSetOf<String>()
    return sections.flatMap { section ->
        section.topics.flatMap { topic ->
            topic.videos.filter { seen.add(it.videoId) }.map { HarvestItem(it.videoId, it.title, ids[it.videoId]) }
        }
    }
}

/**
 * ORIGINAL for the creator's English track, ASR-EN for YouTube's automatic English one. A lecture without
 * English captions keeps its own language: ORIGINAL-ES, ASR-DE.
 */
internal fun subtitleVariant(
    languageCode: String,
    generated: Boolean,
): String {
    val language =
        languageCode
            .substringBefore('-')
            .uppercase()
            .filter(Char::isLetter)
            .ifEmpty { "XX" }
    return when {
        generated -> "ASR-$language"
        language == "EN" -> "ORIGINAL"
        else -> "ORIGINAL-$language"
    }
}

/** SHK_031106_V004_ORIGINAL.srt; a lecture outside the SHOKALON catalog uses SHK_000000_YT-{videoId}. */
internal fun harvestFileName(
    item: HarvestItem,
    variant: String,
): String {
    val code = item.id?.code?.replace(".", "") ?: "000000"
    val vid = item.id?.vid ?: "YT-${item.videoId}"
    return "SHK_${code}_${vid}_$variant.srt"
}

internal enum class HarvestStatus(
    val label: String,
) {
    /** The subtitle file is saved. */
    OK("bor"),

    /** YouTube says the video has no public caption track. */
    NO_CAPTIONS("subtitr yo‘q"),

    /** Anything else (network, playability, timeout): tried again on the next run. */
    FAILED("xato"),
    ;

    companion object {
        fun of(name: String): HarvestStatus? = entries.firstOrNull { it.name == name }
    }
}

/** The outcome for one lecture, kept between runs so an interrupted harvest goes on where it stopped. */
internal data class HarvestRecord(
    val videoId: String,
    val status: HarvestStatus,
    val fileName: String = "",
    val language: String = "",
    val generated: Boolean = false,
    val cues: Int = 0,
    val note: String = "",
)

private const val RECORD_FIELDS = 7

internal fun encodeHarvestRecords(records: Collection<HarvestRecord>): String =
    buildString {
        records.forEach { record ->
            listOf(
                record.videoId,
                record.status.name,
                record.fileName,
                record.language,
                if (record.generated) "1" else "0",
                record.cues.toString(),
                record.note,
            ).joinTo(this, "\t") { it.replace('\t', ' ').replace('\n', ' ') }
            append('\n')
        }
    }

internal fun parseHarvestRecords(text: String): Map<String, HarvestRecord> {
    val records = linkedMapOf<String, HarvestRecord>()
    text.lineSequence().forEach { line ->
        val cells = line.split('\t')
        if (cells.size < RECORD_FIELDS) return@forEach
        val status = HarvestStatus.of(cells[1]) ?: return@forEach
        if (!HARVEST_VIDEO_ID.matches(cells[0])) return@forEach
        records[cells[0]] = HarvestRecord(cells[0], status, cells[2], cells[3], cells[4] == "1", cells[5].toIntOrNull() ?: 0, cells[6])
    }
    return records
}

/** True when this lecture still needs a download: never tried, failed before, or (on request) had no captions. */
internal fun needsHarvest(
    record: HarvestRecord?,
    retryNoCaptions: Boolean,
): Boolean =
    when (record?.status) {
        null, HarvestStatus.FAILED -> true
        HarvestStatus.NO_CAPTIONS -> retryNoCaptions
        HarvestStatus.OK -> false
    }

/** How far the harvest is, counted over the current catalog. */
internal data class HarvestCounts(
    val total: Int,
    val ok: Int,
    val noCaptions: Int,
    val failed: Int,
) {
    val done: Int get() = ok + noCaptions + failed
}

internal fun harvestCounts(
    items: List<HarvestItem>,
    records: Map<String, HarvestRecord>,
): HarvestCounts {
    val statuses = items.mapNotNull { records[it.videoId]?.status }
    return HarvestCounts(
        total = items.size,
        ok = statuses.count { it == HarvestStatus.OK },
        noCaptions = statuses.count { it == HarvestStatus.NO_CAPTIONS },
        failed = statuses.count { it == HarvestStatus.FAILED },
    )
}

/** The manifest inside the ZIP: one row per catalog lecture, with its file or the reason there is none. */
internal fun harvestManifest(
    items: List<HarvestItem>,
    records: Map<String, HarvestRecord>,
): String =
    buildString {
        append("video_id\tvid\tcode\tstatus\tfile\tlanguage\tauto\tcues\ttitle\tnote\n")
        items.forEach { item ->
            val record = records[item.videoId]
            listOf(
                item.videoId,
                item.id?.vid.orEmpty(),
                item.id?.code.orEmpty(),
                record?.status?.label ?: "yig‘ilmagan",
                record?.fileName.orEmpty(),
                record?.language.orEmpty(),
                if (record?.generated == true) "ha" else "",
                record
                    ?.cues
                    ?.takeIf { it > 0 }
                    ?.toString()
                    .orEmpty(),
                item.title,
                record?.note.orEmpty(),
            ).joinTo(this, "\t") { it.replace('\t', ' ').replace('\n', ' ') }
            append('\n')
        }
    }

/**
 * Writes the archive: every saved subtitle under [HARVEST_ZIP_FOLDER], the manifest and a short README.
 * [readFile] returns a saved file's bytes, or null when it is missing (then the lecture is not packed).
 * Returns the number of subtitle files written.
 */
internal fun writeHarvestZip(
    output: OutputStream,
    items: List<HarvestItem>,
    records: Map<String, HarvestRecord>,
    readme: String,
    readFile: (String) -> ByteArray?,
): Int {
    var written = 0
    ZipOutputStream(output).use { zip ->
        val names = mutableSetOf<String>()
        items.forEach { item ->
            val record = records[item.videoId]?.takeIf { it.status == HarvestStatus.OK } ?: return@forEach
            if (!names.add(record.fileName)) return@forEach
            val bytes = readFile(record.fileName) ?: return@forEach
            zip.putNextEntry(ZipEntry(HARVEST_ZIP_FOLDER + record.fileName))
            zip.write(bytes)
            zip.closeEntry()
            written++
        }
        zip.putNextEntry(ZipEntry(HARVEST_MANIFEST))
        zip.write(harvestManifest(items, records).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("README.txt"))
        zip.write(readme.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
    return written
}
