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

/**
 * The model ids of DeepSeek's current API (api-docs.deepseek.com). "deepseek-chat" was retired on
 * 24 July 2026 and is refused since then, so it must not be sent any more.
 */
internal const val DEEPSEEK_MODEL = "deepseek-flash"
internal const val DEEPSEEK_PRO_MODEL = "deepseek-v4-pro"

internal data class DeepSeekModelOption(
    val id: String,
    val label: String,
)

internal val DEEPSEEK_MODELS =
    listOf(
        DeepSeekModelOption(DEEPSEEK_MODEL, "Flash (tavsiya, arzon)"),
        DeepSeekModelOption(DEEPSEEK_PRO_MODEL, "V4 Pro (aniqroq, qimmatroq)"),
    )

/** A chunk of [CLAUDE_CHUNK_SIZE] sentences needs far less; the cap only stops a runaway reply. */
private const val DEEPSEEK_MAX_OUTPUT_TOKENS = 8_192
private const val DEEPSEEK_READ_TIMEOUT_MS = 180_000
private const val DEEPSEEK_CONNECT_TIMEOUT_MS = 15_000

/**
 * How DeepSeek works on a chunk: as a human medical translator who edits Google Translate's draft
 * ("draft_uz") into natural Uzbek for ultrasound doctors, reading American, British and Indian English
 * by their own usage. It follows the shared lesson rules ([lessonSystemPrompt]) and ends with the JSON
 * answer shape, which JSON mode needs in the prompt.
 */
internal val DEEPSEEK_EDITOR_INSTRUCTION =
    """
    How you work on these items: you are a senior medical interpreter who is also an Uzbek ultrasound doctor.
    Translate as a skilled human would for Uzbek ultrasound doctors: first understand what the lecturer
    means, then say it in natural, correct Uzbek (Latin script). Never translate word for word.

    An item may carry "draft_uz", a raw machine translation (Google Translate). It is only a draft:
    - keep what is already correct and natural;
    - fix a wrong meaning, wrong or inconsistent medical terms (the glossary decides), a lost negation,
      numbers, units, side (right/left) and anatomical location;
    - rebuild the sentence in Uzbek word order (the verb at the end) with correct suffixes, agreement and
      punctuation, so it reads as one natural spoken thought;
    - when the draft is wrong, empty or missing, translate from the English yourself. Never copy its errors.

    The lecturer may speak American, British or Indian English; read each by its own usage:
    - American: OR = operating room, ER = emergency room, attending = senior doctor, resident,
      "work-up" = examination plan, "rule out" = exclude.
    - British: theatre = operating room, A&E = emergency department, registrar, consultant,
      "query" = suspected, "whilst" = while.
    - Indian: "kindly", "do the needful", "prepone" = move earlier, "doubt" = a question,
      "itself"/"only" for emphasis ("today itself" = this very day), "same" = it/that,
      lakh = 100 000 and crore = 10 000 000 (write "2 lakh" as "200 000"), USG = ultrasonography,
      PV = per vaginal examination, LSCS = caesarean section.
    Translate what such words mean, never the words themselves.

    Medical terminology: use the glossary and approved terms exactly, and the same term every time in the
    lesson. Keep standard abbreviations (CBD, IVC, BPD, AFI) as the lecturer says them. Never invent a
    term or guess a misheard word: keep the safest reading and list the item in "uncertain".

    The audience reads the subtitle and hears it read aloud: short, clear spoken Uzbek, the polite "siz"
    register, no English words left except abbreviations.
    """.trimIndent()

/** JSON mode needs the schema in the prompt, so the answer shape is spelled out there. */
internal fun deepSeekSystemPrompt(): String {
    val schema = claudeToolSchema(sentences = false).toString()
    return lessonSystemPrompt(
        DEEPSEEK_EDITOR_INSTRUCTION + "\n\nAnswer only with one JSON object that follows this JSON schema, with no text around it: $schema",
    )
}

/** Thinking is switched off: DeepSeek enables it by default, and a translation needs no paid reasoning. */
internal fun deepSeekRequestBody(
    userPrompt: String,
    model: String = DEEPSEEK_MODEL,
): String {
    val system = deepSeekSystemPrompt()
    return JSONObject()
        .put("model", model)
        .put("thinking", JSONObject().put("type", "disabled"))
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
    private val model: String = DEEPSEEK_MODEL,
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
        val reply = send(deepSeekRequestBody(userPrompt, model), chunk)
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
    ) = onCall(apiCall(now(), "DeepSeek", model, chunk, usage, status, success))

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
