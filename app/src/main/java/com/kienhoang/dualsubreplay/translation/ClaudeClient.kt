package com.kienhoang.dualsubreplay.translation

import com.kienhoang.dualsubreplay.data.ApiUsageEntry
import com.kienhoang.dualsubreplay.data.ApiUsageLog
import com.kienhoang.dualsubreplay.data.GlossaryEntry
import com.kienhoang.dualsubreplay.data.LearnedTerm
import com.kienhoang.dualsubreplay.data.chunkIdOf
import com.kienhoang.dualsubreplay.data.learnedTermsIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Sends one chunk; replaced by a fake in unit tests. Returns the raw Messages API reply. */
internal fun interface ClaudeTransport {
    @Throws(IOException::class)
    suspend fun send(
        apiKey: String,
        body: String,
    ): String
}

/** An HTTP failure with its status, so busy replies can be retried and others reported at once. */
internal class ClaudeHttpException(
    val code: Int,
    message: String,
    val retryAfterMs: Long?,
) : IOException(message)

/** The real transport: a plain HTTPS POST to the Messages API with the doctor's own key. */
internal object HttpClaudeTransport : ClaudeTransport {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 180_000
    private const val MS_PER_SECOND = 1_000L

    override suspend fun send(
        apiKey: String,
        body: String,
    ): String =
        withContext(Dispatchers.IO) {
            val connection = URL(CLAUDE_API_URL).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("content-type", "application/json")
                connection.setRequestProperty("x-api-key", apiKey)
                connection.setRequestProperty("anthropic-version", CLAUDE_API_VERSION)
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    val retryAfter =
                        connection
                            .getHeaderField("retry-after")
                            ?.trim()
                            ?.toLongOrNull()
                            ?.times(MS_PER_SECOND)
                    throw ClaudeHttpException(code, claudeHttpError(code, text), retryAfter)
                }
                text
            } finally {
                connection.disconnect()
            }
        }
}

/** Token counts of one reply, as the provider reported them. */
internal data class TokenUsage(
    val input: Long,
    val output: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
    val reported: Boolean,
) {
    companion object {
        val UNREPORTED = TokenUsage(0, 0, 0, 0, reported = false)
    }
}

/** Where a backend reports each API call (the lesson run adds the lesson id). */
internal typealias ApiCallSink = (ApiUsageEntry) -> Unit

/** One call for the usage log; [chunk] null for calls outside a lesson run. */
internal fun apiCall(
    timeMs: Long,
    provider: String,
    model: String,
    chunk: ClaudeChunk?,
    usage: TokenUsage,
    status: String,
    success: Boolean,
): ApiUsageEntry =
    ApiUsageEntry(
        timeMs = timeMs,
        lessonId = "",
        chunkId = chunk?.let { chunkIdOf(it.sentences.indices.map { offset -> it.start + offset }) }.orEmpty(),
        provider = provider,
        model = model,
        inputTokens = usage.input,
        outputTokens = usage.output,
        cacheReadTokens = usage.cacheRead,
        cacheWriteTokens = usage.cacheWrite,
        tokensReported = usage.reported,
        status = status,
        fallback = false,
        success = success,
    )

/**
 * Stops a lesson run with a reason for the doctor: a wrong key, no balance, a refused request, retries used
 * up or the Claude backup limit reached. Never a reason to ask another provider.
 */
internal class TranslationStoppedException(
    message: String,
) : IOException(message)

/** Progress of a lesson translation, for the lesson screen and the toast at the end. */
internal data class ClaudeProgress(
    val done: Int,
    val total: Int,
)

internal data class ClaudeLessonResult(
    val translations: Map<Int, String>,
    val newTerms: List<LearnedTerm>,
    val uncertain: List<ClaudeUncertainty>,
)

/** One engine's answer to one chunk; Claude and Gemini implement it. */
internal fun interface LessonBackend {
    @Throws(IOException::class)
    suspend fun translate(
        userPrompt: String,
        chunk: ClaudeChunk,
    ): ClaudeChunkResult
}

/**
 * Translates a whole lesson chunk by chunk with [backend]. A reply that is cut short or misses items
 * is retried as two smaller chunks; other failures stop the run with their reason. [terms] is asked
 * again before every chunk, so terms suggested in an earlier chunk (or approved meanwhile) are used
 * in the next ones. [chunkSize] is small for Claude (cheap retries) and the whole lesson for Gemini
 * (one request of the free daily quota per lesson).
 */
