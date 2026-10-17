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
 * DeepL with the doctor's own DeepL API key (the Free plan gives 500,000 characters a month): whole
 * Uzbek lessons in requests of 50 sentences (stored like Claude's and Gemini's translations), and other
 * target languages sentence by sentence during playback (src/uz), with the same UZI glossary guidance;
 * any failure falls back to Google Translate. Only public caption text is sent.
 */

/** Free-plan keys end with ":fx" and use their own host. */
internal fun deeplEndpoint(apiKey: String): String =
    if (apiKey.trim().endsWith(":fx")) {
        "https://api-free.deepl.com/v2/translate"
    } else {
        "https://api.deepl.com/v2/translate"
    }

/** DeepL's language code: upper case, and a regional variant where DeepL requires one as target. */
internal fun deeplLanguage(
    code: String,
    asTarget: Boolean,
): String {
    val base = code.substringBefore('-').substringBefore('_').uppercase()
    if (!asTarget) return base
    return when (base) {
        "EN" -> "EN-US"
        "PT" -> "PT-BR"
        else -> base
    }
}

/** [source] null lets DeepL detect the language (a whole lesson in whatever language it is spoken). */
internal fun deeplRequestBody(
    source: String?,
    target: String,
    texts: List<String>,
): String {
    val body = JSONObject().put("text", JSONArray().also { array -> texts.forEach { array.put(it) } })
    source?.let { body.put("source_lang", deeplLanguage(it, asTarget = false)) }
    return body.put("target_lang", deeplLanguage(target, asTarget = true)).toString()
}

/** One translation per sent text, in order. */
internal fun parseDeepLTranslations(
    body: String,
    count: Int,
): List<String> {
    val translations =
        try {
            JSONObject(body).optJSONArray("translations") ?: JSONArray()
        } catch (error: JSONException) {
            throw IOException("DeepL javobini o‘qib bo‘lmadi.")
        }
    val texts = (0 until translations.length()).map { translations.optJSONObject(it)?.optString("text").orEmpty() }
    if (texts.size != count || texts.any(String::isBlank)) throw IOException("DeepL tarjima qaytarmadi.")
    return texts
}

/** Why DeepL refused, in Uzbek, for the translator status line. */
internal fun deeplErrorMessage(
    code: Int,
    body: String,
): String {
    val reason =
        when (code) {
            HTTP_FORBIDDEN -> "kalit noto‘g‘ri"
            HTTP_QUOTA_EXCEEDED -> "oylik bepul limit (500 000 belgi) tugadi"
            HTTP_TOO_MANY -> "so‘rovlar juda ko‘p, birozdan keyin"
            HTTP_BAD_REQUEST -> "so‘rov qabul qilinmadi"
            else -> "xizmat javob bermadi"
        }
    val detail = deeplBodyMessage(body)?.let { " – $it" }.orEmpty()
    return "DeepL: $reason (HTTP $code$detail)"
}

/** DeepL explains errors as {"message": "..."}; other APIs nest it under "error". */
private fun deeplBodyMessage(body: String): String? =
    try {
        JSONObject(body).optString("message").takeIf(String::isNotBlank) ?: apiErrorMessage(body)
    } catch (ignored: JSONException) {
        null
    }

/** How long DeepL rests after a failure, so every sentence does not wait for the same refusal. */
internal fun deeplRestMs(code: Int?): Long =
    when (code) {
        HTTP_FORBIDDEN, HTTP_QUOTA_EXCEEDED -> DEEPL_LONG_REST_MS
        else -> DEEPL_SHORT_REST_MS
    }

/** DeepL translates only when chosen, given a key, and not resting after a failure. */
internal fun deeplEnabled(
    provider: String,
    apiKey: String,
    pausedUntilMs: Long,
    nowMs: Long,
): Boolean = provider == TRANSLATION_PROVIDER_DEEPL && apiKey.isNotBlank() && nowMs >= pausedUntilMs

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_FORBIDDEN = 403
private const val HTTP_TOO_MANY = 429
private const val HTTP_QUOTA_EXCEEDED = 456
private const val DEEPL_SHORT_REST_MS = 60_000L
private const val DEEPL_LONG_REST_MS = 3_600_000L

