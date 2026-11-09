package com.kienhoang.dualsubreplay.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeSearchTest {
    private fun video(
        id: String,
        title: String,
        length: String?,
    ): String {
        val lengthField = length?.let { ""","lengthText":{"simpleText":"$it"}""" }.orEmpty()
        return """{"videoRenderer":{"videoId":"$id","title":{"runs":[{"text":"$title"}]},""" +
            """"ownerText":{"runs":[{"text":"SonoNerds"}]},"shortViewCountText":{"simpleText":"12K views"},""" +
            """"publishedTimeText":{"simpleText":"2 years ago"}$lengthField}}"""
    }

    @Test
    fun videosAreFoundWhereverYouTubePutsThem() {
        val json =
            """{"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[""" +
                """{"itemSectionRenderer":{"contents":[""" +
                video("jv748J0XB-0", "Ultrasound Physics 1", "12:34") + "," +
                video("abcdefghijk", "Live now", null) + "," +
                """{"reelShelfRenderer":{"items":[]}},""" +
                video("jv748J0XB-0", "Repeat", "12:34") + "," +
                video("x6hXhLNc-jU", "Liver ultrasound", "1:02:03") +
                """]}}]}}}}}"""
        val results = parseYouTubeSearch(json)
        assertEquals(listOf("jv748J0XB-0", "x6hXhLNc-jU"), results.map { it.videoId })
        assertEquals("Ultrasound Physics 1", results[0].title)
        assertEquals("SonoNerds", results[0].channel)
        assertEquals("12:34", results[0].duration)
        assertEquals("12K views", results[0].views)
    }

    @Test
    fun theRequestAsksForVideosOnly() {
        val body = JSONObject(youtubeSearchBody("gallbladder ultrasound"))
        assertEquals("gallbladder ultrasound", body.getString("query"))
        assertEquals("EgIQAQ%3D%3D", body.getString("params"))
        assertTrue(body.getJSONObject("context").getJSONObject("client").getString("clientName") == "WEB")
    }
}
