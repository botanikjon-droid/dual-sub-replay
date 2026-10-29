package com.kienhoang.dualsubreplay.translation

import com.kienhoang.dualsubreplay.data.ApiUsageLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/*
 * DeepSeek as the lesson translator (OpenAI-compatible chat API). The same instructions and the same
 * answer shape as Claude's, so the reply is read by the same parser.
 *
 * Errors, by DeepSeek's documented HTTP statuses (api-docs.deepseek.com, "Error Codes"):
 * 401 wrong key and 402 no balance stop the run at once; 400/422 (a request the API refuses) stop it too;
 * 429 (rate limit), 500 and 503 (server error/overloaded) and network failures are retried at most twice
 * (429 after its Retry-After when given). None of these is a reason to ask Claude: only an unusable reply
 * (broken JSON, sentences missing, cut short) is, through [FallbackLessonBackend], within a per-lesson limit.
 */

internal const val DEEPSEEK_API_URL = "https://api.deepseek.com/chat/completions"
internal const val DEEPSEEK_MODEL = "deepseek-chat"

/** Upper limit of output tokens for deepseek-chat; a chunk of [CLAUDE_CHUNK_SIZE] sentences fits well below it. */
private const val DEEPSEEK_MAX_OUTPUT_TOKENS = 8_192
private const val DEEPSEEK_READ_TIMEOUT_MS = 180_000
private const val DEEPSEEK_CONNECT_TIMEOUT_MS = 15_000

/** JSON mode needs the schema in the prompt, so the answer shape is spelled out there. */
internal fun deepSeekRequestBody(userPrompt: String): String {
    val schema = claudeToolSchema(sentences = false).toString()
    val system =
        lessonSystemPrompt(
            "Answer only with one JSON object that follows this JSON schema, with no text around it: $schema",
        )
    return JSONObject()
        .put("model", DEEPSEEK_MODEL)
        .put(
            "messages",
            JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", userPrompt)),
        ).put("response_format", JSONObject().put("type", "json_object"))
        .put("temperature", 0.2)
        .put("max_tokens", DEEPSEEK_MAX_OUTPUT_TOKENS)
        .toString()
}

/** Reads a chat completion as a lesson answer; a cut or unreadable reply is a [ClaudeReplyException]. */
internal fun parseDeepSeekReply(
    body: String,
    chunk: ClaudeChunk,
): ClaudeChunkResult {
    val choice =
        try {
            JSONObject(body).getJSONArray("choices").getJSONObject(0)
        } catch (error: JSONException) {
            throw ClaudeReplyException("DeepSeek javobini o‘qib bo‘lmadi.")
        }
    if (choice.optString("finish_reason") == "length") throw ClaudeReplyException("DeepSeek javobi kesilib qoldi.")
    val content =
        choice
            .optJSONObject("message")
            ?.optString("content")
            .orEmpty()
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    val answer =
        try {
            JSONObject(content)
        } catch (error: JSONException) {
            throw ClaudeReplyException("DeepSeek tarjima qaytarmadi.")
        }
    return parseLessonAnswer(answer, chunk, "DeepSeek")
}

/** Token counts of a chat completion ("usage"); DeepSeek reports its automatic context-cache hits too. */
internal fun deepSeekUsage(body: String?): TokenUsage {
    val usage =
        try {
            body?.let { JSONObject(it).optJSONObject("usage") }
        } catch (error: JSONException) {
            null
        } ?: return TokenUsage.UNREPORTED
    if (!usage.has("prompt_tokens") && !usage.has("completion_tokens")) return TokenUsage.UNREPORTED
    val hit = usage.optLong("prompt_cache_hit_tokens")
    val miss =
        if (usage.has("prompt_cache_miss_tokens")) {
            usage.optLong("prompt_cache_miss_tokens")
        } else {
            usage.optLong("prompt_tokens") -
                hit
        }
    return TokenUsage(
        input = miss.coerceAtLeast(0),
        output = usage.optLong("completion_tokens"),
        cacheRead = hit,
        cacheWrite = 0,
        reported = true,
    )
}

/** An HTTP failure from DeepSeek with its status and, for 429, the server's Retry-After. */
internal class DeepSeekHttpException(
    val code: Int,
    message: String,
    val retryAfterMs: Long?,
) : IOException(message)

/** The doctor-facing reason for a DeepSeek HTTP status, with the API's own message when it gave one. */
internal fun deepSeekHttpError(
    code: Int,
    body: String,
): String {
    val reason =
        when (code) {
            HTTP_UNAUTHORIZED -> "DeepSeek API kaliti noto‘g‘ri yoki yaroqsiz (HTTP 401). Sozlamalarda kalitni tekshiring."
            HTTP_PAYMENT_REQUIRED -> "DeepSeek hisobida mablag‘ tugagan (HTTP 402). platform.deepseek.com da balansni to‘ldiring."
            HTTP_BAD_REQUEST, HTTP_UNPROCESSABLE -> "DeepSeek so‘rovni qabul qilmadi (HTTP $code)."
            HTTP_TOO_MANY_REQUESTS -> "DeepSeek so‘rovlar chegarasi (HTTP 429). Birozdan keyin qayta urinib ko‘ring."
            else ->
                if (code >= HTTP_SERVER_ERROR) {
                    "DeepSeek serveri band yoki xato (HTTP $code). Birozdan keyin qayta urinib ko‘ring."
                } else {
                    "DeepSeek xatosi (HTTP $code)."
                }
        }
    return apiErrorMessage(body)?.let { "$reason ($it)" } ?: reason
}

