package com.kienhoang.dualsubreplay.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Asks the real YouTube search through the app's own client; only in the uz-voice-check workflow. */
class YouTubeSearchLiveTest {
    @Test
    fun aLessonTitleFindsVideos() =
        runBlocking {
            assumeTrue(System.getenv("LIVE_VOICE_TEST") == "1")
            val results = YouTubeSearch.search("gallbladder ultrasound artifacts")
            println(
                "search: ${results.size} videos; first: ${results.take(
                    3,
                ).joinToString(" | ") { "${it.title} (${it.duration}, ${it.channel})" }}",
            )
            assertTrue("YouTube search returned no videos", results.size >= 5)
            val picture = YouTubeSearch.thumbnail(results.first().videoId)
            println("thumbnail: ${picture?.size ?: 0} bytes")
            assertTrue("No thumbnail", (picture?.size ?: 0) > 1_000)
        }
}
