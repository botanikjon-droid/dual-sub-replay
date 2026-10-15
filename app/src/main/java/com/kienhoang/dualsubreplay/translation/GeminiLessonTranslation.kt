package com.kienhoang.dualsubreplay.translation

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
 * Whole-lesson Gemini translation. The free tier counts requests (about 20 a day per model), not
 * sentences, so the lesson's whole transcript goes in ONE request with the same medical-uzbek-translator
 * instructions as Claude, and the answer is imported as the lesson's translation. A reopened lesson
 * costs no request. Only a reply that is cut short is split into two requests.
 */

/** Sentences per request: a 45-minute lecture fits in one request and one reply. */
internal const val GEMINI_LESSON_CHUNK_SIZE = 400

/** Gemini Flash answers up to 65,536 tokens; a 400-sentence lesson needs about a third. */
internal const val GEMINI_LESSON_MAX_OUTPUT_TOKENS = 65_536

/** A whole lesson takes Gemini a while to write. */
internal const val GEMINI_LESSON_READ_TIMEOUT_MS = 300_000

/** Busy (5xx) replies are retried once after this pause, then the next model is tried. */
internal const val GEMINI_BUSY_PAUSE_MS = 5_000L

internal fun geminiLessonSystemPrompt(): String =
    lessonSystemPrompt("Answer only with the JSON object described by the response schema, with no text around it.")

/** The shared JSON schema in Gemini's dialect (upper-case type names). */
internal fun geminiSchema(schema: JSONObject): JSONObject {
    val out = JSONObject()
    schema.keys().forEach { key ->
        when (val value = schema.get(key)) {
            is JSONObject ->
                out.put(
                    key,
                    if (key == "properties") {
                        JSONObject().also { props -> value.keys().forEach { props.put(it, geminiSchema(value.getJSONObject(it))) } }
                    } else {
                        geminiSchema(value)
                    },
                )
            else -> out.put(key, if (key == "type") value.toString().uppercase() else value)
        }
    }
    return out
}

internal fun geminiLessonRequestBody(userPrompt: String): String {
    val config =
        JSONObject()
            .put("responseMimeType", "application/json")
            .put("responseSchema", geminiSchema(claudeToolSchema()))
            .put("maxOutputTokens", GEMINI_LESSON_MAX_OUTPUT_TOKENS)
            .put("temperature", 0.2)
    return JSONObject()
        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", geminiLessonSystemPrompt()))))
        .put(
            "contents",
            JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))),
        ).put("generationConfig", config)
        .toString()
}

/** Reads a generateContent reply as a lesson answer; a cut or unreadable reply is a [ClaudeReplyException]. */
internal fun parseGeminiLessonReply(
    body: String,
    chunk: ClaudeChunk,
): ClaudeChunkResult = parseLessonAnswer(geminiAnswerJson(body, "Gemini tarjima qaytarmadi."), chunk, "Gemini")

/** The JSON object a generateContent reply answers with (thought parts and code fences removed). */
internal fun geminiAnswerJson(
    body: String,
    missing: String,
): JSONObject {
    val root = readGeminiJson { JSONObject(body) }
    root.optJSONObject("error")?.let { throw IOException("Gemini: ${it.optString("message")}") }
    root
        .optJSONObject("promptFeedback")
        ?.optString("blockReason")
        ?.takeIf(String::isNotBlank)
        ?.let { throw IOException("Gemini matnni rad etdi ($it).") }
    val candidate = root.optJSONArray("candidates")?.optJSONObject(0) ?: throw ClaudeReplyException(missing)
    if (candidate.optString("finishReason") == "MAX_TOKENS") throw ClaudeReplyException("Gemini javobi uzunlik chegarasida kesildi.")
    val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: throw ClaudeReplyException(missing)
    val text =
        buildString {
            for (index in 0 until parts.length()) {
                val part = parts.optJSONObject(index) ?: continue
                if (!part.optBoolean("thought")) append(part.optString("text"))
            }
        }.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    return readGeminiJson { JSONObject(text) }
}

