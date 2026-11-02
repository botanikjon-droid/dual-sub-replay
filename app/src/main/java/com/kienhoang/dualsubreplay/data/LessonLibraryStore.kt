package com.kienhoang.dualsubreplay.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Translated lessons and their study notes on the device: filesDir/lesson-library.tsv (the list) and
 * filesDir/lesson-notes/<videoId>.json (konspekt and quiz). Both are small, the doctor's own work and
 * survive restarts. Failures are logged and never stop playback.
 */
internal object LessonLibraryStore {
    private val _records = MutableStateFlow<List<LessonRecord>>(emptyList())
    val records: StateFlow<List<LessonRecord>> = _records
    private val _notedIds = MutableStateFlow<Set<String>>(emptySet())

    /** Videos with stored notes; changes when notes are written, so section screens update. */
    val notedIds: StateFlow<Set<String>> = _notedIds
    private val _notesSaved = MutableStateFlow(0)

    /** Counts notes saved this session, so a shown konspekt reloads after it is rewritten. */
    val notesSaved: StateFlow<Int> = _notesSaved
    private val lock = Mutex()
    private var directory: File? = null

    suspend fun load(context: Context) =
        lock.withLock {
            if (directory != null) return@withLock
            val files = context.applicationContext.filesDir
            directory = files
            _records.value =
                withContext(Dispatchers.IO) {
                    try {
                        val file = File(files, LIBRARY_FILE)
                        if (file.isFile) decodeLessonLibrary(file.readText(Charsets.UTF_8)) else emptyList()
                    } catch (error: IOException) {
                        Log.w(TAG, "Lesson list not read", error)
                        emptyList()
                    }
                }
            _notedIds.value = withContext(Dispatchers.IO) { storedNotesIds(files) }
        }

    private fun storedNotesIds(files: File): Set<String> =
        File(files, NOTES_DIRECTORY)
            .listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.mapTo(HashSet()) { it.name.removeSuffix(".json") }
            .orEmpty()

    suspend fun record(record: LessonRecord) =
        lock.withLock {
            val next = upsertLessonRecord(_records.value, record)
            _records.value = next
            val files = directory ?: return@withLock
            withContext(Dispatchers.IO) {
                try {
                    writeAtomically(File(files, LIBRARY_FILE), encodeLessonLibrary(next))
                } catch (error: IOException) {
                    Log.w(TAG, "Lesson list not saved", error)
                }
            }
        }

    suspend fun notes(videoId: String): LessonNotes? {
        val file = notesFile(videoId) ?: return null
        return withContext(Dispatchers.IO) {
            try {
                if (file.isFile) lessonNotesFromJson(JSONObject(file.readText(Charsets.UTF_8)), requireStudy = false) else null
            } catch (error: IOException) {
                Log.w(TAG, "Lesson notes not read", error)
                null
            } catch (error: JSONException) {
                null
            } catch (error: LessonNotesException) {
                null
            }
        }
    }

    /** Every lesson with stored notes, oldest translated first (the order of a study book). */
    suspend fun allNotes(): List<PrintableLesson> {
        val folder = directory?.let { File(it, NOTES_DIRECTORY) } ?: return emptyList()
        val ids =
            withContext(Dispatchers.IO) {
                folder.listFiles { file -> file.isFile && file.name.endsWith(".json") }?.map { it.name.removeSuffix(".json") }.orEmpty()
            }
        val records = _records.value
        return printOrder(ids, records).mapNotNull { id ->
            notes(id)?.let { notes -> PrintableLesson(id, records.firstOrNull { it.videoId == id }?.displayTitle() ?: "Video $id", notes) }
        }
    }

    suspend fun saveNotes(
        videoId: String,
        notes: LessonNotes,
    ) {
        val file = notesFile(videoId) ?: return
        withContext(Dispatchers.IO) {
            try {
                check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
                writeAtomically(file, notes.toJson().toString())
                _notedIds.value = _notedIds.value + videoId
                _notesSaved.value += 1
            } catch (error: IOException) {
                Log.w(TAG, "Lesson notes not saved", error)
            } catch (error: IllegalStateException) {
                Log.w(TAG, "Lesson notes folder not created", error)
            }
        }
    }

    /** Saves a lecture frame as JPEG in the lesson's notes folder; returns its file name, or null. */
    suspend fun saveFrame(
        videoId: String,
        seconds: Int,
        jpeg: ByteArray,
    ): String? {
        val folder = framesFolder(videoId) ?: return null
        val name = "$seconds.jpg"
        return withContext(Dispatchers.IO) {
            try {
                check(folder.isDirectory || folder.mkdirs())
                File(folder, name).writeBytes(jpeg)
                name
            } catch (error: IOException) {
                Log.w(TAG, "Frame not saved", error)
                null
            } catch (error: IllegalStateException) {
                Log.w(TAG, "Frame folder not created", error)
                null
            }
        }
    }

    /** A saved frame's file, for showing it in the notes. */
    fun frameFile(
        videoId: String,
        image: String,
    ): File? = framesFolder(videoId)?.let { File(it, image) }?.takeIf { image.isNotEmpty() }

    private fun framesFolder(videoId: String): File? = notesFile(videoId)?.let { File(it.parentFile, videoId) }

    private fun notesFile(videoId: String): File? {
        val files = directory ?: return null
        if (!videoId.matches(Regex("[A-Za-z0-9_-]{6,20}"))) return null
        return File(File(files, NOTES_DIRECTORY), "$videoId.json")
    }

    private fun writeAtomically(
        target: File,
        text: String,
    ) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(text, Charsets.UTF_8)
        if (!temporary.renameTo(target)) {
            target.writeText(text, Charsets.UTF_8)
            temporary.delete()
        }
    }

    private const val LIBRARY_FILE = "lesson-library.tsv"
    private const val NOTES_DIRECTORY = "lesson-notes"
    private const val TAG = "LessonLibrary"
}
