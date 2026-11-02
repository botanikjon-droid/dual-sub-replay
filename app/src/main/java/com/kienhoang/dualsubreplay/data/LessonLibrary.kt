package com.kienhoang.dualsubreplay.data

/*
 * The list of translated lessons: one line per video with its title, who translated it and when,
 * so the doctor can find and reopen a lesson. Pure model and a tab-separated file form; the file
 * store is LessonLibraryStore.
 */

internal data class LessonRecord(
    val videoId: String,
    val title: String,
    /** "Gemini", "Claude" or "Fayl" (imported by hand). */
    val engine: String,
    val translatedAtMs: Long,
    val lines: Int,
)

private val VIDEO_ID = Regex("[A-Za-z0-9_-]{6,20}")

internal fun encodeLessonLibrary(records: List<LessonRecord>): String =
    records.joinToString("\n") { record ->
        listOf(record.videoId, clean(record.title), clean(record.engine), record.translatedAtMs.toString(), record.lines.toString())
            .joinToString("\t")
    }

internal fun decodeLessonLibrary(text: String): List<LessonRecord> = text.lineSequence().mapNotNull(::decodeLine).toList()

private fun decodeLine(line: String): LessonRecord? {
    val parts = line.split('\t')
    if (parts.size < LIBRARY_FIELDS || !VIDEO_ID.matches(parts[0])) return null
    return LessonRecord(
        videoId = parts[0],
        title = parts[1],
        engine = parts[2],
        translatedAtMs = parts[3].toLongOrNull() ?: return null,
        lines = parts[4].toIntOrNull() ?: 0,
    )
}

private const val LIBRARY_FIELDS = 5

private fun clean(text: String): String = text.replace(Regex("[\\t\\r\\n]+"), " ").trim()

/**
 * Adds or refreshes a lesson, newest first. A missing title keeps the one stored before, so a
 * translation imported before the title was known does not erase it later.
 */
internal fun upsertLessonRecord(
    records: List<LessonRecord>,
    record: LessonRecord,
): List<LessonRecord> {
    val previous = records.firstOrNull { it.videoId == record.videoId }
    val title = record.title.ifBlank { previous?.title.orEmpty() }
    return (listOf(record.copy(title = title)) + records.filterNot { it.videoId == record.videoId })
        .sortedByDescending { it.translatedAtMs }
}

/** The title shown in the list: the video's title, or its id when the title was never known. */
internal fun LessonRecord.displayTitle(): String = title.ifBlank { "Video $videoId" }
