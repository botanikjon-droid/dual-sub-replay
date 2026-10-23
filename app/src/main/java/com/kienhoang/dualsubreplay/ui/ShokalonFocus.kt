package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.BuildConfig
import com.kienhoang.dualsubreplay.data.CatalogSection
import com.kienhoang.dualsubreplay.data.CatalogVideo
import com.kienhoang.dualsubreplay.data.YouTubeUrlParser
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.net.URI

/*
 * SHOKALON focus mode (the Uzbek build only): the doctor watches catalog lectures, not YouTube. The YouTube
 * page keeps the player, title and description, but its suggestions (related videos, Shorts, the home feed,
 * comments, end-screen and pause-screen videos, the bottom tabs) are hidden, and a page that is not a lesson
 * (another video, Shorts, search, channels, feeds) is left at once for the last lesson.
 */

/** On in the SHOKALON (uz) build, off in the general DualSub Replay builds. */
internal val shokalonFocusMode: Boolean = BuildConfig.DISTRIBUTION == "uz"

internal const val FOCUS_STYLE_ID = "shokalon-focus-style"

/** YouTube's mobile page parts that offer other videos; selectors that match nothing cost nothing. */
private val DISTRACTION_SELECTORS =
    listOf(
        // Related and suggested videos under the player, and the home and search feeds.
        "ytm-item-section-renderer[section-identifier=\"related-items\"]",
        "ytm-watch-next-secondary-results-renderer",
        "ytm-video-with-context-renderer",
        "ytm-compact-video-renderer",
        "ytm-compact-radio-renderer",
        "ytm-compact-playlist-renderer",
        "ytm-compact-autoplay-renderer",
        "ytm-media-item",
        "ytm-video-card-renderer",
        "yt-lockup-view-model",
        "ytm-rich-grid-renderer",
        "ytm-rich-item-renderer",
        "ytm-rich-section-renderer",
        "ytm-shelf-renderer",
        "ytm-horizontal-card-list-renderer",
        "ytm-chip-cloud-renderer",
        ".related-chips-slot-wrapper",
        // Shorts.
        "ytm-reel-shelf-renderer",
        "ytm-reel-item-renderer",
        "ytm-shorts-lockup-view-model",
        "ytm-shorts-lockup-view-model-v2",
        // Comments.
        "ytm-comments-entry-point-header-renderer",
        "ytm-comment-section-renderer",
        // Bottom tabs (Home, Shorts, Subscriptions) and promotions.
        "ytm-pivot-bar-renderer",
        "ytm-promoted-sparkles-web-renderer",
        "ytm-companion-ad-renderer",
        "ad-slot-renderer",
        "ytm-merch-shelf-renderer",
        "ytm-statement-banner-renderer",
        // Videos offered inside the player: end screen, cards, autoplay countdown, pause overlay.
        ".ytp-endscreen-content",
        ".ytp-ce-element",
        ".ytp-ce-covering-overlay",
        ".ytp-autonav-endscreen",
        ".ytp-autonav-endscreen-upnext-container",
        ".ytp-suggestion-set",
        ".ytp-videowall-still",
        ".ytp-pause-overlay",
        ".ytp-related-on-error-overlay",
        ".ytm-autonav-bar",
        ".ytm-autonav-toggle-button-container",
    )

/** The CSS that hides [DISTRACTION_SELECTORS]. */
internal fun focusModeCss(): String = DISTRACTION_SELECTORS.joinToString(",\n") + " { display: none !important; }"

/** Adds (or removes) the focus style; safe to run on every page and every in-page navigation. */
internal fun focusModeScript(enabled: Boolean): String {
    val css = JSONObject.quote(focusModeCss())
    return """
        (function() {
          const existing = document.getElementById('$FOCUS_STYLE_ID');
          if (!$enabled) { if (existing) existing.remove(); return; }
          const style = existing || document.createElement('style');
          style.id = '$FOCUS_STYLE_ID';
          style.textContent = $css;
          if (!existing) (document.head || document.documentElement).appendChild(style);
        })();
        """.trimIndent()
}

/** Whether a YouTube page may stay open in focus mode. */
internal enum class FocusVerdict {
    ALLOW,
    BLOCK,
}

/** Pages that only list other videos or people: Shorts, search, feeds, channels. */
private val DISTRACTING_PATH_PREFIXES =
    listOf("/shorts", "/results", "/feed", "/channel", "/c/", "/user", "/@", "/hashtag", "/gaming", "/premium", "/playables")