/** DeepL accepts up to 50 texts per request: a 400-sentence lesson takes about 8 requests. */
internal const val DEEPL_LESSON_CHUNK_SIZE = 50

/** An HTTP failure from DeepL, with its status and an Uzbek reason. */
internal class DeepLHttpException(
    val code: Int,
    message: String,
) : IOException(message)

/** Sends one request; replaced by a fake in unit tests. */
internal fun interface DeepLTransport {
    @Throws(IOException::class)
    suspend fun send(
        apiKey: String,
        body: String,
    ): String
}

internal object HttpDeepLTransport : DeepLTransport {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000

    override suspend fun send(
        apiKey: String,
        body: String,
    ): String =
        withContext(Dispatchers.IO) {
            val connection = URL(deeplEndpoint(apiKey)).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.setRequestProperty("Authorization", "DeepL-Auth-Key ${apiKey.trim()}")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw DeepLHttpException(code, deeplErrorMessage(code, text))
                text
            } finally {
                connection.disconnect()
            }
        }
}

/**
 * DeepL as a whole-lesson engine: up to 50 sentences per request, each guided by the UZI glossary
 * through [prepare]. The result is imported as the lesson's translation like Claude's and Gemini's, so
 * a reopened lesson costs no characters and plays without waiting. DeepL neither rebuilds sentences
 * nor reports new terms or doubts. A busy service (429, 5xx) is asked again after a pause.
 */
internal class DeepLBackend(
    private val apiKey: String,
    private val prepare: (String) -> String = { it },
    private val transport: DeepLTransport = HttpDeepLTransport,
    private val retryPausesMs: List<Long> = listOf(2_000L, 8_000L),
    /**
     * The lecture's language. It must be named: a glossary-guided sentence is half Uzbek, DeepL then
     * detects Uzbek and sends the sentence back untranslated ("And will give akustik soya along with…").
     */
    private val source: String = "en",
) : LessonBackend {
    init {
        require(apiKey.isNotBlank()) { "DeepL API kalitini sozlamalarga kiriting." }
    }

    override suspend fun translate(
        userPrompt: String,
        chunk: ClaudeChunk,
    ): ClaudeChunkResult {
        val guided = chunk.sentences.map(prepare)
        val texts = request(guided).toMutableList()
        // A sentence that still came back in English is asked again without the glossary's Uzbek terms.
        val stuck = texts.indices.filter { looksUntranslated(texts[it]) }
        if (stuck.isNotEmpty()) {
            request(stuck.map { chunk.sentences[it] }).forEachIndexed { position, text -> texts[stuck[position]] = text }
        }
        val translations = texts.withIndex().associate { (index, text) -> chunk.start + index to normalizeUzbekApostrophes(text) }
        return ClaudeChunkResult(translations, newTerms = emptyList(), uncertain = emptyList())
    }

    private suspend fun request(texts: List<String>): List<String> =
        parseDeepLTranslations(sendWithRetry(deeplRequestBody(source, "uz", texts)), texts.size)

    private suspend fun sendWithRetry(body: String): String {
        var attempt = 0
        while (true) {
            try {
                return transport.send(apiKey, body)
            } catch (error: DeepLHttpException) {
                val busy = error.code == HTTP_TOO_MANY || error.code >= HTTP_SERVER_ERROR
                if (!busy || attempt >= retryPausesMs.size) throw error
                delay(retryPausesMs[attempt])
                attempt++
            }
        }
    }
}

private const val HTTP_SERVER_ERROR = 500

/** English words that never appear in Uzbek text; two or more mean DeepL left the sentence in English. */
private val ENGLISH_MARKERS =
    setOf("the", "and", "with", "along", "of", "will", "is", "are", "this", "that", "which", "from", "it", "be", "can", "give", "there")

internal fun looksUntranslated(text: String): Boolean =
    Regex("[A-Za-z]+").findAll(text.lowercase()).count { it.value in ENGLISH_MARKERS } >= 2
