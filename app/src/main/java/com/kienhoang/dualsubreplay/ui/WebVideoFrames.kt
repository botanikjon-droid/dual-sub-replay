package com.kienhoang.dualsubreplay.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.webkit.WebView
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONException
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/*
 * Pictures for the lesson notes: the frame of the lecture video on the screen, copied from the app's own
 * window (like the phone's screenshot), never downloaded from YouTube. The page's video is paused at the
 * wanted second, the video element's place is read from the page, and that part of the window is copied.
 */

/** What taking one frame gave. */
internal sealed interface FrameGrab {
    data class Taken(
        val bitmap: Bitmap,
        val seconds: Int,
    ) : FrameGrab

    /** The copy was black: this phone does not let the app copy the video picture. */
    data object Black : FrameGrab

    data class Failed(
        val reason: String,
    ) : FrameGrab
}

/** The single WebView registers here, so the lesson notes can take frames without holding the view. */
internal object VideoFrameSource {
    /** Takes the frame at [second] (null: where the video is now). Null while no lesson page is shown. */
    @Volatile var grab: (suspend (second: Float?) -> FrameGrab)? = null

    /** The "📷" button above the subtitles: the frame on the screen goes into the notes. Set by AppViewModel. */
    @Volatile var onCaptureNow: (() -> Unit)? = null
}

/** Pauses the page video at [second], or where it is when null; re-checks the YouTube origin first. */
internal fun webSeekPausedScript(second: Float?): String {
    val seek =
        second
            ?.takeIf { it.isFinite() }
            ?.coerceAtLeast(0f)
            ?.let { "video.currentTime = $it;" }
            .orEmpty()
    return """
        (function() {
          const host = window.location.hostname.toLowerCase().replace(/\.$/, '');
          if (window.location.protocol !== 'https:' ||
              !(host === 'youtube.com' || host.endsWith('.youtube.com'))) return false;
          clearInterval(window.__dualSubClipTimer);
          const videos = Array.from(document.querySelectorAll('video'));
          const video = videos.find(function(item) { return item.readyState > 0; }) || videos[0];
          if (!video) return false;
          video.pause();
          $seek
          return true;
        })();
        """.trimIndent()
}

/** The video element's place in CSS pixels, the page's pixel ratio and whether the frame is ready. */
internal const val WEB_VIDEO_RECT_SCRIPT =
    """
    (function() {
      const host = window.location.hostname.toLowerCase().replace(/\.$/, '');
      if (window.location.protocol !== 'https:' ||
          !(host === 'youtube.com' || host.endsWith('.youtube.com'))) return null;
      const videos = Array.from(document.querySelectorAll('video'));
      const video = videos.find(function(item) { return item.readyState > 0; }) || videos[0];
      if (!video) return null;
      const box = video.getBoundingClientRect();
      return { x: box.left, y: box.top, w: box.width, h: box.height, dpr: window.devicePixelRatio || 1,
               ready: !video.seeking && video.readyState >= 2, t: video.currentTime };
    })();
    """

internal data class VideoBox(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val ready: Boolean,
    val seconds: Int,
)

/** Reads [WEB_VIDEO_RECT_SCRIPT]'s answer in device pixels relative to the WebView; null when unusable. */
internal fun parseVideoBox(result: String?): VideoBox? {
    if (result.isNullOrBlank() || result == "null") return null
    return try {
        val json = JSONObject(result)
        val ratio = json.optDouble("dpr", 1.0).takeIf { it > 0 } ?: 1.0
        val box =
            VideoBox(
                left = (json.optDouble("x") * ratio).roundToInt(),
                top = (json.optDouble("y") * ratio).roundToInt(),
                width = (json.optDouble("w") * ratio).roundToInt(),
                height = (json.optDouble("h") * ratio).roundToInt(),
                ready = json.optBoolean("ready"),
                seconds = json.optDouble("t", 0.0).toInt(),
            )
        box.takeIf { it.width > MIN_FRAME_SIDE && it.height > MIN_FRAME_SIDE }
    } catch (error: JSONException) {
        null
    }
}