/** 429 and server errors pass; a wrong key, no balance or a refused request do not. */
internal fun isRetryableDeepSeekStatus(code: Int): Boolean = code == HTTP_TOO_MANY_REQUESTS || code >= HTTP_SERVER_ERROR

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_PAYMENT_REQUIRED = 402
private const val HTTP_UNPROCESSABLE = 422
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR = 500
private const val MS_PER_SECOND = 1_000L

/** Sends one request; replaced by a fake in unit tests. Returns the raw chat completion. */
internal fun interface DeepSeekTransport {
    @Throws(IOException::class)
    suspend fun send(
        apiKey: String,
        body: String,
    ): String
}

internal object HttpDeepSeekTransport : DeepSeekTransport {
    override suspend fun send(
        apiKey: String,
        body: String,
    ): String =
        withContext(Dispatchers.IO) {
            val connection = URL(DEEPSEEK_API_URL).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = DEEPSEEK_CONNECT_TIMEOUT_MS
                connection.readTimeout = DEEPSEEK_READ_TIMEOUT_MS
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
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
                    throw DeepSeekHttpException(code, deepSeekHttpError(code, text), retryAfter)
                }
                text
            } finally {
                connection.disconnect()
            }
        }
}

/**
 * DeepSeek as a lesson engine: one chunk per request, the answer read like Claude's. A retryable failure
 * is tried again at most [retryPausesMs].size times; every attempt goes to the usage log.
 */
internal class DeepSeekBackend(
    private val apiKey: String,
    private val transport: DeepSeekTransport = HttpDeepSeekTransport,
    private val retryPausesMs: List<Long> = listOf(3_000L, 10_000L),
    private val onCall: ApiCallSink = ApiUsageLog::record,
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : LessonBackend {
    init {
        require(apiKey.isNotBlank()) { "DeepSeek API kalitini sozlamalarga kiriting." }
    }

    override suspend fun translate(
        userPrompt: String,
        chunk: ClaudeChunk,
    ): ClaudeChunkResult {
        val reply = send(deepSeekRequestBody(userPrompt), chunk)
        val result =
            try {
                parseDeepSeekReply(reply, chunk)
            } catch (error: ClaudeReplyException) {
                log(chunk, deepSeekUsage(reply), STATUS_BAD_REPLY, success = false)
                throw error
            }
        log(chunk, deepSeekUsage(reply), STATUS_OK, success = true)
        return result
    }

    private suspend fun send(
        body: String,
        chunk: ClaudeChunk,
    ): String {
        var attempt = 0
        while (true) {
            val waitMs =
                try {
                    return transport.send(apiKey, body)
                } catch (error: DeepSeekHttpException) {
                    log(chunk, TokenUsage.UNREPORTED, error.code.toString(), success = false)
                    if (!isRetryableDeepSeekStatus(error.code) || attempt >= retryPausesMs.size) {
                        throw TranslationStoppedException(error.message.orEmpty())
                    }
                    error.retryAfterMs?.coerceAtMost(MAX_RETRY_AFTER_MS) ?: retryPausesMs[attempt]
                } catch (error: IOException) {
                    log(chunk, TokenUsage.UNREPORTED, STATUS_NETWORK, success = false)
                    if (attempt >= retryPausesMs.size) {
                        throw TranslationStoppedException("DeepSeek bilan aloqa bo‘lmadi: ${error.message.orEmpty()}")
                    }
                    retryPausesMs[attempt]
                }
            pause(waitMs)
            attempt++
        }
    }

    private fun log(
        chunk: ClaudeChunk,
        usage: TokenUsage,
        status: String,
        success: Boolean,
    ) = onCall(apiCall(now(), "DeepSeek", DEEPSEEK_MODEL, chunk, usage, status, success))

    private companion object {
        const val MAX_RETRY_AFTER_MS = 60_000L
    }
}

/**
 * DeepSeek first, Claude only for an unusable reply. A chunk bigger than [minChunk] is handed back to the
 * translator, which halves it and asks DeepSeek again; only the smallest chunks go to [fallback], at most
 * [limit] times per lesson. Any other failure (wrong key, no balance, rate limit) stops the run as it is.
 */
internal class FallbackLessonBackend(
    private val primary: LessonBackend,
    private val fallback: LessonBackend?,
    private val limit: Int,
    private val minChunk: Int = CLAUDE_MIN_CHUNK_SIZE,
    private val onFallback: (String) -> Unit = {},
) : LessonBackend {
    /** Chunks Claude has translated in this run. */
    var used = 0
        private set

    override suspend fun translate(
        userPrompt: String,
        chunk: ClaudeChunk,
    ): ClaudeChunkResult =
        try {
            primary.translate(userPrompt, chunk)
        } catch (error: ClaudeReplyException) {
            if (chunk.sentences.size > minChunk) throw error
            val backup =
                fallback ?: throw TranslationStoppedException(
                    "${error.message} Claude zaxirasi uchun Claude kaliti kiritilmagan; tarjima to‘xtatildi.",
                )
            if (used >= limit) {
                throw TranslationStoppedException(
                    "${error.message} Claude zaxira limiti ($limit blok) tugadi; tarjima to‘xtatildi. " +
                        "Tarjima qilingan qismi saqlangan, qayta boshlansa davom etadi.",
                )
            }
            used++
            onFallback(error.message.orEmpty())
            backup.translate(userPrompt, chunk)
        }
}