private inline fun <T> readGeminiJson(parse: () -> T): T =
    try {
        parse()
    } catch (error: JSONException) {
        throw ClaudeReplyException("Gemini javobini o‘qib bo‘lmadi.")
    }

/** An HTTP failure from Gemini, with the reason from its JSON body. */
internal class GeminiLessonHttpException(
    val code: Int,
    message: String,
) : IOException(message)

/** Sends one request to one model; replaced by a fake in unit tests. */
internal fun interface GeminiTransport {
    @Throws(IOException::class)
    suspend fun send(
        apiKey: String,
        model: String,
        body: String,
    ): String
}

internal object HttpGeminiTransport : GeminiTransport {
    private const val CONNECT_TIMEOUT_MS = 15_000

    override suspend fun send(
        apiKey: String,
        model: String,
        body: String,
    ): String =
        withContext(Dispatchers.IO) {
            val connection = URL("$GEMINI_API_URL/$model:generateContent").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = GEMINI_LESSON_READ_TIMEOUT_MS
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.setRequestProperty("x-goog-api-key", apiKey)
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    throw GeminiLessonHttpException(code, "HTTP $code${apiErrorMessage(text)?.let { " - $it" }.orEmpty()}")
                }
                text
            } finally {
                connection.disconnect()
            }
        }
}

/**
 * Gemini as a lesson engine. Models are tried in order; a model whose free quota is used up (429)
 * rests until its retry time and one the key cannot use (404) rests a day, and the next model, with
 * its own quota, is asked. [quota] lives as long as the app, so a used-up model is not asked again.
 */
internal class GeminiBackend(
    private val apiKey: String,
    private val quota: GeminiModelQuota,
    private val transport: GeminiTransport = HttpGeminiTransport,
    private val now: () -> Long = System::currentTimeMillis,
    private val busyPauseMs: Long = GEMINI_BUSY_PAUSE_MS,
    private val onModel: (String) -> Unit = {},
) : LessonBackend {
    init {
        require(apiKey.isNotBlank()) { "Gemini API kalitini sozlamalarga kiriting." }
    }

    override suspend fun translate(
        userPrompt: String,
        chunk: ClaudeChunk,
    ): ClaudeChunkResult = parseGeminiLessonReply(request(geminiLessonRequestBody(userPrompt)), chunk)

    /** Sends [body] to the first model that answers; every model out of quota is a [GeminiQuotaExhausted]. */
    suspend fun request(body: String): String {
        for (model in quota.available(now())) {
            val reply = sendToModel(model, body) ?: continue
            onModel(model)
            return reply
        }
        throw GeminiQuotaExhausted(quota.nextRetryMs())
    }

    /** The reply, or null when this model should rest and the next one be asked. */
    private suspend fun sendToModel(
        model: String,
        body: String,
    ): String? {
        repeat(2) { attempt ->
            try {
                return transport.send(apiKey, model, body)
            } catch (error: GeminiLessonHttpException) {
                when {
                    error.code == HTTP_TOO_MANY_REQUESTS -> {
                        quota.rest(model, now() + (parseRetryDelayMs(error.message) ?: GEMINI_DEFAULT_REST_MS))
                        return null
                    }
                    error.code == HTTP_NOT_FOUND -> {
                        quota.rest(model, now() + GEMINI_UNKNOWN_MODEL_REST_MS)
                        return null
                    }
                    error.code >= HTTP_SERVER_ERROR && attempt == 0 -> delay(busyPauseMs)
                    error.code >= HTTP_SERVER_ERROR -> return null
                    else -> throw error
                }
            }
        }
        return null
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR = 500
    }
}

/** Shown on the lesson screen when every Gemini model is out of free quota. */
internal fun geminiQuotaMessage(untilMs: Long): String {
    val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date(untilMs))
    return "Gemini bepul limiti tugadi; soat $time dan keyin yana urinib ko‘ring yoki Claude bilan tarjima qiling."
}
