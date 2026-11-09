package com.kienhoang.dualsubreplay.dubbing

import org.json.JSONObject

/*
 * The doctor's pronunciation rules: how the voice should say a written word or abbreviation ("UTT" ->
 * "ultratovush tekshiruvi"). Only the voice's copy changes; the subtitle keeps what is written.
 */

/**
 * [text] with every whole word or phrase of [rules] said as its spoken form (case ignored, longest first). An
 * abbreviation written in capitals keeps an Uzbek ending: "UTTda" -> "ultratovush tekshiruvida".
 */
internal fun applySpeechLexicon(
    text: String,
    rules: Map<String, String>,
): String {
    if (rules.isEmpty()) return text
    val letter = "[\\p{L}\\p{N}\u2018\u2019']"
    val alternatives =
        rules.keys
            .sortedByDescending(String::length)
            .joinToString("|") { written -> written.trim().split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) } }
    val regex = Regex("(?<!$letter)($alternatives)(\\p{Ll}[\\p{Ll}\u2018\u2019']*)?(?!$letter)", RegexOption.IGNORE_CASE)
    val lookup = rules.mapKeys { (written, _) -> written.trim().replace(Regex("\\s+"), " ").lowercase() }
    return regex.replace(text) { match ->
        val word = match.groupValues[1]
        val ending = match.groupValues[2]
        val spoken = lookup[word.replace(Regex("\\s+"), " ").lowercase()] ?: return@replace match.value
        val abbreviation = word.length > 1 && word.none(Char::isLowerCase)
        when {
            ending.isEmpty() -> spoken
            abbreviation -> spoken + ending
            else -> match.value
        }
    }
}

internal fun speechLexiconToJson(rules: Map<String, String>): String = JSONObject(rules as Map<*, *>).toString()

internal fun speechLexiconFromJson(json: String?): Map<String, String> {
    if (json.isNullOrBlank()) return emptyMap()
    return runCatching {
        val root = JSONObject(json)
        root.keys().asSequence().associateWith { root.getString(it) }.filter { (written, spoken) ->
            written.isNotBlank() &&
                spoken.isNotBlank()
        }
    }.getOrDefault(emptyMap())
}
