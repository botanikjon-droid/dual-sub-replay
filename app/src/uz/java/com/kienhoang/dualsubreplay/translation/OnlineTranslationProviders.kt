package com.kienhoang.dualsubreplay.translation

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

/** Tries each provider in order, retrying once, and reports every failure if none succeeds. */
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
                    if (attempt < ATTEMPTS_PER_PROVIDER - 1) Thread.sleep(retryDelayMs)
                }
            }
        }
        throw IOException(
            "Online translation failed. Check your internet connection and retry. (${failures.joinToString("; ")})",
        )
    }

    private companion object {
        const val ATTEMPTS_PER_PROVIDER = 2
        const val RETRY_DELAY_MS = 400L
    }
}

private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 15_000
private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) DualSubReplay"

private fun httpGet(url: String): String = request(url, body = null)

private fun httpPost(
    url: String,
    body: String,
): String = request(url, body)

private fun request(
    url: String,
    body: String?,
): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("User-Agent", USER_AGENT)
        if (body != null) {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            // Google Cloud explains rejected keys and quotas in its JSON error body.
            throw IOException("HTTP $code${apiErrorMessage(text)?.let { " - $it" }.orEmpty()}")
        }
        return text
    } finally {
        connection.disconnect()
    }
}
