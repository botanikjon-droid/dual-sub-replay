package com.kienhoang.dualsubreplay.data

/*
 * "Ultratovush mundarijasi": English ultrasound lectures on YouTube by examination (section) and topic, from
 * the asset app/src/main/assets/uzi_catalog.tsv. Only links: a lecture opens in the app's single YouTube page.
 * tools/catalog/check_catalog_links.py checks every link (exists, plays, has English captions).
 */

internal const val UZI_CATALOG_ASSET = "uzi_catalog.tsv"

internal data class CatalogVideo(
    val videoId: String,
    val title: String,
)

internal data class CatalogTopic(
    val name: String,
    val videos: List<CatalogVideo>,
)

internal data class CatalogSection(
    val name: String,
    val topics: List<CatalogTopic>,
) {
    val videoCount: Int get() = topics.sumOf { it.videos.size }
}

private val CATALOG_VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
private const val CATALOG_FIELDS = 4

/** Sections, topics and lectures in the file's order; comments, the header and bad rows are skipped. */
internal fun parseUziCatalog(text: String): List<CatalogSection> {
    val sections = linkedMapOf<String, LinkedHashMap<String, MutableList<CatalogVideo>>>()
    val seen = mutableSetOf<String>()
    text.lineSequence().forEach { line ->
        if (line.isBlank() || line.startsWith("#")) return@forEach
        val cells = line.split('\t').map(String::trim)
        if (cells.size < CATALOG_FIELDS) return@forEach
        val (section, topic, videoId, title) = cells
        if (section.isEmpty() || topic.isEmpty() || !CATALOG_VIDEO_ID.matches(videoId) || !seen.add(videoId)) return@forEach
        sections.getOrPut(section) { linkedMapOf() }.getOrPut(topic) { mutableListOf() } += CatalogVideo(videoId, title.ifEmpty { videoId })
    }
    return sections.map { (name, topics) -> CatalogSection(name, topics.map { (topic, videos) -> CatalogTopic(topic, videos) }) }
}

/** One built-in lecture and the catalog version that added it (the asset's fifth column; 1 when absent). */
internal data class CatalogRow(
    val section: String,
    val topic: String,
    val video: CatalogVideo,
    val added: Int,
)

/** The asset's lectures with their versions, in file order, skipping what [parseUziCatalog] skips. */
internal fun parseUziCatalogRows(text: String): List<CatalogRow> {
    val seen = mutableSetOf<String>()
    return text
        .lineSequence()
        .mapNotNull { line ->
            if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
            val cells = line.split('\t').map(String::trim)
            if (cells.size < CATALOG_FIELDS) return@mapNotNull null
            val (section, topic, videoId, title) = cells
            if (section.isEmpty() || topic.isEmpty() || !CATALOG_VIDEO_ID.matches(videoId) || !seen.add(videoId)) return@mapNotNull null
            val added = cells.getOrNull(CATALOG_FIELDS)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            CatalogRow(section, topic, CatalogVideo(videoId, title.ifEmpty { videoId }), added)
        }.toList()
}

/** The newest version named in the asset; 1 for an asset without the version column. */
internal fun catalogVersion(rows: List<CatalogRow>): Int = rows.maxOfOrNull { it.added } ?: 1

/**
 * Brings lectures added to the built-in catalog after [seenVersion] into the doctor's copy [own]. Lectures
 * he already has stay where he put them; ones he deleted from an older version do not come back (they are
 * not newer than [seenVersion]). A new section is placed where it is in the built-in catalog.
 */
internal fun mergeCatalogAdditions(
    own: List<CatalogSection>,
    rows: List<CatalogRow>,
    seenVersion: Int,
): List<CatalogSection> {
    val present = own.flatMap { section -> section.topics.flatMap { topic -> topic.videos.map { it.videoId } } }.toSet()
    val ownNames = own.map { it.name.lowercase() }.toSet()
    var merged = own
    rows.filter { it.added > seenVersion && it.video.videoId !in present }.forEach { row ->
        merged = addCatalogVideo(merged, row.section, row.topic, row.video)
    }
    val builtInOrder = rows.map { it.section }.distinct()
    builtInOrder.forEachIndexed { index, name ->
        if (name.lowercase() in ownNames) return@forEachIndexed
        val section = merged.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return@forEachIndexed
        val rest = merged.filterNot { it === section }
        merged = rest.toMutableList().apply { add(index.coerceAtMost(rest.size), section) }
    }
    return merged
}

/*
 * The doctor's own changes ("✏️ O‘zgartirish"): the whole catalog is kept as his copy in
 * filesDir/uzi-catalog.tsv in the asset's format; "Asl holiga qaytarish" deletes the copy.
 */

