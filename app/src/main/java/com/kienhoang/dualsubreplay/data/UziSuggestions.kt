package com.kienhoang.dualsubreplay.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.IOException

/*
 * Suggested lectures (assets/uzi_suggestions.tsv): videos found for the catalog's gaps that the doctor has
 * not watched yet. They are not lessons: a section lists them under "Tavsiya qilingan videolar", he watches
 * one and adds it to his catalog, or hides it. Same columns as the catalog.
 */

internal const val UZI_SUGGESTIONS_ASSET = "uzi_suggestions.tsv"

/** The suggestions of [section] still to look at: not in his catalog and not hidden, in file order. */
internal fun sectionSuggestions(
    all: List<CatalogRow>,
    section: String,
    catalog: List<CatalogSection>?,
    hidden: Set<String>,
): List<CatalogRow> {
    val present =
        catalog
            .orEmpty()
            .flatMap { it.topics }
            .flatMap { it.videos }
            .mapTo(HashSet()) { it.videoId }
    return all.filter { it.section == section && it.video.videoId !in present && it.video.videoId !in hidden }
}

internal object UziSuggestionsStore {
    private val _rows = MutableStateFlow<List<CatalogRow>>(emptyList())
    val rows: StateFlow<List<CatalogRow>> = _rows
    private val _hidden = MutableStateFlow<Set<String>>(emptySet())

    /** Suggestions the doctor marked "Kerak emas". */
    val hidden: StateFlow<Set<String>> = _hidden
    private var context: Context? = null

    suspend fun load(context: Context) {
        if (this.context != null) return
        val app = context.applicationContext
        this.context = app
        _hidden.value = preferences(app).getStringSet(KEY_HIDDEN, emptySet()).orEmpty().toSet()
        _rows.value =
            withContext(Dispatchers.IO) {
                try {
                    parseUziCatalogRows(app.assets.open(UZI_SUGGESTIONS_ASSET).use { it.readBytes().toString(Charsets.UTF_8) })
                } catch (error: IOException) {
                    Log.w(TAG, "Suggestions not read", error)
                    emptyList()
                }
            }
    }

    fun hide(videoId: String) {
        val next = _hidden.value + videoId
        _hidden.value = next
        context?.let { preferences(it).edit().putStringSet(KEY_HIDDEN, next).apply() }
    }

    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private const val PREFERENCES = "uzi_suggestions"
    private const val KEY_HIDDEN = "hidden"
    private const val TAG = "UziSuggestions"
}
