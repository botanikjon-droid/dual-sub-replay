package com.kienhoang.dualsubreplay.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionDisabled
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kienhoang.dualsubreplay.data.UziCatalogStore
import kotlinx.coroutines.flow.MutableStateFlow

/*
 * Player controls of SHOKALON's top bar: how the subtitles are shown (a panel under the video, a compact line
 * on the video, or hidden) and full screen. Full screen is the app's own: the phone turns sideways, the system
 * bars and the app's bar go, and the YouTube page shows only its player, filling the screen. YouTube's own
 * full-screen button keeps working too.
 */

/** True while the lesson is shown full screen. Read by the YouTube page to fill the screen with the player. */
internal val shokalonFullscreen = MutableStateFlow(false)

/**
 * True while the doctor has hidden the subtitles: neither the panel, nor the line on the video, nor the small
 * button that brings the panel back is shown (the voice still speaks). Kept for the app session.
 */
internal val shokalonSubtitlesHidden = MutableStateFlow(false)

/** Shows the subtitles as a panel, as a compact line on the video, or hides them completely. */
@Composable
internal fun RowScope.SubtitleModeButton(
    activeVideoId: String?,
    mode: PlayerExperienceMode,
    onMode: (PlayerExperienceMode) -> Unit,
    onShow: () -> Unit,
    onHide: () -> Unit,
) {
    if (!shokalonFocusMode || activeVideoId == null) return
    var open by remember { mutableStateOf(false) }
    val hidden by shokalonSubtitlesHidden.collectAsStateWithLifecycle()
    Box(Modifier.align(Alignment.CenterVertically)) {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("subtitle_mode")) {
            Icon(
                if (hidden) Icons.Default.ClosedCaptionDisabled else Icons.Default.ClosedCaption,
                contentDescription = "Subtitr ko\u2018rinishi",
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(mark(!hidden && mode == PlayerExperienceMode.TRANSCRIPT_PANEL) + "Subtitr paneli (video ostida)") },
                onClick = {
                    open = false
                    shokalonSubtitlesHidden.value = false
                    onMode(PlayerExperienceMode.TRANSCRIPT_PANEL)
                    onShow()
                },
            )
            DropdownMenuItem(
                text = { Text(mark(!hidden && mode == PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY) + "Video ustida, ixcham (fonda)") },
                onClick = {
                    open = false
                    shokalonSubtitlesHidden.value = false
                    onMode(PlayerExperienceMode.SCROLL_FRIENDLY_OVERLAY)
                },
            )
            DropdownMenuItem(
                text = { Text(if (hidden) "Subtitrni ko\u2018rsatish" else "Subtitrni butunlay yashirish") },
                onClick = {
                    open = false
                    shokalonSubtitlesHidden.value = !hidden
                    if (hidden) {
                        if (mode == PlayerExperienceMode.TRANSCRIPT_PANEL) onShow()
                    } else {
                        onHide()
                    }
                },
            )
        }
    }
}

private fun mark(selected: Boolean): String = if (selected) "\u2713 " else ""

/** The full-screen button of the top bar: the app's own full screen (YouTube's own button works too). */
@Composable
internal fun RowScope.FullscreenButton(
    activeVideoId: String?,
    onFullscreen: () -> Unit,
) {
    if (!shokalonFocusMode || activeVideoId == null) return
    IconButton(
        onClick = onFullscreen,
        modifier = Modifier.align(Alignment.CenterVertically).testTag("lesson_fullscreen"),
    ) { Icon(Icons.Default.Fullscreen, contentDescription = "To\u2018liq ekran") }
}

/** Shows [content] (the small button that brings the subtitle panel back) unless the subtitles are hidden. */
@Composable
internal fun UnlessSubtitlesHidden(content: @Composable () -> Unit) {
    val hidden by shokalonSubtitlesHidden.collectAsStateWithLifecycle()
    if (!hidden) content()
}

/**
 * A strip along the right edge: a swipe from it to the left opens the lesson list of the open lecture's
 * section (or the sections when the lecture is not in the catalog).
 */
@Composable
internal fun BoxScope.LessonEdgeSwipe(
    activeVideoId: String?,
    onOpen: (section: String?) -> Unit,
) {
    if (!shokalonFocusMode || activeVideoId == null) return
    val sections by UziCatalogStore.sections.collectAsStateWithLifecycle()
    val section = remember(sections, activeVideoId) { lessonSteps(sections, activeVideoId)?.section }
    val threshold = with(LocalDensity.current) { EDGE_SWIPE_DISTANCE.toPx() }
    var dragged by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight(EDGE_SWIPE_HEIGHT)
            .width(EDGE_SWIPE_WIDTH)
            .testTag("lesson_edge_swipe")
            .pointerInput(section) {
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = { if (dragged < -threshold) onOpen(section) },
                ) { _, amount -> dragged += amount }
            },
    )
}

private val EDGE_SWIPE_WIDTH = 22.dp
private val EDGE_SWIPE_DISTANCE = 56.dp
private const val EDGE_SWIPE_HEIGHT = 0.7f

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

/**
 * While [active]: turns the phone sideways, hides the system bars, shows an exit button over the video and
 * lets the back button leave full screen. Everything returns as it was on leaving.
 */
@Composable
internal fun BoxScope.FullscreenSession(
    active: Boolean,
    onExit: () -> Unit,
) {
    if (!active) return
    val activity = LocalContext.current.findActivity()
    val view = LocalView.current
    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val orientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = orientation
        }
    }
    BackHandler(onBack = onExit)
    FilledTonalIconButton(
        onClick = onExit,
        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).testTag("lesson_fullscreen_exit"),
    ) { Icon(Icons.Default.FullscreenExit, contentDescription = "To‘liq ekrandan chiqish") }
}

/** Page style that makes the YouTube player fill the screen and hides the rest of the page. */
internal fun fullscreenPlayerScript(enabled: Boolean): String {
    val css =
        org.json.JSONObject.quote(
            "html,body{overflow:hidden!important;background:#000!important}" +
                "#player-container-id,#player-container,.player-container,ytm-player,#player{position:fixed!important;" +
                "top:0!important;left:0!important;width:100vw!important;height:100vh!important;max-height:none!important;" +
                "margin:0!important;padding:0!important;z-index:2147483646!important;background:#000!important}" +
                ".html5-video-player,.html5-video-container{width:100%!important;height:100%!important}" +
                "video{width:100%!important;height:100%!important;left:0!important;top:0!important;object-fit:contain!important}",
        )
    return """
        (function() {
          const existing = document.getElementById('$FULLSCREEN_STYLE_ID');
          if (!$enabled) { if (existing) existing.remove(); window.dispatchEvent(new Event('resize')); return; }
          const style = existing || document.createElement('style');
          style.id = '$FULLSCREEN_STYLE_ID';
          style.textContent = $css;
          if (!existing) (document.head || document.documentElement).appendChild(style);
          window.dispatchEvent(new Event('resize'));
        })();
        """.trimIndent()
}

internal const val FULLSCREEN_STYLE_ID = "shokalon-fullscreen-style"
