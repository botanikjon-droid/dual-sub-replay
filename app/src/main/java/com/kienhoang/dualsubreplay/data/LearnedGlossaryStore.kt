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
 * The learned glossary on the device (filesDir/learned-glossary.tsv). It survives app restarts and is
 * kept in backups, since it is small and the doctor's own work. Every change is written at once.
 */
internal object LearnedGlossaryStore {
    private val _terms = MutableStateFlow<List<LearnedTerm>>(emptyList())
    val terms: StateFlow<List<LearnedTerm>> = _terms
    private val lock = Mutex()
    private var file: File? = null

    suspend fun load(context: Context) =
        lock.withLock {
            if (file != null) return@withLock
            val target = File(context.applicationContext.filesDir, LEARNED_GLOSSARY_FILE)
            file = target
            _terms.value =
                withContext(Dispatchers.IO) {
                    try {
                        if (target.isFile) parseLearnedGlossary(target.readText(Charsets.UTF_8)) else emptyList()
                    } catch (error: IOException) {
                        Log.w(TAG, "Learned glossary not read", error)
                        emptyList()
                    }
                }
        }

    /** Applies [change] and saves the result; the new list is returned. */
    suspend fun update(change: (List<LearnedTerm>) -> List<LearnedTerm>): List<LearnedTerm> =
        lock.withLock {
            val next = change(_terms.value)
            _terms.value = next
            val target = file ?: return@withLock next
            withContext(Dispatchers.IO) {
                try {
                    val temporary = File(target.parentFile, "${target.name}.tmp")
                    temporary.writeText(formatLearnedGlossary(next), Charsets.UTF_8)
                    if (!temporary.renameTo(target)) {
                        target.writeText(formatLearnedGlossary(next), Charsets.UTF_8)
                        temporary.delete()
                    }
                } catch (error: IOException) {
                    Log.w(TAG, "Learned glossary not saved", error)
                }
            }
            next
        }

    private const val TAG = "LearnedGlossary"
}
