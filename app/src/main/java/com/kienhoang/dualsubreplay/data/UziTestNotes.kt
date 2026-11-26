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

/*
 * The doctor's own notes under a question ("Izoh"): what he wants to remember, or what he did not
 * understand. Kept per bank and question number in the app's private files (files/uzi-test-notes.json).
 */

/** The key of one question's note: the bank and the question number. */
internal fun uziNoteKey(
    kind: UziTestBankKind,
    number: Int,
): String = "${kind.name}:$number"

internal fun uziNotesToJson(notes: Map<String, String>): String =
    JSONObject().apply { notes.toSortedMap().forEach { (key, text) -> put(key, text) } }.toString()

/** The stored notes; an unreadable file gives none rather than failing. */
internal fun uziNotesFromJson(text: String): Map<String, String> =
    try {
        val json = JSONObject(text)
        json
            .keys()
            .asSequence()
            .associateWith { json.optString(it) }
            .filterValues(String::isNotBlank)
    } catch (error: JSONException) {
        emptyMap()
    }

internal object UziTestNotes {
    private val _notes = MutableStateFlow<Map<String, String>>(emptyMap())
    val notes: StateFlow<Map<String, String>> = _notes
    private val lock = Mutex()
    private var file: File? = null

    suspend fun load(context: Context) =
        lock.withLock {
            if (file != null) return@withLock
            val target = File(context.applicationContext.filesDir, NOTES_FILE)
            file = target
            _notes.value =
                withContext(Dispatchers.IO) {
                    try {
                        if (target.isFile) uziNotesFromJson(target.readText(Charsets.UTF_8)) else emptyMap()
                    } catch (error: IOException) {
                        Log.w(TAG, "Test notes not read", error)
                        emptyMap()
                    }
                }
        }

    /** Saves [text] as the note of [key]; a blank text deletes the note. */
    suspend fun save(
        key: String,
        text: String,
    ) = lock.withLock {
        val next = if (text.isBlank()) _notes.value - key else _notes.value + (key to text.trim())
        _notes.value = next
        val target = file ?: return@withLock
        withContext(Dispatchers.IO) {
            try {
                writeFileAtomically(target, uziNotesToJson(next))
            } catch (error: IOException) {
                Log.w(TAG, "Test notes not saved", error)
            }
        }
    }

    private const val NOTES_FILE = "uzi-test-notes.json"
    private const val TAG = "UziTestNotes"
}
