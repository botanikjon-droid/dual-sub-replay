package com.kienhoang.dualsubreplay.translation

import com.kienhoang.dualsubreplay.data.GlossaryEntry
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One online translation service. Blocking; callers run it on an IO dispatcher. */
internal interface OnlineTranslationProvider {
    val name: String

    @Throws(IOException::class)
    fun translate(
        source: String,
        target: String,
        text: String,
    ): String
}

/** The official Google Cloud Translation v2 API, used when a key was supplied at build time. */
internal class GoogleCloudTranslationProvider(
    private val apiKey: String,
) : OnlineTranslationProvider {
    override val name = "Google Cloud"

    override fun translate(
        source: String,
        target: String,
        text: String,
    ): String =
        parseCloudTranslation(
            httpPost("$GOOGLE_CLOUD_TRANSLATE_URL?key=${urlEncode(apiKey)}", cloudTranslateBody(source, target, text)),
        )
}

/** The keyless, undocumented gtx endpoint. Kept only as a fallback; easy to delete. */
internal class GtxTranslationProvider : OnlineTranslationProvider {
    override val name = "gtx"

    override fun translate(
        source: String,
        target: String,
        text: String,
    ): String {
        val query = gtxTranslateQuery(source, target, text)
        val body =
            if (query.length <= GTX_MAX_GET_QUERY_LENGTH) {
                httpGet("$GOOGLE_GTX_TRANSLATE_URL?$query")
            } else {
                httpPost(GOOGLE_GTX_TRANSLATE_URL, query)
            }
        return parseGtxTranslation(body)
    }
}

/**
 * Tries each provider in order, retrying with growing pauses (a rate-limited service often
 * recovers within seconds), and reports every failure if none succeeds.
 */
internal class OnlineTranslationChain(
    private val providers: List<OnlineTranslationProvider>,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
) {
    fun translate(
        source: String,
        target: String,
        text: String,
    ): String {
        val failures = mutableListOf<String>()
        for (provider in providers) {
            repeat(ATTEMPTS_PER_PROVIDER) { attempt ->
                try {
                    return provider.translate(source, target, text)
                } catch (error: IOException) {
                    failures += "${provider.name}: ${error.message}"
                    if (attempt < ATTEMPTS_PER_PROVIDER - 1) Thread.sleep(retryDelayMs * (attempt * 2 + 1))
                }
            }
        }
        throw IOException(
            "Online translation failed. Check your internet connection and retry. (${failures.joinToString("; ")})",
        )
    }

    private companion object {
        const val ATTEMPTS_PER_PROVIDER = 3
        const val RETRY_DELAY_MS = 500L
    }
}

/**
 * The optional Gemini translator: several sentences per request, with context and glossary terms.
 * The key comes from the user's own settings and is sent only in a request header.
 */
internal class GeminiBatchTranslator(
    private val apiKey: () -> String,
) {
    /** The model that answered last; later requests start with it. */
    @Volatile var workingModel: String? = null
        private set

    private val quota = GeminiModelQuota()

    /** Cleared when a model rejects the low-thinking setting, so it is not sent again. */
    @Volatile private var lowThinking = true

    @Throws(IOException::class)
    fun translate(
        batch: SentenceBatch,
        glossary: List<GlossaryEntry>,
    ): List<String> {
        val key = apiKey().trim()
        if (key.isEmpty()) throw IOException("Gemini API key is not set.")
        val available = quota.available(System.currentTimeMillis())
        val models = listOfNotNull(workingModel?.takeIf { it in available }) + available.filter { it != workingModel }
        for (model in models) {
            try {
                return parseGeminiTranslations(call(model, key, batch, glossary), batch.sentences.size).also { workingModel = model }
            } catch (error: HttpStatusException) {
                // Out of free quota (429) or not usable with this key (404): rest this model and try the
                // next one, which has its own quota. Anything else is a real failure.
                val restMs =
                    when (error.code) {
                        HTTP_TOO_MANY_REQUESTS -> parseRetryDelayMs(error.message) ?: GEMINI_DEFAULT_REST_MS
                        HTTP_NOT_FOUND -> GEMINI_UNKNOWN_MODEL_REST_MS
                        else -> throw error
                    }
                quota.rest(model, System.currentTimeMillis() + restMs)
                if (model == workingModel) workingModel = null
            }
        }
        throw GeminiQuotaExhausted(quota.nextRetryMs())
    }

    private fun call(
        model: String,
        key: String,
        batch: SentenceBatch,
        glossary: List<GlossaryEntry>,
    ): String {
        fun post(thinking: Boolean) =
            request(
                "$GEMINI_API_URL/$model:generateContent",
                geminiRequestBody(batch, glossary, lowThinking = thinking),
                contentType = JSON_CONTENT_TYPE,
                headers = mapOf("x-goog-api-key" to key),
                readTimeoutMs = GEMINI_READ_TIMEOUT_MS,
            )
        if (!lowThinking) return post(thinking = false)
        return try {
            post(thinking = true)
        } catch (error: HttpStatusException) {
            if (!isThinkingConfigRejected(error.code, error.message)) throw error
            lowThinking = false
            post(thinking = false)
        }
    }
}

/** An HTTP error status, kept as an IOException so callers that only know IOException still work. */
internal class HttpStatusException(
    val code: Int,
    message: String,
) : IOException(message)

private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 15_000

/** A batch of 25 sentences takes Gemini longer than one gtx sentence. */
private const val GEMINI_READ_TIMEOUT_MS = 60_000
private const val HTTP_NOT_FOUND = 404
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) DualSubReplay"
private const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded; charset=UTF-8"
private const val JSON_CONTENT_TYPE = "application/json; charset=UTF-8"

private fun httpGet(url: String): String = request(url, body = null)

private fun httpPost(
    url: String,
    body: String,
): String = request(url, body)

private fun request(
    url: String,
    body: String?,
    contentType: String = FORM_CONTENT_TYPE,
    headers: Map<String, String> = emptyMap(),
    readTimeoutMs: Int = READ_TIMEOUT_MS,
): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("User-Agent", USER_AGENT)
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        if (body != null) {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            // Google Cloud explains rejected keys and quotas in its JSON error body.
            throw HttpStatusException(code, "HTTP $code${apiErrorMessage(text)?.let { " - $it" }.orEmpty()}")
        }
        return text
    } finally {
        connection.disconnect()
    }
}
