package com.kienhoang.dualsubreplay.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.data.YouTubeSearchResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/*
 * "Video qo‘shish" as a picking session: the doctor searches, watches a result in the player ("Ko‘rish"),
 * comes back to the same results with "Tanlanganlar" in the top bar, ticks the lectures he likes and adds them
 * together. The session (query, results, ticks) lives here, outside the dialogs, so watching does not lose it.
 */

internal data class PickerState(
    /** The section the lectures go to (null: the doctor chooses it when adding). */
    val section: String? = null,
    val query: String = "",
    val results: List<YouTubeSearchResult> = emptyList(),
    /** Ticked lectures in the order they were ticked. */
    val selected: List<YouTubeSearchResult> = emptyList(),
    /** True while a session is open or waiting for the doctor to come back from watching. */
    val active: Boolean = false,
    /** True after "Ko‘rish": the sections screen reopens the search when it is shown again. */
    val resume: Boolean = false,
)

internal object CatalogPicker {
    private val _state = MutableStateFlow(PickerState())
    val state: StateFlow<PickerState> = _state

    /** Starts a session for [section], or continues the one already open for it. */
    fun begin(section: String?) {
        _state.update { if (it.active && it.section == section) it.copy(resume = false) else PickerState(section, active = true) }
    }

    fun keep(
        query: String,
        results: List<YouTubeSearchResult>,
    ) = _state.update { it.copy(query = query, results = results) }

    fun toggle(result: YouTubeSearchResult) =
        _state.update { state ->
            val ticked = state.selected.any { it.videoId == result.videoId }
            state.copy(selected = if (ticked) state.selected.filterNot { it.videoId == result.videoId } else state.selected + result)
        }

    /** "Ko‘rish": the session waits while the lecture plays. */
    fun watch() = _state.update { it.copy(resume = true) }

    /** The edit to reopen when the sections screen is shown after watching, once. */
    fun takeResume(): CatalogEdit? {
        val state = _state.value
        if (!state.active || !state.resume) return null
        _state.update { it.copy(resume = false) }
        return CatalogEdit.Add(state.section)
    }

    fun finish() {
        _state.value = PickerState()
    }
}

/** Top bar: back to the picking session after watching a result, with the number ticked so far. */
@Composable
internal fun RowScope.PickerReturnButton(onReturn: (section: String?) -> Unit) {
    val state by CatalogPicker.state.collectAsStateWithLifecycle()
    if (!state.active || !state.resume) return
    TextButton(
        onClick = { onReturn(state.section) },
        modifier = Modifier.align(Alignment.CenterVertically).testTag("picker_return"),
    ) { Text("↩ Tanlanganlar (${state.selected.size})") }
}