/**
 * A lesson video, the (hidden) home page and YouTube's own account pages are allowed; any other video and
 * the listing pages are not. [isLessonVideo] says whether a video belongs to the doctor's lessons.
 */
internal fun focusVerdict(
    url: String,
    isLessonVideo: (String) -> Boolean,
): FocusVerdict {
    val path =
        runCatching { URI(url).path }
            .getOrNull()
            .orEmpty()
            .lowercase()
    if (DISTRACTING_PATH_PREFIXES.any { path.startsWith(it) }) return FocusVerdict.BLOCK
    val videoId = YouTubeUrlParser.extractVideoId(url) ?: return FocusVerdict.ALLOW
    return if (isLessonVideo(videoId)) FocusVerdict.ALLOW else FocusVerdict.BLOCK
}

/** Whether [videoId] is a lecture of the catalog; null [sections] (not loaded yet) knows nothing. */
internal fun catalogHasVideo(
    sections: List<CatalogSection>?,
    videoId: String,
): Boolean? = sections?.any { section -> section.topics.any { topic -> topic.videos.any { it.videoId == videoId } } }

internal const val FOCUS_BLOCKED_MESSAGE =
    "Bu video SHOKALON mundarijasida yo‘q. Darslarni “Ultratovush bo‘limlari” dan tanlang."

/** The open lecture's place in its catalog section: which lectures come before and after it there. */
internal data class LessonSteps(
    val videoId: String,
    val section: String,
    val position: Int,
    val total: Int,
    val previous: CatalogVideo?,
    val next: CatalogVideo?,
)

/** The open lecture's neighbours within its section (topics in catalog order); null when it is not a catalog lecture. */
internal fun lessonSteps(
    sections: List<CatalogSection>?,
    videoId: String?,
): LessonSteps? {
    if (sections == null || videoId == null) return null
    for (section in sections) {
        val videos = section.topics.flatMap { it.videos }.distinctBy { it.videoId }
        val index = videos.indexOfFirst { it.videoId == videoId }
        if (index < 0) continue
        return LessonSteps(
            videoId = videoId,
            section = section.name,
            position = index + 1,
            total = videos.size,
            previous = videos.getOrNull(index - 1),
            next = videos.getOrNull(index + 1),
        )
    }
    return null
}

/** The steps of the open lecture, published by the step bar for the YouTube page's own next/previous buttons. */
internal val focusLessonSteps = MutableStateFlow<LessonSteps?>(null)

internal fun lessonWatchUrl(videoId: String): String = "https://m.youtube.com/watch?v=$videoId"

/**
 * Makes the player's own "next" and "previous" buttons go to the neighbouring lectures of the section instead
 * of YouTube's suggestions; at either end of the section they do nothing. Without [steps] YouTube keeps them.
 */
internal fun focusStepScript(steps: LessonSteps?): String {
    val previous = JSONObject.quote(steps?.previous?.videoId.orEmpty())
    val next = JSONObject.quote(steps?.next?.videoId.orEmpty())
    val active = steps != null
    return """
        (function() {
          window.__shokalonSteps = { active: $active, prev: $previous, next: $next };
          if (window.__shokalonStepHook) return;
          window.__shokalonStepHook = true;
          const nextLabel = /next|keyingi|следующ/i;
          const previousLabel = /previous|prev-|prev |oldingi|предыдущ/i;
          document.addEventListener('click', function(event) {
            const steps = window.__shokalonSteps || {};
            if (!steps.active || !event.target || !event.target.closest) return;
            const control = event.target.closest('button, a, [role="button"]');
            if (!control) return;
            const inPlayer = control.closest(
              '#player-container-id, #player, ytm-player, .html5-video-player, .player-controls-content, ytm-custom-control');
            if (!inPlayer) return;
            const label = [control.getAttribute('aria-label'), control.getAttribute('title'), String(control.className || '')].join(' ');
            const isNext = nextLabel.test(label);
            const isPrevious = !isNext && previousLabel.test(label);
            if (!isNext && !isPrevious) return;
            event.preventDefault();
            event.stopImmediatePropagation();
            const target = isNext ? steps.next : steps.prev;
            if (target) location.href = 'https://m.youtube.com/watch?v=' + target;
          }, true);
        })();
        """.trimIndent()
}
