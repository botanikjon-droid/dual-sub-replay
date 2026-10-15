package com.kienhoang.dualsubreplay.translation

import com.kienhoang.dualsubreplay.data.GlossaryEntry
import com.kienhoang.dualsubreplay.data.LearnedTerm
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
    suspend fun translate(
        sentences: List<String>,
        glossaryFor: (List<String>) -> List<GlossaryEntry>,
        terms: () -> List<LearnedTerm>,
        onChunk: suspend (ClaudeChunkResult, ClaudeProgress) -> Unit,
    ): ClaudeLessonResult {
        val finished = linkedMapOf<Int, String>()
        val newTerms = mutableListOf<LearnedTerm>()
        val uncertain = mutableListOf<ClaudeUncertainty>()
        val queue = ArrayDeque(claudeChunks(sentences, chunkSize))
        while (queue.isNotEmpty()) {
            val chunk = queue.removeFirst()
            val texts = chunk.sentences
            val prompt = claudeUserPrompt(chunk, sentences, finished, glossaryFor(texts), learnedTermsIn(terms(), texts))
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

/** Claude: forced tool call; a busy service is retried after a pause, a wrong key or model is not. */
internal class ClaudeBackend(
    private val apiKey: String,
    val model: String,
    private val transport: ClaudeTransport = HttpClaudeTransport,
    private val retryPausesMs: List<Long> = listOf(3_000L, 10_000L, 30_000L),
) : LessonBackend {
    init {
        require(apiKey.isNotBlank()) { "Claude API kalitini kiriting." }
    }

    override suspend fun translate(
        userPrompt: String,
        chunk: ClaudeChunk,
    ): ClaudeChunkResult = parseClaudeReply(request(claudeRequestBody(model, userPrompt)), chunk)

    /** Sends one Messages API request, retrying a busy service; returns the raw reply. */
    suspend fun request(body: String): String {
        var attempt = 0
        while (true) {
            try {
                return transport.send(apiKey, body)
            } catch (error: ClaudeHttpException) {
                if (!isRetryableClaudeStatus(error.code) || attempt >= retryPausesMs.size) throw error
                delay(error.retryAfterMs?.coerceAtMost(MAX_RETRY_AFTER_MS) ?: retryPausesMs[attempt])
                attempt++
            }
        }
    }

    private companion object {
        const val MAX_RETRY_AFTER_MS = 60_000L
    }
}
