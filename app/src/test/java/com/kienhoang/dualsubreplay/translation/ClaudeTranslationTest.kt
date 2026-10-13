package com.kienhoang.dualsubreplay.translation

import com.kienhoang.dualsubreplay.data.LearnedTerm
import com.kienhoang.dualsubreplay.data.LearnedTermStatus
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class ClaudeTranslationTest {
    private val lesson = (0 until 12).map { "sentence $it" }

    /** A Messages API reply whose tool call translates [indices] (or the given pairs). */
    private fun reply(
        translations: List<Pair<Int, String>>,
        newTerms: List<Pair<String, String>> = emptyList(),
        uncertain: List<Int> = emptyList(),
        stopReason: String = "tool_use",
    ): String {
        val input =
            JSONObject()
                .put(
                    "translations",
                    JSONArray().apply { translations.forEach { (i, uz) -> put(JSONObject().put("i", i).put("uz", uz)) } },
                ).put(
                    "new_terms",
                    JSONArray().apply { newTerms.forEach { (en, uz) -> put(JSONObject().put("en", en).put("uz", uz)) } },
                ).put(
                    "uncertain",
                    JSONArray().apply {
                        uncertain.forEach {
                            put(
                                JSONObject()
                                    .put(
                                        "i",
                                        it,
                                    ).put("heard", "a situs")
                                    .put("options", JSONArray(listOf("ascites", "cyst")))
                                    .put("reason", "ASR"),
                            )
                        }
                    },
                )
        return JSONObject()
            .put("stop_reason", stopReason)
            .put(
                "content",
                JSONArray().put(JSONObject().put("type", "tool_use").put("name", CLAUDE_TOOL_NAME).put("input", input)),
            ).toString()
    }

    private fun itemsOf(body: String): List<Int> {
        val prompt = JSONObject(JSONObject(body).getJSONArray("messages").getJSONObject(0).getString("content"))
        val items = prompt.getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it).getInt("i") }
    }

    @Test
    fun chunksCoverEveryLessonSentenceOnceInOrder() {
        val chunks = claudeChunks(lesson, size = 5)
        assertEquals(listOf(0, 5, 10), chunks.map { it.start })
        assertEquals(lesson, chunks.flatMap { it.sentences })
        assertEquals(
            listOf(ClaudeChunk(0, lesson.take(3)), ClaudeChunk(3, lesson.subList(3, 6))),
            splitClaudeChunk(ClaudeChunk(0, lesson.take(6)), 2),
        )
        assertNull(splitClaudeChunk(ClaudeChunk(0, lesson.take(5)), 5))
    }

    @Test
    fun promptCarriesContextFinishedUzbekAndTerms() {
        val learned =
            listOf(
                LearnedTerm("splenic window", "taloq oynasi", status = LearnedTermStatus.APPROVED),
                LearnedTerm("echogenic reflex", "exogen signal"),
            )
        val prompt =
            JSONObject(
                claudeUserPrompt(ClaudeChunk(4, lesson.subList(4, 6)), lesson, mapOf(3 to "gap 3"), emptyList(), learned),
            )
        val before = prompt.getJSONArray("context_before")
        assertEquals(3, before.length())
        assertEquals("sentence 3", before.getJSONObject(2).getString("en"))
        assertEquals("gap 3", before.getJSONObject(2).getString("uz"))
        assertFalse(before.getJSONObject(0).has("uz"))
        assertEquals(listOf("sentence 6", "sentence 7"), (0 until 2).map { prompt.getJSONArray("context_after").getString(it) })
        assertEquals(4, prompt.getJSONArray("items").getJSONObject(0).getInt("i"))
        assertEquals("taloq oynasi", prompt.getJSONArray("approved_terms").getJSONObject(0).getString("uz"))
        assertEquals("exogen signal", prompt.getJSONArray("suggested_terms").getJSONObject(0).getString("uz"))
    }

    @Test
    fun requestForcesTheTranslationTool() {
        val body = JSONObject(claudeRequestBody("claude-sonnet-5-5", "{}"))
        assertEquals("claude-sonnet-5-5", body.getString("model"))
        assertEquals(CLAUDE_TOOL_NAME, body.getJSONObject("tool_choice").getString("name"))
        assertEquals(CLAUDE_TOOL_NAME, body.getJSONArray("tools").getJSONObject(0).getString("name"))
        assertTrue(body.getString("system").contains("dangling"))
    }

    @Test
    fun replyIsReadAndCheckedAgainstTheChunk() {
        val chunk = ClaudeChunk(2, lesson.subList(2, 4))
        val result =
            parseClaudeReply(reply(listOf(2 to "bo'lib", 3 to "ma'no"), listOf("echogenic reflex" to "exogen signal"), listOf(3)), chunk)
        assertEquals(mapOf(2 to "bo‘lib", 3 to "ma’no"), result.translations)
        assertEquals("echogenic reflex", result.newTerms.single().english)
        assertEquals(listOf("ascites", "cyst"), result.uncertain.single().options)

        // Missing items, items of another chunk, a cut reply and a refusal are errors.
        expectReplyError { parseClaudeReply(reply(listOf(2 to "a", 9 to "b")), chunk) }
        expectReplyError { parseClaudeReply(reply(listOf(2 to "a", 3 to "b"), stopReason = "max_tokens"), chunk) }
        expectReplyError { parseClaudeReply("""{"content":[{"type":"text","text":"no"}]}""", chunk) }
        expectReplyError { parseClaudeReply("not json", chunk) }
    }

    private fun expectReplyError(block: () -> Unit) {
        try {
            block()
            fail("expected ClaudeReplyException")
        } catch (expected: ClaudeReplyException) {
            // expected
        }
    }

    @Test
    fun apostrophesFollowTheSkillRule() {
        assertEquals("o‘tkir g‘ovak ma’no Doppler’s", normalizeUzbekApostrophes("o'tkir gʻovak ma`no Doppler's"))
    }

    @Test
    fun translatedSegmentsKeepTimingAndOrder() {
        val segments = listOf(SubtitleSegment(0, 0, 1000, "a"), SubtitleSegment(1, 1000, 2000, "b"))
        val out = translatedSegments(segments, mapOf(0 to "A", 1 to "B"))
        assertEquals(listOf("A", "B"), out.map { it.originalText })
        assertEquals(listOf(0L, 1000L), out.map { it.startMs })
    }

    @Test
    fun lessonIsTranslatedChunkByChunkAndFailedChunksAreSplit() =
        runBlocking {
            val sent = mutableListOf<List<Int>>()
            var cutOnce = true
            val transport =
                ClaudeTransport { _, body ->
                    val items = itemsOf(body)
                    sent += items
                    if (cutOnce && items.size > 5) {
                        cutOnce = false
                        reply(items.map { it to "uz $it" }, stopReason = "max_tokens")
                    } else {
                        reply(items.map { it to "uz $it" }, newTerms = listOf("term ${items.first()}" to "atama"))
                    }
                }
            val progress = mutableListOf<Int>()
            val result =
                ClaudeLessonTranslator(transport, retryPausesMs = emptyList()).translate(
                    apiKey = "key",
                    model = "m",
                    sentences = lesson,
                    glossaryFor = { emptyList() },
                    terms = { emptyList() },
                ) { _, p -> progress += p.done }
            assertEquals(lesson.indices.associateWith { "uz $it" }, result.translations)
            // The first 12-item chunk was cut, so it was asked again as two halves.
            assertEquals(listOf(lesson.indices.toList(), (0..5).toList(), (6..11).toList()), sent)
            assertEquals(listOf(6, 12), progress)
            assertEquals(2, result.newTerms.size)
        }

    @Test
    fun busyServiceIsRetriedButAWrongKeyStopsAtOnce() =
        runBlocking {
            var calls = 0
            val busyOnce =
                ClaudeTransport { _, body ->
                    calls++
                    if (calls == 1) throw ClaudeHttpException(529, "busy", null)
                    reply(itemsOf(body).map { it to "uz" })
                }
            ClaudeLessonTranslator(busyOnce, retryPausesMs = listOf(1L)).translate("k", "m", lesson.take(2), {
                emptyList()
            }, { emptyList() }) { _, _ -> }
            assertEquals(2, calls)

            val wrongKey = ClaudeTransport { _, _ -> throw ClaudeHttpException(401, claudeHttpError(401, ""), null) }
            try {
                ClaudeLessonTranslator(wrongKey, retryPausesMs = listOf(1L)).translate("k", "m", lesson.take(2), {
                    emptyList()
                }, { emptyList() }) { _, _ -> }
                fail("expected the key error")
            } catch (error: IOException) {
                assertTrue(error.message!!.contains("kaliti"))
            }
        }

    @Test
    fun httpErrorsAreExplainedInUzbek() {
        assertTrue(claudeHttpError(404, """{"error":{"message":"model: x"}}""").startsWith("Tanlangan model topilmadi"))
        assertTrue(isRetryableClaudeStatus(529))
        assertFalse(isRetryableClaudeStatus(401))
    }
}
