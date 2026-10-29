package com.kienhoang.dualsubreplay.translation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/*
 * DeepSeek as the lesson translator (OpenAI-compatible chat API). The same instructions and the same
 * answer shape as Claude's, so the reply is read by the same parser. When DeepSeek fails (no key, quota,
 * a busy service, a bad reply), [FallbackLessonBackend] hands the same chunk to Claude.
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
                    throw IOException("DeepSeek HTTP $code${apiErrorMessage(text)?.let { " - $it" }.orEmpty()}")
                }
                text
            } finally {
                connection.disconnect()
            }
        }
}

/** DeepSeek as a lesson engine: one chunk per request, the answer read like Claude's. */
internal class DeepSeekBackend(
    private val apiKey: String,
    private val transport: DeepSeekTransport = HttpDeepSeekTransport,
) : LessonBackend {
    init {
        require(apiKey.isNotBlank()) { "DeepSeek API kalitini sozlamalarga kiriting." }
    }

    override suspend fun translate(
        userPrompt: String,
        chunk: ClaudeChunk,
    ): ClaudeChunkResult = parseDeepSeekReply(transport.send(apiKey, deepSeekRequestBody(userPrompt)), chunk)
}

/**
 * The first engine that answers wins: a failed chunk (any IOException, including a cut reply) is given
 * to [fallback], Claude, when there is one; without it the failure is reported as it is. A cancelled
 * run is never retried on the fallback.
 */
internal class FallbackLessonBackend(
    private val primary: LessonBackend,
    private val fallback: LessonBackend?,
    private val onFallback: (String) -> Unit = {},
) : LessonBackend {
    override suspend fun translate(
        userPrompt: String,
        chunk: ClaudeChunk,
    ): ClaudeChunkResult =
        try {
            primary.translate(userPrompt, chunk)
        } catch (error: IOException) {
            val next = fallback ?: throw error
            onFallback(error.message.orEmpty())
            next.translate(userPrompt, chunk)
        }
}
