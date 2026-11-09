package com.kienhoang.dualsubreplay.data

import java.io.InputStream
import java.util.zip.ZipInputStream

/*
 * SHOKALON stage 8 hands back one ZIP of finished Uzbek translations named SHK_{code}_{V###}_UZ-LATIN.srt.
 * The phone imports it in one go: the Video ID in each name finds the lecture's YouTube video through the
 * catalog table (shokalon_ids.tsv), and each file is stored like a translation imported by hand. Reports,
 * notes and other files in the ZIP are skipped. The translations stay on the phone; nothing is uploaded.
 */

/** The engine name stored for these lessons, shown as "✓ Tarjima: SHOKALON". */
internal const val SHOKALON_TRANSLATION_SOURCE = "SHOKALON"

private val TRANSLATED_SUBTITLE_NAME = Regex("""SHK_\d{6}_(V\d{3,})_UZ-LATIN\.srt""", RegexOption.IGNORE_CASE)

/** A translation found in the ZIP and the YouTube video it belongs to. */
internal data class ZipTranslation(
    val fileName: String,
    val vid: String,
    val videoId: String,
    val text: String,
)

/** What the ZIP held: translations with a known lecture, and translation files whose Video ID is not in the catalog. */
internal data class TranslationZipContents(
    val translations: List<ZipTranslation>,
    val unknown: List<String>,
)

/** The Video ID (V041) of a SHOKALON translation file name, folders ignored; null for any other file. */
internal fun translatedSubtitleVid(entryName: String): String? =
    TRANSLATED_SUBTITLE_NAME
        .matchEntire(entryName.substringAfterLast('/'))
        ?.groupValues
        ?.get(1)
        ?.uppercase()

/** Reads every SHOKALON translation in [input]; [ids] is the catalog table keyed by YouTube video ID. */
internal fun readTranslationZip(
    input: InputStream,
    ids: Map<String, ShokalonId>,
): TranslationZipContents {
    val byVid = ids.values.associate { it.vid to it.videoId }
    val translations = mutableListOf<ZipTranslation>()
    val unknown = mutableListOf<String>()
    ZipInputStream(input).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.isDirectory) continue
            val vid = translatedSubtitleVid(entry.name) ?: continue
            val fileName = entry.name.substringAfterLast('/')
            val videoId = byVid[vid]
            if (videoId == null) {
                unknown += fileName
            } else {
                translations += ZipTranslation(fileName, vid, videoId, readBoundedText(zip).removePrefix("\uFEFF"))
            }
        }
    }
    return TranslationZipContents(translations, unknown)
}

/** The Uzbek summary shown after an import. */
internal fun translationZipSummary(
    imported: Int,
    cues: Int,
    unknown: List<String>,
    failed: List<String>,
): String =
    buildString {
        append(
            if (imported == 0 && unknown.isEmpty() && failed.isEmpty()) {
                "ZIP ichida SHK_…_UZ-LATIN.srt tarjima fayli topilmadi."
            } else {
                "Yuklandi: $imported ta dars, $cues ta blok. Darslar ochilganda tarjima ko‘rinadi."
            },
        )
        if (unknown.isNotEmpty()) append("\nMundarijada yo‘q: ").append(unknown.joinToString(", "))
        if (failed.isNotEmpty()) append("\nYuklanmadi: ").append(failed.joinToString(", "))
    }
