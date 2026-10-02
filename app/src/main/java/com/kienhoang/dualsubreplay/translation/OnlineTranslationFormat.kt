package com.kienhoang.dualsubreplay.translation

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/*
 * Pure helpers for the "uz" build's online translation. They live in main so the
 * default unit-test run covers them; only src/uz calls them at runtime.
 *
 * Google Cloud Translation is the official API. The keyless "gtx" endpoint is an
 * undocumented fallback for personal builds and may change or be rate limited.
 */

internal const val GOOGLE_CLOUD_TRANSLATE_URL = "https://translation.googleapis.com/language/translate/v2"
internal const val GOOGLE_GTX_TRANSLATE_URL = "https://translate.googleapis.com/translate_a/single"

/** Longer gtx queries go in a POST body instead of the URL. */
internal const val GTX_MAX_GET_QUERY_LENGTH = 1800

internal fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

internal fun formEncode(parameters: List<Pair<String, String>>): String =
    parameters.joinToString("&") { (name, value) -> "${urlEncode(name)}=${urlEncode(value)}" }

/** The language code both Google endpoints expect, e.g. "en-US" becomes "en". */
internal fun onlineLanguageCode(code: String): String = TranslationLanguages.normalize(code)

internal fun cloudTranslateBody(
    source: String,
    target: String,
    text: String,
): String =
    formEncode(
        listOf(
            "q" to text,
            "source" to onlineLanguageCode(source),
            "target" to onlineLanguageCode(target),
            "format" to "text",
        ),
    )

internal fun gtxTranslateQuery(
    source: String,
    target: String,
    text: String,
): String =
    formEncode(
        listOf(
            "client" to "gtx",
            "sl" to onlineLanguageCode(source),
            "tl" to onlineLanguageCode(target),
            "dt" to "t",
            "ie" to "UTF-8",
            "oe" to "UTF-8",
            "q" to text,
        ),
    )

/** Reads `data.translations[0].translatedText`, or reports the API's own error message. */
internal fun parseCloudTranslation(body: String): String {
    apiErrorMessage(body)?.let { message -> throw IOException("Google Cloud: $message") }
    val root = parseJson("Google Cloud") { JSONObject(body) }
    val translated =
        root
            .optJSONObject("data")
            ?.optJSONArray("translations")
            ?.optJSONObject(0)
            ?.optString("translatedText")
            .orEmpty()
    if (translated.isBlank()) throw IOException("Google Cloud returned no translation.")
    return translated
}

/** Joins the translated pieces of a gtx reply: `[[["piece", "source", ...], ...], ...]`. */
internal fun parseGtxTranslation(body: String): String {
    val chunks =
        parseJson("gtx") { JSONArray(body) }.optJSONArray(0)
            ?: throw IOException("gtx returned an unexpected response.")
    val translated =
        buildString {
            for (index in 0 until chunks.length()) {
                val piece = chunks.optJSONArray(index)?.opt(0)
                if (piece is String) append(piece)
            }
        }
    if (translated.isBlank()) throw IOException("gtx returned no translation.")
    return translated
}

/** The `error.message` of a Google API error body, or null when the body has none. */
internal fun apiErrorMessage(body: String): String? =
    try {
        JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf(String::isNotBlank)
    } catch (ignored: JSONException) {
        null
    }

private inline fun <T> parseJson(
    provider: String,
    parse: () -> T,
): T =
    try {
        parse()
    } catch (error: JSONException) {
        throw IOException("$provider returned an unreadable response.", error)
    }
