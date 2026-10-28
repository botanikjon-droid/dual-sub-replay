package com.kienhoang.dualsubreplay.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.data.CatalogSection
import com.kienhoang.dualsubreplay.data.LessonLibraryStore
import com.kienhoang.dualsubreplay.data.LessonRecord
import com.kienhoang.dualsubreplay.data.UziCatalogStore
import com.kienhoang.dualsubreplay.data.YouTubeSearch
import com.kienhoang.dualsubreplay.data.YouTubeSearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * "Qo‘shimcha videolar" in the top bar (focus mode, a lesson open): searches YouTube for more on the lesson,
 * starting with its title, and shows the videos as the app's own list. A chosen video opens in the player as
 * a lesson the doctor picked himself, so focus mode lets it stay.
 */
@Composable
internal fun RowScope.LessonSearchButton(
    activeVideoId: String?,
    onOpen: (String) -> Unit,
) {
    if (!shokalonFocusMode || activeVideoId == null) return
    var open by rememberSaveable { mutableStateOf(false) }
    val sections by UziCatalogStore.sections.collectAsStateWithLifecycle()
    val records by LessonLibraryStore.records.collectAsStateWithLifecycle()
    IconButton(
        onClick = { open = true },
        modifier = Modifier.align(Alignment.CenterVertically).testTag("lesson_search"),
    ) { Icon(Icons.Default.Search, contentDescription = "Mavzu bo‘yicha qo‘shimcha videolar") }
    if (open) {
        YouTubeSearchDialog(
            title = "Qo\u2018shimcha videolar",
            subtitle = "Mavzu bo\u2018yicha YouTube'dan qidirish",
            initialQuery = lessonSearchQuery(sections, records, activeVideoId),
            excludeVideoId = activeVideoId,
            sections = sections,
            onPick = { result ->
                focusRequestedVideos += result.videoId
                open = false
                onOpen(result.videoId)
            },
            onDismiss = { open = false },
        )
    }
}

/** The open lesson's title (the catalog's, else the one seen when it was translated), to search with. */
internal fun lessonSearchQuery(
    sections: List<CatalogSection>?,
    records: List<LessonRecord>,
    videoId: String,
): String =
    sections
        ?.firstNotNullOfOrNull { section ->
            section.topics.firstNotNullOfOrNull { topic -> topic.videos.firstOrNull { it.videoId == videoId } }
        }?.title
        ?: records.firstOrNull { it.videoId == videoId }?.title.orEmpty()

private sealed interface SearchState {
    data object Idle : SearchState

    data object Loading : SearchState

    data class Found(
        val results: List<YouTubeSearchResult>,
    ) : SearchState

    data class Failed(
        val message: String,
    ) : SearchState
}

@Composable
internal fun YouTubeSearchDialog(
    title: String,
    subtitle: String,
    initialQuery: String,
    excludeVideoId: String?,
    sections: List<CatalogSection>?,
    onPick: (YouTubeSearchResult) -> Unit,
    onDismiss: () -> Unit,
    picking: PickingActions? = null,
) {
    // A picking session coming back from "Ko‘rish" shows the same results again, without a new search.
    val kept = CatalogPicker.state.value.takeIf { picking != null && it.results.isNotEmpty() }
    var query by rememberSaveable { mutableStateOf(kept?.query ?: initialQuery) }
    var state by remember { mutableStateOf<SearchState>(kept?.let { SearchState.Found(it.results) } ?: SearchState.Idle) }
    val scope = rememberCoroutineScope()
    val pickerState by CatalogPicker.state.collectAsStateWithLifecycle()

    fun search() {
        val text = query.trim()
        if (text.isEmpty()) return
        state = SearchState.Loading
        scope.launch {
            state =
                try {
                    SearchState.Found(
                        YouTubeSearch.search(text).filter { it.videoId != excludeVideoId }.also {
                            if (picking !=
                                null
                            ) {
                                CatalogPicker.keep(text, it)
                            }
                        },
                    )
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: IOException) {
                    SearchState.Failed(error.message ?: "Qidirib bo‘lmadi.")
                }
        }
    }
    LaunchedEffect(Unit) { if (query.isNotBlank() && state == SearchState.Idle) search() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("lesson_search_dialog"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Orqaga") }
                    Column {
                        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Hint(subtitle)
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Qidiruv so‘zi (inglizcha yaxshiroq topadi)") },
                    trailingIcon = { IconButton(onClick = ::search) { Icon(Icons.Default.Search, contentDescription = "Qidirish") } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("lesson_search_query"),
                )
                val ticked = pickerState.selected.mapTo(HashSet()) { it.videoId }.takeIf { picking != null }
                Box(Modifier.weight(1f)) { SearchResults(state, sections, onPick, ticked, picking) }
                if (picking != null) PickingBar(pickerState.selected.size, picking)
            }
        }
    }
}