/**
 * True when a frame is (almost) all black: the phone protected the video layer from copying. Checks a
 * grid of [pixels] (ARGB) for any visible brightness; an ultrasound image always has bright tissue.
 */
internal fun looksBlack(pixels: IntArray): Boolean {
    if (pixels.isEmpty()) return true
    var bright = 0
    for (pixel in pixels) {
        val r = pixel shr RED_SHIFT and CHANNEL
        val g = pixel shr GREEN_SHIFT and CHANNEL
        val b = pixel and CHANNEL
        if (r + g + b > DARK_SUM) bright++
    }
    return bright * BRIGHT_SHARE_DIVISOR < pixels.size
}

/** Takes the lecture frame at [second] (or the current one) from this WebView's window. */
internal suspend fun WebView.grabVideoFrame(second: Float?): FrameGrab {
    if (!isYouTubeWebUrl(url.orEmpty())) return FrameGrab.Failed("YouTube darsi ochiq emas.")
    val activity = context.findActivity() ?: return FrameGrab.Failed("Oyna topilmadi.")
    if (evaluate(webSeekPausedScript(second)) != "true") return FrameGrab.Failed("Videoni to‘xtatib bo‘lmadi.")
    var box: VideoBox? = null
    for (attempt in 0 until READY_POLLS) {
        box = parseVideoBox(evaluate(WEB_VIDEO_RECT_SCRIPT))
        if (box?.ready == true) break
        delay(READY_POLL_MS)
    }
    // The decoder paints the sought frame a moment after the page reports it ready.
    delay(PAINT_WAIT_MS)
    box = parseVideoBox(evaluate(WEB_VIDEO_RECT_SCRIPT)) ?: box
    val found = box ?: return FrameGrab.Failed("Video topilmadi.")
    val origin = IntArray(2).also(::getLocationInWindow)
    val decor = activity.window.decorView
    val area =
        Rect(
            (origin[0] + found.left).coerceAtLeast(0),
            (origin[1] + found.top).coerceAtLeast(0),
            (origin[0] + found.left + found.width).coerceAtMost(decor.width),
            (origin[1] + found.top + found.height).coerceAtMost(decor.height),
        )
    if (area.width() < MIN_FRAME_SIDE || area.height() < MIN_FRAME_SIDE) return FrameGrab.Failed("Video ekranda ko‘rinmayapti.")
    val bitmap = Bitmap.createBitmap(area.width(), area.height(), Bitmap.Config.ARGB_8888)
    val result =
        suspendCancellableCoroutine { continuation ->
            PixelCopy.request(activity.window, area, bitmap, { continuation.resume(it) }, Handler(Looper.getMainLooper()))
        }
    if (result != PixelCopy.SUCCESS) return FrameGrab.Failed("Kadrni nusxalab bo‘lmadi (kod $result).")
    if (looksBlack(sample(bitmap))) return FrameGrab.Black
    return FrameGrab.Taken(bitmap, second?.toInt() ?: found.seconds)
}

private fun sample(bitmap: Bitmap): IntArray {
    val pixels = mutableListOf<Int>()
    for (row in 1 until SAMPLE_GRID) {
        for (column in 1 until SAMPLE_GRID) {
            pixels += bitmap.getPixel(bitmap.width * column / SAMPLE_GRID, bitmap.height * row / SAMPLE_GRID)
        }
    }
    return pixels.toIntArray()
}

private suspend fun WebView.evaluate(script: String): String? =
    suspendCancellableCoroutine { continuation -> evaluateJavascript(script) { continuation.resume(it) } }

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private const val MIN_FRAME_SIDE = 40
private const val READY_POLLS = 20
private const val READY_POLL_MS = 250L
private const val PAINT_WAIT_MS = 600L
private const val SAMPLE_GRID = 16
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val CHANNEL = 0xFF
private const val DARK_SUM = 60
private const val BRIGHT_SHARE_DIVISOR = 50
