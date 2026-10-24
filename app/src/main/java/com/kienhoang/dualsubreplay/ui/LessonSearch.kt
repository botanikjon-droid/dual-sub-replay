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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.kienhoang.dualsubreplay.data.LessonLibraryStore
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
    IconButton(
        onClick = { open = true },
        modifier = Modifier.align(Alignment.CenterVertically).testTag("lesson_search"),
    ) { Icon(Icons.Default.Search, contentDescription = "Mavzu bo‘yicha qo‘shimcha videolar") }
    if (open) {
        LessonSearchDialog(
            initialQuery = lessonSearchQuery(activeVideoId),
            activeVideoId = activeVideoId,
            onOpen = { videoId ->
                focusRequestedVideos += videoId
                open = false
                onOpen(videoId)
            },
            onDismiss = { open = false },
        )
    }
}

/** The open lesson's title (the catalog's, else the one seen when it was translated), to search with. */
private fun lessonSearchQuery(videoId: String): String =
    UziCatalogStore.sections.value
        ?.firstNotNullOfOrNull { section ->
            section.topics.firstNotNullOfOrNull { topic -> topic.videos.firstOrNull { it.videoId == videoId } }
        }?.title
        ?: LessonLibraryStore.records.value
            .firstOrNull { it.videoId == videoId }
            ?.title
            .orEmpty()

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
private fun LessonSearchDialog(
    initialQuery: String,
    activeVideoId: String,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var state by remember { mutableStateOf<SearchState>(SearchState.Idle) }
    val scope = rememberCoroutineScope()

    fun search() {
        val text = query.trim()
        if (text.isEmpty()) return
        state = SearchState.Loading
        scope.launch {
            state =
                try {
                    SearchState.Found(YouTubeSearch.search(text).filter { it.videoId != activeVideoId })
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: IOException) {
                    SearchState.Failed(error.message ?: "Qidirib bo‘lmadi.")
                }
        }
    }
    LaunchedEffect(Unit) { if (query.isNotBlank()) search() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("lesson_search_dialog"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Darsga qaytish") }
                    Column {
                        Text("Qo‘shimcha videolar", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Hint("Mavzu bo‘yicha YouTube'dan qidirish")
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
                SearchResults(state, onOpen)
            }
        }
    }
}

@Composable
private fun SearchResults(
    state: SearchState,
    onOpen: (String) -> Unit,
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
                    items(state.results, key = { it.videoId }) { result -> SearchResultCard(result) { onOpen(result.videoId) } }
                }
            }
    }
}

@Composable
private fun SearchResultCard(
    result: YouTubeSearchResult,
    onClick: () -> Unit,
) {
    val inCatalog = catalogHasVideo(UziCatalogStore.sections.value, result.videoId) == true
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