@Composable
private fun SearchResults(
    state: SearchState,
    sections: List<CatalogSection>?,
    onPick: (YouTubeSearchResult) -> Unit,
    ticked: Set<String>?,
    picking: PickingActions?,
) {
    when (state) {
        SearchState.Idle -> Box(Modifier.padding(16.dp)) { Hint("Qidiruv so‘zini yozing.") }
        SearchState.Loading ->
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is SearchState.Failed -> Box(Modifier.padding(16.dp)) { Text(state.message, color = MaterialTheme.colorScheme.error) }
        is SearchState.Found ->
            if (state.results.isEmpty()) {
                Box(Modifier.padding(16.dp)) { Hint("Hech narsa topilmadi. Boshqa so‘z bilan urinib ko‘ring.") }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding =
                        PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.results, key = { it.videoId }) { result ->
                        SearchResultCard(
                            result,
                            catalogHasVideo(sections, result.videoId) == true,
                            ticked = ticked?.contains(result.videoId),
                            onWatch = picking?.let { actions -> { actions.watch(result) } },
                        ) { if (picking != null) CatalogPicker.toggle(result) else onPick(result) }
                    }
                }
            }
    }
}

@Composable
private fun SearchResultCard(
    result: YouTubeSearchResult,
    inCatalog: Boolean,
    ticked: Boolean? = null,
    onWatch: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp, shadowElevation = 2.dp) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(128.dp)
                    .height(72.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1E293B)),
            ) {
                thumbnail(result.videoId)?.let {
                    Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Text(
                    result.duration,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp),
                )
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(result.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Hint(listOf(result.channel, result.views, result.published).filter(String::isNotBlank).joinToString(" · "))
                if (inCatalog) Text("✓ SHOKALON mundarijasida bor", style = MaterialTheme.typography.labelSmall, color = Color(0xFF16A34A))
                if (onWatch != null) {
                    TextButton(onClick = onWatch, modifier = Modifier.testTag("search_watch")) { Text("\u25B6 Ko\u2018rish") }
                }
            }
            if (ticked != null) Checkbox(checked = ticked, onCheckedChange = { onClick() }, modifier = Modifier.testTag("search_tick"))
        }
    }
}

/** What the search does in a picking session ("Video qo‘shish"). */
internal class PickingActions(
    /** Opens the result in the player; the session waits for the doctor to come back. */
    val watch: (YouTubeSearchResult) -> Unit,
    /** Adds the ticked results. */
    val addSelected: () -> Unit,
)

@Composable
private fun PickingBar(
    count: Int,
    picking: PickingActions,
) {
    Surface(tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Tanlangan: $count ta video", fontWeight = FontWeight.SemiBold)
                Hint("Videoni bosib belgilang, \u25B6 Ko\u2018rish bilan oldin ko\u2018rib chiqing.")
            }
            Button(enabled = count > 0, onClick = picking.addSelected, modifier = Modifier.testTag("picker_add_selected")) {
                Text("Qo\u2018shish")
            }
        }
    }
}

@Composable
private fun thumbnail(videoId: String): ImageBitmap? {
    val picture by produceState<ImageBitmap?>(null, videoId) {
        value = YouTubeSearch.thumbnail(videoId)?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
    }
    return picture
}
