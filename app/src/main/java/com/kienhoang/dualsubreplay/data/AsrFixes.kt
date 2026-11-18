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

/*
 * "Noto‘g‘ri eshitilgan so‘zlar": words YouTube's automatic captions keep mishearing (often with an Indian
 * accent: "doubler" for Doppler), fixed in the English before it is sent to the translator. The list ships in
 * assets/uzi_asr_fixes.tsv and grows with the doctor's own pairs (files/asr-fixes.tsv). Only whole words and
 * phrases are replaced, case-insensitively, the longest first.
 */

internal data class AsrFix(
    val heard: String,
    val correct: String,
)

internal const val UZI_ASR_FIXES_ASSET = "uzi_asr_fixes.tsv"
private const val USER_ASR_FIXES_FILE = "asr-fixes.tsv"

/** "heard<TAB>correct" lines; comments, the header and incomplete lines are skipped. */
internal fun parseAsrFixes(text: String): List<AsrFix> =
    text
        .lineSequence()
        .mapNotNull { line ->
            if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
            val fields = line.split('\t').map(String::trim)
            if (fields.size < 2 || fields[0].isEmpty() || fields[1].isEmpty() ||
                fields[0] == "heard"
            ) {
                null
            } else {
                AsrFix(fields[0], fields[1])
            }
        }.toList()

internal fun formatAsrFixes(fixes: List<AsrFix>): String =
    "heard\tcorrect\n" + fixes.joinToString("") { "${it.heard.replace('\t', ' ')}\t${it.correct.replace('\t', ' ')}\n" }

/** The doctor's pairs win over the shipped ones with the same misheard words. */
internal fun mergeAsrFixes(
    shipped: List<AsrFix>,
    own: List<AsrFix>,
): List<AsrFix> {
    val ownHeard = own.map { it.heard.lowercase() }.toSet()
    return own + shipped.filter { it.heard.lowercase() !in ownHeard }
}

/** One pattern for all fixes, longest phrase first, matching whole words only. */
internal class AsrFixer(
    fixes: List<AsrFix>,
) {
    private val byHeard = fixes.associateBy { it.heard.lowercase() }
    private val pattern =
        fixes
            .map { it.heard }
            .distinctBy(String::lowercase)
            .sortedByDescending(String::length)
            .takeIf(List<String>::isNotEmpty)
            ?.joinToString("|") { heard -> heard.trim().split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) } }
            ?.let { Regex("""(?<![\p{L}\p{N}])(?:$it)(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE) }

    fun fix(text: String): String {
        val regex = pattern ?: return text
        return regex.replace(text) { match ->
            val key =
                match.value
                    .lowercase()
                    .split(Regex("\\s+"))
                    .joinToString(" ")
            byHeard[key]?.correct ?: match.value
        }
    }
}

internal object AsrFixStore {
    private val shippedFixes = MutableStateFlow<List<AsrFix>>(emptyList())
    private val _own = MutableStateFlow<List<AsrFix>>(emptyList())

    /** The doctor's own pairs, for the card that lists and edits them. */
    val own: StateFlow<List<AsrFix>> = _own
    private val lock = Mutex()
    private var file: File? = null

    suspend fun load(context: Context) =
        lock.withLock {
            if (file != null) return@withLock
            val target = File(context.applicationContext.filesDir, USER_ASR_FIXES_FILE)
            file = target
            withContext(Dispatchers.IO) {
                shippedFixes.value =
                    try {
                        context.assets.open(UZI_ASR_FIXES_ASSET).use { parseAsrFixes(it.readBytes().toString(Charsets.UTF_8)) }
                    } catch (error: IOException) {
                        Log.w(TAG, "Shipped ASR fixes not read", error)
                        emptyList()
                    }
                _own.value =
                    try {
                        if (target.isFile) parseAsrFixes(target.readText(Charsets.UTF_8)) else emptyList()
                    } catch (error: IOException) {
                        Log.w(TAG, "Own ASR fixes not read", error)
                        emptyList()
                    }
            }
        }

    /** Every fix in force: the doctor's first, then the shipped list. */
    fun fixer(): AsrFixer = AsrFixer(mergeAsrFixes(shippedFixes.value, _own.value))

    suspend fun add(
        heard: String,
        correct: String,
    ) {
        val fix = AsrFix(heard.trim().replace(Regex("\\s+"), " "), correct.trim())
        require(fix.heard.isNotEmpty() && fix.correct.isNotEmpty()) { "Ikkala maydonni ham to‘ldiring." }
        save(listOf(fix) + _own.value.filter { !it.heard.equals(fix.heard, ignoreCase = true) })
    }

    suspend fun remove(fix: AsrFix) = save(_own.value - fix)

    private suspend fun save(next: List<AsrFix>) =
        lock.withLock {
            _own.value = next
            val target = file ?: return@withLock
            withContext(Dispatchers.IO) {
                try {
                    writeFileAtomically(target, formatAsrFixes(next))
                } catch (error: IOException) {
                    Log.w(TAG, "Own ASR fixes not saved", error)
                }
            }
        }

    private const val TAG = "AsrFixes"
}