internal class LessonTranslator(
    private val backend: LessonBackend,
    private val chunkSize: Int = CLAUDE_CHUNK_SIZE,
) {
    /**
     * [done] are sentences already translated in an earlier run: they are not sent again, only given as
     * context, and [onChunk] is called for the new chunks only.
     */
    suspend fun translate(
        sentences: List<String>,
        glossaryFor: (List<String>) -> List<GlossaryEntry>,
        terms: () -> List<LearnedTerm>,
        done: Map<Int, String> = emptyMap(),
        drafts: (suspend (List<String>) -> List<String>)? = null,
        onChunk: suspend (ClaudeChunkResult, ClaudeProgress) -> Unit,
    ): ClaudeLessonResult {
        val finished = linkedMapOf<Int, String>()
        done.forEach { (index, uzbek) -> if (index in sentences.indices && uzbek.isNotBlank()) finished[index] = uzbek }
        val newTerms = mutableListOf<LearnedTerm>()
        val uncertain = mutableListOf<ClaudeUncertainty>()
        val queue = ArrayDeque(missingChunks(sentences, finished.keys, chunkSize))
        while (queue.isNotEmpty()) {
            val chunk = queue.removeFirst()
            val texts = chunk.sentences
            val prompt =
                claudeUserPrompt(chunk, sentences, finished, glossaryFor(texts), learnedTermsIn(terms(), texts), draftsOf(drafts, texts))
            val result =
                try {
                    backend.translate(prompt, chunk)
                } catch (error: ClaudeReplyException) {
                    val halves = splitClaudeChunk(chunk) ?: throw error
                    halves.asReversed().forEach(queue::addFirst)
                    continue
                }
            finished.putAll(result.translations)
            newTerms += result.newTerms
            uncertain += result.uncertain
            onChunk(result, ClaudeProgress(finished.size, sentences.size))
        }
        return ClaudeLessonResult(finished, newTerms, uncertain)
    }
}

/**
 * The machine drafts of [texts], or none when the draft service fails: the translator then works from the
 * English alone, as before. A cancelled run is never swallowed.
 */
internal suspend fun draftsOf(
    drafts: (suspend (List<String>) -> List<String>)?,
    texts: List<String>,
): List<String> =
    try {
        drafts?.invoke(texts).orEmpty()
    } catch (error: IOException) {
        emptyList()
    } catch (error: IllegalArgumentException) {
        emptyList()
    }

/** Claude: forced tool call; a busy service is retried after a pause, a wrong key or model is not. */
internal class ClaudeBackend(
    private val apiKey: String,
    val model: String,
    private val transport: ClaudeTransport = HttpClaudeTransport,
    private val retryPausesMs: List<Long> = listOf(3_000L, 10_000L, 30_000L),
    private val onCall: ApiCallSink = ApiUsageLog::record,
    private val now: () -> Long = System::currentTimeMillis,
) : LessonBackend {
    init {
        require(apiKey.isNotBlank()) { "Claude API kalitini kiriting." }
    }

    override suspend fun translate(
        userPrompt: String,
        chunk: ClaudeChunk,
    ): ClaudeChunkResult {
        val reply = request(claudeRequestBody(model, userPrompt), chunk)
        val result =
            try {
                parseClaudeReply(reply, chunk)
            } catch (error: ClaudeReplyException) {
                log(chunk, claudeUsage(reply), STATUS_BAD_REPLY, success = false)
                throw error
            }
        log(chunk, claudeUsage(reply), STATUS_OK, success = true)
        return result
    }

    /**
     * Sends one Messages API request, retrying a busy service; returns the raw reply. Every failed attempt
     * is logged; a successful one too when [chunk] is null (the lesson run logs its own after reading it).
     */
    suspend fun request(
        body: String,
        chunk: ClaudeChunk? = null,
    ): String {
        var attempt = 0
        while (true) {
            try {
                val reply = transport.send(apiKey, body)
                if (chunk == null) log(null, claudeUsage(reply), STATUS_OK, success = true)
                return reply
            } catch (error: ClaudeHttpException) {
                log(chunk, TokenUsage.UNREPORTED, error.code.toString(), success = false)
                if (!isRetryableClaudeStatus(error.code) || attempt >= retryPausesMs.size) throw error
                delay(error.retryAfterMs?.coerceAtMost(MAX_RETRY_AFTER_MS) ?: retryPausesMs[attempt])
                attempt++
            } catch (error: IOException) {
                log(chunk, TokenUsage.UNREPORTED, STATUS_NETWORK, success = false)
                throw error
            }
        }
    }

    private fun log(
        chunk: ClaudeChunk?,
        usage: TokenUsage,
        status: String,
        success: Boolean,
    ) = onCall(apiCall(now(), "Claude", model, chunk, usage, status, success))

    private companion object {
        const val MAX_RETRY_AFTER_MS = 60_000L
    }
}

internal const val STATUS_OK = "200"
internal const val STATUS_BAD_REPLY = "bad_reply"
internal const val STATUS_NETWORK = "network"
