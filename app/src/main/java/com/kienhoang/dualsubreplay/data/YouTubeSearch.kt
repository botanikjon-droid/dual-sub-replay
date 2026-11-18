package com.kienhoang.dualsubreplay.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/*
 * "Qo‘shimcha videolar": the doctor searches YouTube for more on the open lesson from inside SHOKALON. The
 * same public search YouTube's own web page uses (no key of the doctor's), videos only; the results are shown
 * as the app's own list, so the YouTube page with its suggestions is never opened for a search.
 */

/** One video found by a search. */
internal data class YouTubeSearchResult(
    val videoId: String,
    val title: String,
    val channel: String,
    val duration: String,
    val views: String,
    val published: String,
)

private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
private const val MAX_RESULTS = 25

/** The request body: [query], videos only ("EgIQAQ==" is YouTube's own "Type: Video" filter). */
internal fun youtubeSearchBody(
    query: String,
    clientVersion: String = SEARCH_WEB_CLIENT_VERSION,
): String =
    JSONObject()
        .put(
            "context",
            JSONObject().put(
                "client",
                JSONObject()
                    .put("clientName", "WEB")
                    .put("clientVersion", clientVersion)
                    .put("hl", "en")
                    .put("gl", "US"),
            ),
        ).put("query", query)
        .put("params", "EgIQAQ%3D%3D")
        .toString()

/** Every video in a search response, wherever YouTube put it; live streams, Shorts and repeats are left out. */
internal fun parseYouTubeSearch(json: String): List<YouTubeSearchResult> {
    val results = linkedMapOf<String, YouTubeSearchResult>()

    fun visit(node: Any?) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("videoRenderer")?.let { video -> videoResult(video)?.let { results.putIfAbsent(it.videoId, it) } }
                node.keys().forEach { key -> if (key != "videoRenderer") visit(node.opt(key)) }
            }
            is JSONArray -> for (index in 0 until node.length()) visit(node.opt(index))
        }
    }
    visit(JSONObject(json))
    return results.values.take(MAX_RESULTS)
}

private fun JSONObject.text(name: String): String {
    val field = optJSONObject(name) ?: return ""
    field.optString("simpleText").takeIf(String::isNotBlank)?.let { return it }
    val runs = field.optJSONArray("runs") ?: return ""
    return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
}

private fun videoResult(video: JSONObject): YouTubeSearchResult? {
    val videoId = video.optString("videoId").takeIf(VIDEO_ID::matches) ?: return null
    val duration = video.text("lengthText")
    // No length: a live stream or an upcoming premiere, not a lecture to watch.
    if (duration.isBlank()) return null
    return YouTubeSearchResult(
        videoId = videoId,
        title = video.text("title").ifBlank { return null },
        channel = video.text("ownerText").ifBlank { video.text("longBylineText") },
        duration = duration,
        views = video.text("shortViewCountText").ifBlank { video.text("viewCountText") },
        published = video.text("publishedTimeText"),
    )
}

internal const val SEARCH_WEB_CLIENT_VERSION = "2.20260708.00.00"
private const val SEARCH_URL = "https://www.youtube.com/youtubei/v1/search?prettyPrint=false"
private const val SEARCH_TIMEOUT_S = 20L
private const val THUMBNAIL_CACHE = 60
private const val SEARCH_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"

/** Searches YouTube for [query]; throws IOException with an Uzbek message when it cannot. */
internal object YouTubeSearch {
    private val client: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(SEARCH_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(SEARCH_TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(SEARCH_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    private val thumbnails =
        object : LinkedHashMap<String, ByteArray>(THUMBNAIL_CACHE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > THUMBNAIL_CACHE
        }

    /** The small picture of [videoId] (320x180 JPEG), kept for the session; null when it cannot be had. */
    suspend fun thumbnail(videoId: String): ByteArray? {
        synchronized(thumbnails) { thumbnails[videoId] }?.let { return it }
        if (!VIDEO_ID.matches(videoId)) return null
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("https://i.ytimg.com/vi/$videoId/mqdefault.jpg").build()
                client.newCall(request).execute().use { response ->
                    response.body?.bytes()?.takeIf { response.isSuccessful && it.isNotEmpty() }
                }
            } catch (error: IOException) {
                null
            }?.also { bytes -> synchronized(thumbnails) { thumbnails[videoId] = bytes } }
        }
    }

    suspend fun search(query: String): List<YouTubeSearchResult> =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(SEARCH_URL)
                    .header("User-Agent", SEARCH_USER_AGENT)
                    .header("Origin", "https://www.youtube.com")
                    .header("X-YouTube-Client-Name", "1")
                    .header("X-YouTube-Client-Version", SEARCH_WEB_CLIENT_VERSION)
                    .post(youtubeSearchBody(query).toRequestBody("application/json".toMediaType()))
                    .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("YouTube qidiruvi javob bermadi (HTTP ${response.code}).")
                    parseYouTubeSearch(response.body?.string().orEmpty())
                }
            } catch (error: org.json.JSONException) {
                throw IOException("YouTube qidiruvi tushunarsiz javob qaytardi.", error)
            } catch (error: IOException) {
                throw IOException(error.message ?: "Internetga ulanib bo‘lmadi.", error)
            }
        }
}
