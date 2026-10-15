package com.kienhoang.dualsubreplay.translation

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/*
 * DeepL as an optional playback translator, with the doctor's own DeepL API key (the Free plan gives
 * 500,000 characters a month). Sentence by sentence like Google Translate, with the same UZI glossary
 * guidance; any failure falls back to Google Translate. Pure request/reply helpers; the HTTP call
 * lives in src/uz. Only public caption text is sent.
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

internal fun deeplRequestBody(
    source: String,
    target: String,
    texts: List<String>,
): String =
    JSONObject()
        .put("text", JSONArray().also { array -> texts.forEach { array.put(it) } })
        .put("source_lang", deeplLanguage(source, asTarget = false))
        .put("target_lang", deeplLanguage(target, asTarget = true))
        .toString()

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
