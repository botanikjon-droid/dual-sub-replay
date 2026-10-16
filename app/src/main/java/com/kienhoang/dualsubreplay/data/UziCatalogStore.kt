package com.kienhoang.dualsubreplay.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The lesson catalog shown in the app: the doctor's own copy (filesDir/uzi-catalog.tsv) once he has changed
 * anything, otherwise the built-in asset. Null until loaded; empty when nothing could be read.
 */
internal object UziCatalogStore {
    private val _sections = MutableStateFlow<List<CatalogSection>?>(null)
    val sections: StateFlow<List<CatalogSection>?> = _sections

    /** True while the doctor's own copy is in use (then "Asl holiga qaytarish" is offered). */
    private val _edited = MutableStateFlow(false)
    val edited: StateFlow<Boolean> = _edited

    private val lock = Mutex()
    private var files: File? = null
    private var builtIn: List<CatalogSection> = emptyList()

    suspend fun load(context: Context) =
        lock.withLock {
            if (files != null) return@withLock
            val app = context.applicationContext
            files = app.filesDir
            withContext(Dispatchers.IO) {
                builtIn =
                    try {
                        app.assets.open(UZI_CATALOG_ASSET).use { parseUziCatalog(it.readBytes().toString(Charsets.UTF_8)) }
                    } catch (error: IOException) {
                        Log.w(TAG, "Built-in catalog not read", error)
                        emptyList()
                    }
                val own = File(app.filesDir, OWN_FILE)
                val ownSections =
                    try {
                        if (own.isFile) parseUziCatalog(own.readText(Charsets.UTF_8)) else null
                    } catch (error: IOException) {
                        Log.w(TAG, "Own catalog not read", error)
                        null
                    }
                _edited.value = ownSections != null
                _sections.value = ownSections ?: builtIn
            }
        }

    /** Applies one change and keeps the result as the doctor's copy. */
    suspend fun change(edit: (List<CatalogSection>) -> List<CatalogSection>) =
        lock.withLock {
            val next = edit(_sections.value ?: builtIn)
            _sections.value = next
            _edited.value = true
            val folder = files ?: return@withLock
            withContext(Dispatchers.IO) {
                try {
                    val target = File(folder, OWN_FILE)
                    val temporary = File(folder, "$OWN_FILE.tmp")
                    temporary.writeText(encodeUziCatalog(next), Charsets.UTF_8)
                    if (!temporary.renameTo(target)) {
                        target.writeText(encodeUziCatalog(next), Charsets.UTF_8)
                        temporary.delete()
                    }
                } catch (error: IOException) {
                    Log.w(TAG, "Own catalog not saved", error)
                }
            }
        }

    /** Drops the doctor's copy: the built-in catalog comes back. */
    suspend fun reset() =
        lock.withLock {
            files?.let { folder -> withContext(Dispatchers.IO) { File(folder, OWN_FILE).delete() } }
            _sections.value = builtIn
            _edited.value = false
        }

    private const val OWN_FILE = "uzi-catalog.tsv"
    private const val TAG = "UziCatalog"
}