/** The catalog in the asset's TSV format (header included), for the doctor's copy. */
internal fun encodeUziCatalog(sections: List<CatalogSection>): String =
    buildString {
        append("section\ttopic\tvideo_id\ttitle\n")
        sections.forEach { section ->
            section.topics.forEach { topic ->
                topic.videos.forEach { video ->
                    listOf(section.name, topic.name, video.videoId, video.title).joinTo(this, "\t") { catalogCell(it) }
                    append('\n')
                }
            }
        }
    }

private fun catalogCell(text: String): String =
    text
        .replace('\t', ' ')
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()

/**
 * Adds a lecture at the end of [topic] in [section] (both created when new). A lecture already in the
 * catalog is moved there instead of repeated.
 */
internal fun addCatalogVideo(
    sections: List<CatalogSection>,
    section: String,
    topic: String,
    video: CatalogVideo,
): List<CatalogSection> {
    val sectionName = catalogCell(section)
    val topicName = catalogCell(topic)
    require(sectionName.isNotEmpty() && topicName.isNotEmpty()) { "Bo‘lim va mavzu nomini yozing." }
    require(CATALOG_VIDEO_ID.matches(video.videoId)) { "YouTube havolasi noto‘g‘ri." }
    val clean = CatalogVideo(video.videoId, catalogCell(video.title).ifEmpty { video.videoId })
    val without = removeCatalogVideo(sections, video.videoId)
    val target = without.firstOrNull { it.name.equals(sectionName, ignoreCase = true) }
    if (target == null) return without + CatalogSection(sectionName, listOf(CatalogTopic(topicName, listOf(clean))))
    val topics =
        if (target.topics.any { it.name.equals(topicName, ignoreCase = true) }) {
            target.topics.map { if (it.name.equals(topicName, ignoreCase = true)) it.copy(videos = it.videos + clean) else it }
        } else {
            target.topics + CatalogTopic(topicName, listOf(clean))
        }
    return without.map { if (it === target) it.copy(topics = topics) else it }
}

/**
 * Puts [replacement] in the place of lecture [oldVideoId] (same section, topic and position), for a lecture
 * that cannot be used (another language, deleted, poor quality). A replacement already elsewhere is moved.
 */
internal fun replaceCatalogVideo(
    sections: List<CatalogSection>,
    oldVideoId: String,
    replacement: CatalogVideo,
): List<CatalogSection> {
    require(CATALOG_VIDEO_ID.matches(replacement.videoId)) { "YouTube havolasi noto‘g‘ri." }
    if (oldVideoId == replacement.videoId) return sections
    val clean = CatalogVideo(replacement.videoId, catalogCell(replacement.title).ifEmpty { replacement.videoId })
    val elsewhere = sections.any { section -> section.topics.any { topic -> topic.videos.any { it.videoId == oldVideoId } } }
    require(elsewhere) { "Almashtiriladigan dars mundarijada topilmadi." }
    val withoutNew =
        sections.map { section ->
            section.copy(
                topics =
                    section.topics.map { topic ->
                        topic.copy(videos = topic.videos.filterNot { it.videoId == clean.videoId })
                    },
            )
        }
    return withoutNew
        .map { section ->
            section.copy(
                topics =
                    section.topics
                        .map { topic -> topic.copy(videos = topic.videos.map { if (it.videoId == oldVideoId) clean else it }) }
                        .filter { it.videos.isNotEmpty() },
            )
        }.filter { it.topics.isNotEmpty() }
}

/** Removes a lecture; a topic or section left empty goes too. */
internal fun removeCatalogVideo(
    sections: List<CatalogSection>,
    videoId: String,
): List<CatalogSection> =
    sections
        .map { section ->
            section.copy(
                topics =
                    section.topics
                        .map { topic -> topic.copy(videos = topic.videos.filterNot { it.videoId == videoId }) }
                        .filter { it.videos.isNotEmpty() },
            )
        }.filter { it.topics.isNotEmpty() }

internal fun removeCatalogSection(
    sections: List<CatalogSection>,
    name: String,
): List<CatalogSection> = sections.filterNot { it.name == name }

/** Moves a section one place up ([step] -1) or down (+1). */
internal fun moveCatalogSection(
    sections: List<CatalogSection>,
    name: String,
    step: Int,
): List<CatalogSection> = sections.moved(sections.indexOfFirst { it.name == name }, step)

/** Moves a lecture one place up or down within its topic. */
internal fun moveCatalogVideo(
    sections: List<CatalogSection>,
    videoId: String,
    step: Int,
): List<CatalogSection> =
    sections.map { section ->
        section.copy(
            topics =
                section.topics.map { topic ->
                    topic.copy(videos = topic.videos.moved(topic.videos.indexOfFirst { it.videoId == videoId }, step))
                },
        )
    }

private fun <T> List<T>.moved(
    index: Int,
    step: Int,
): List<T> {
    val target = index + step
    if (index < 0 || target !in indices) return this
    return toMutableList().apply { add(target, removeAt(index)) }
}
