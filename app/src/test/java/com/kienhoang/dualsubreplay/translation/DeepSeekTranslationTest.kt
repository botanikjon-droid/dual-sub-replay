package com.kienhoang.dualsubreplay.translation

import com.kienhoang.dualsubreplay.data.ApiUsageEntry
import com.kienhoang.dualsubreplay.data.apiUsageToJson
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class DeepSeekTranslationTest {
    private val chunk = ClaudeChunk(0, listOf("first sentence", "second sentence"))
    private val bigChunk = ClaudeChunk(0, (0 until 10).map { "sentence $it" })

    private fun answer(indices: List<Int> = listOf(0, 1)): String =
        JSONObject()
            .put(
                "translations",
                JSONArray().apply { indices.forEach { put(JSONObject().put("i", it).put("uz", "gap $it")) } },
            ).put("new_terms", JSONArray())
            .put("uncertain", JSONArray())
            .toString()

    private fun completion(
        content: String,
        finishReason: String = "stop",
        usage: JSONObject? = JSONObject().put("prompt_tokens", 1200).put("completion_tokens", 300),
    ): String =
        JSONObject()
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put("finish_reason", finishReason)
                        .put("message", JSONObject().put("role", "assistant").put("content", content)),
                ),
            ).apply { usage?.let { put("usage", it) } }
            .toString()

    /** A transport that answers with [steps] in order (a Throwable is thrown) and counts the calls. */
    private class ScriptedTransport(
        vararg steps: Any,
    ) : DeepSeekTransport {
        private val queue = ArrayDeque(steps.toList())
        var calls = 0

        override suspend fun send(
            apiKey: String,
            body: String,
        ): String {
            calls++
            val step = queue.removeFirstOrNull() ?: error("no more replies")
            if (step is Throwable) throw step
            return step as String
        }
    }

    private class CountingBackend(
        private val reply: (ClaudeChunk) -> ClaudeChunkResult,
    ) : LessonBackend {
        var calls = 0

        override suspend fun translate(
            userPrompt: String,
            chunk: ClaudeChunk,
        ): ClaudeChunkResult {
            calls++
            return reply(chunk)
        }
    }

    private fun claudeLike() =
        CountingBackend { chunk ->
            ClaudeChunkResult(
                chunk.sentences.indices.associate {
                    chunk.start + it to "claude ${chunk.start + it}"
                },
                emptyList(),
                emptyList(),
                "Claude",
            )
        }

    private fun deepSeek(
        transport: DeepSeekTransport,
        log: MutableList<ApiUsageEntry> = mutableListOf(),
        pauses: MutableList<Long> = mutableListOf(),
    ) = DeepSeekBackend("sk-secret-key", transport, retryPausesMs = listOf(3_000L, 10_000L), onCall = { log += it }, now = { 42L }) {
        pauses += it
    }

    private fun stopped(block: suspend () -> Unit): TranslationStoppedException {
        try {
            runBlocking { block() }
        } catch (expected: TranslationStoppedException) {
            return expected
        }
        fail("the run must stop")
        error("unreachable")
    }

    @Test
    fun requestAsksForJsonObjectWithTheSharedPrompt() {
        val body = JSONObject(deepSeekRequestBody("{}"))
        assertEquals(DEEPSEEK_MODEL, body.getString("model"))
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
        val system = body.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(system.contains("JSON schema"))
    }

    @Test
    fun replyIsReadAsLessonAnswerWithItsProvider() {
        val result = parseDeepSeekReply(completion("```json\n" + answer() + "\n```"), chunk)
        assertEquals("gap 0", result.translations[0])
        assertEquals("gap 1", result.translations[1])
        assertEquals("DeepSeek", result.engine)
    }

    @Test
    fun cutReplyIsReportedSoTheChunkIsSplit() {
        try {
            parseDeepSeekReply(completion(answer(), finishReason = "length"), chunk)
            fail("a cut reply must not be accepted")
        } catch (expected: ClaudeReplyException) {
            assertTrue(expected.message.orEmpty().contains("kesilib"))
        }
    }

    @Test
    fun wrongKeyStopsAtOnceWithoutClaude() {
        val transport = ScriptedTransport(DeepSeekHttpException(401, deepSeekHttpError(401, ""), null))
        val claude = claudeLike()
        val backend = FallbackLessonBackend(deepSeek(transport), claude, limit = 5)
        val error = stopped { backend.translate("{}", chunk) }
        assertTrue(error.message.orEmpty().contains("401"))
        assertEquals(1, transport.calls)
        assertEquals(0, claude.calls)
    }

    @Test
    fun noBalanceStopsAtOnceWithoutClaude() {
        val body = JSONObject().put("error", JSONObject().put("message", "Insufficient Balance")).toString()
        val transport = ScriptedTransport(DeepSeekHttpException(402, deepSeekHttpError(402, body), null))
        val claude = claudeLike()
        val error = stopped { FallbackLessonBackend(deepSeek(transport), claude, limit = 5).translate("{}", chunk) }
        assertTrue(error.message.orEmpty().contains("402"))
        assertTrue(error.message.orEmpty().contains("Insufficient Balance"))
        assertEquals(0, claude.calls)
    }

    @Test
    fun refusedRequestIsNotRetried() {
        val transport = ScriptedTransport(DeepSeekHttpException(422, deepSeekHttpError(422, ""), null))
        stopped { deepSeek(transport).translate("{}", chunk) }
        assertEquals(1, transport.calls)
    }

    @Test
    fun rateLimitWaitsForRetryAfterThenSucceeds() {
        val transport = ScriptedTransport(DeepSeekHttpException(429, "limit", 7_000L), completion(answer()))
        val pauses = mutableListOf<Long>()
        val result = runBlocking { deepSeek(transport, pauses = pauses).translate("{}", chunk) }
        assertEquals("gap 0", result.translations[0])
        assertEquals(listOf(7_000L), pauses)
        assertEquals(2, transport.calls)
    }

    @Test
    fun rateLimitIsRetriedAtMostTwice() {
        val limit = { DeepSeekHttpException(429, "limit", null) }
        val transport = ScriptedTransport(limit(), limit(), limit(), completion(answer()))
        val pauses = mutableListOf<Long>()
        stopped { deepSeek(transport, pauses = pauses).translate("{}", chunk) }
        assertEquals(3, transport.calls)
        assertEquals(listOf(3_000L, 10_000L), pauses)
    }

    @Test
    fun serverErrorsAreRetriedAtMostTwiceAndNeverGoToClaude() {
        val busy = { DeepSeekHttpException(503, "busy", null) }
        val transport = ScriptedTransport(busy(), DeepSeekHttpException(500, "error", null), busy())
        val claude = claudeLike()
        stopped { FallbackLessonBackend(deepSeek(transport), claude, limit = 5).translate("{}", chunk) }
        assertEquals(3, transport.calls)
        assertEquals(0, claude.calls)
    }

    @Test
    fun networkFailureIsRetriedThenSucceeds() {
        val transport = ScriptedTransport(SocketTimeoutException("timeout"), completion(answer()))
        val log = mutableListOf<ApiUsageEntry>()
        runBlocking { deepSeek(transport, log).translate("{}", chunk) }
        assertEquals(listOf("network", "200"), log.map { it.status })
    }

    @Test
    fun brokenJsonOfASmallChunkGoesToClaudeWithinTheLimit() {
        val transport = ScriptedTransport(completion("not json"), completion("{also broken"), completion("[]"))
        val claude = claudeLike()
        val backend = FallbackLessonBackend(deepSeek(transport), claude, limit = 2)
        val first = runBlocking { backend.translate("{}", chunk) }
        assertEquals("Claude", first.engine)
        runBlocking { backend.translate("{}", chunk) }
        assertEquals(2, claude.calls)
        val error = stopped { backend.translate("{}", chunk) }
        assertTrue(error.message.orEmpty().contains("limit"))
        assertEquals(2, claude.calls)
        assertEquals(2, backend.used)
    }

    @Test
    fun brokenReplyOfABigChunkIsSplitBeforeAnyClaude() {
        val transport = ScriptedTransport(completion("not json"))
        val claude = claudeLike()
        try {
            runBlocking { FallbackLessonBackend(deepSeek(transport), claude, limit = 5).translate("{}", bigChunk) }
            fail("a big chunk must go back to the translator to be halved")
        } catch (expected: ClaudeReplyException) {
            assertEquals(0, claude.calls)
        }
    }

    @Test
    fun missingSentencesWithoutClaudeKeyStopTheRun() {
        val transport = ScriptedTransport(completion(answer(listOf(0))))
        val error = stopped { FallbackLessonBackend(deepSeek(transport), fallback = null, limit = 5).translate("{}", chunk) }
        assertTrue(error.message.orEmpty().contains("Claude kaliti"))
    }

    @Test
    fun zeroLimitTurnsTheBackupOff() {
        val claude = claudeLike()
        stopped { FallbackLessonBackend(deepSeek(ScriptedTransport(completion("x"))), claude, limit = 0).translate("{}", chunk) }
        assertEquals(0, claude.calls)
    }

    @Test
    fun everyCallIsLoggedWithTokensAndCacheButNeverTheKey() {
        val usage =
            JSONObject()
                .put("prompt_tokens", 1000)
                .put("completion_tokens", 250)
                .put("prompt_cache_hit_tokens", 800)
                .put("prompt_cache_miss_tokens", 200)
        val transport = ScriptedTransport(DeepSeekHttpException(503, "busy", null), completion(answer(), usage = usage))
        val log = mutableListOf<ApiUsageEntry>()
        runBlocking { deepSeek(transport, log).translate("{}", chunk) }
        assertEquals(2, log.size)
        val failed = log[0]
        assertEquals("503", failed.status)
        assertFalse(failed.success)
        assertFalse(failed.tokensReported)
        val ok = log[1]
        assertEquals("0-1", ok.chunkId)
        assertEquals("DeepSeek", ok.provider)
        assertEquals(DEEPSEEK_MODEL, ok.model)
        assertEquals(200L, ok.inputTokens)
        assertEquals(250L, ok.outputTokens)
        assertEquals(800L, ok.cacheReadTokens)
        assertTrue(ok.success)
        assertTrue(log.none { apiUsageToJson(it).contains("sk-secret-key") })
    }

    @Test
    fun replyWithoutUsageIsNotCountedAsFree() {
        val log = mutableListOf<ApiUsageEntry>()
        runBlocking { deepSeek(ScriptedTransport(completion(answer(), usage = null)), log).translate("{}", chunk) }
        assertFalse(log.single().tokensReported)
        assertEquals(0L, log.single().inputTokens)
    }

    @Test
    fun usageWithoutCacheFieldsCountsAllPromptTokensAsInput() {
        val usage = deepSeekUsage(completion(answer()))
        assertEquals(TokenUsage(1200, 300, 0, 0, reported = true), usage)
    }

    @Test
    fun otherErrorsAreNotAReasonForClaude() {
        val claude = claudeLike()
        val backend = FallbackLessonBackend(LessonBackend { _, _ -> throw IOException("something else") }, claude, limit = 5)
        try {
            runBlocking { backend.translate("{}", chunk) }
            fail("the error must reach the caller")
        } catch (expected: IOException) {
            assertEquals("something else", expected.message)
        }
        assertEquals(0, claude.calls)
    }
}
