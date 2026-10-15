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
        }

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

    suspend fun saveNotes(
        videoId: String,
        notes: LessonNotes,
    ) {
        val file = notesFile(videoId) ?: return
        withContext(Dispatchers.IO) {
            try {
                check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
                writeAtomically(file, notes.toJson().toString())
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
