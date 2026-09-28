package com.kienhoang.dualsubreplay.translation

import org.json.JSONObject

/*
 * Pure helpers for the F-Droid build's Bergamot engine. They live in main so the
 * default unit-test run covers them; only src/fdroid calls them at runtime.
 *
 * Mozilla publishes its Firefox Translations models through Remote Settings. Every
 * model translates to or from English, so other pairs pivot through English.
 */

internal const val BERGAMOT_CATALOG_URL =
    "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/translations-models/records"
internal const val BERGAMOT_ATTACHMENT_BASE_URL = "https://firefox-settings-attachments.cdn.mozilla.net/"
internal const val BERGAMOT_PIVOT_LANGUAGE = "en"

internal data class BergamotPair(
    val source: String,
    val target: String,
) {
    val key: String get() = "$source-$target"
}

internal data class BergamotFile(
    val type: String,
    val name: String,
    val url: String,
    val sha256: String,
    val size: Long,
)

internal data class BergamotModel(
    val pair: BergamotPair,
    val version: String,
    val model: BergamotFile,
    val lexicalShortlist: BergamotFile,
    val sourceVocabulary: BergamotFile,
    val targetVocabulary: BergamotFile,
) {
    val files: List<BergamotFile>
        get() = listOf(model, lexicalShortlist, sourceVocabulary, targetVocabulary).distinctBy(BergamotFile::name)
}

/** Maps the app's language codes to Mozilla's; null when Mozilla has no model family for it. */
internal fun bergamotLanguageCode(appCode: String): String? {
    val normalized = TranslationLanguages.normalize(appCode)
    return when (normalized) {
        "zh" -> "zh-Hans"
        "no" -> "nb"
        else -> normalized
    }
}

/** The model pairs needed for one translation, in order. Empty when no translation is needed. */
internal fun bergamotRoute(
    source: String,
    target: String,
): List<BergamotPair> =
    when {
        source == target -> emptyList()
        source == BERGAMOT_PIVOT_LANGUAGE || target == BERGAMOT_PIVOT_LANGUAGE -> listOf(BergamotPair(source, target))
        else -> listOf(BergamotPair(source, BERGAMOT_PIVOT_LANGUAGE), BergamotPair(BERGAMOT_PIVOT_LANGUAGE, target))
    }

/**
 * Picks the newest complete release model for every pair. Records restricted to
 * Firefox Nightly or other channels carry a filter expression and are skipped.
 */
internal fun parseBergamotCatalog(
    json: String,
    attachmentBaseUrl: String = BERGAMOT_ATTACHMENT_BASE_URL,
): Map<BergamotPair, BergamotModel> {
    val records = JSONObject(json).getJSONArray("data")
    val grouped = mutableMapOf<Triple<String, String, String>, MutableMap<String, BergamotFile>>()
    for (index in 0 until records.length()) {
        val record = records.getJSONObject(index)
        if (record.optString("filter_expression").isNotBlank()) continue
        val attachment = record.optJSONObject("attachment") ?: continue
        val key = Triple(record.optString("fromLang"), record.optString("toLang"), record.optString("version"))
        if (key.first.isBlank() || key.second.isBlank() || key.third.isBlank()) continue
        val type = record.optString("fileType")
        grouped.getOrPut(key) { mutableMapOf() }[type] =
            BergamotFile(
                type = type,
                name = attachment.optString("filename").ifBlank { record.optString("name") },
                url = attachmentBaseUrl + attachment.optString("location"),
                sha256 = attachment.optString("hash"),
                size = attachment.optLong("size"),
            )
    }
    val models = grouped.mapNotNull { (key, files) -> completeModel(BergamotPair(key.first, key.second), key.third, files) }
    return models
        .groupBy(BergamotModel::pair)
        .mapValues { (_, candidates) -> candidates.maxWith { left, right -> compareModelVersions(left.version, right.version) } }
}

private fun completeModel(
    pair: BergamotPair,
    version: String,
    files: Map<String, BergamotFile>,
): BergamotModel? {
    val model = files["model"] ?: return null
    val lexicalShortlist = files["lex"] ?: return null
    val sourceVocabulary = files["srcvocab"] ?: files["vocab"] ?: return null
    val targetVocabulary = files["trgvocab"] ?: files["vocab"] ?: return null
    return BergamotModel(pair, version, model, lexicalShortlist, sourceVocabulary, targetVocabulary)
}

/** Compares versions such as "1.0", "2.1" and "1.0a1"; a pre-release sorts below its release. */
internal fun compareModelVersions(
    left: String,
    right: String,
): Int {
    val leftParts = versionParts(left)
    val rightParts = versionParts(right)
    for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
        val difference = (leftParts.getOrNull(index) ?: 0).compareTo(rightParts.getOrNull(index) ?: 0)
        if (difference != 0) return difference
    }
    return isRelease(left).compareTo(isRelease(right))
}

private fun versionParts(version: String): List<Int> =
    version
        .substringBefore('a')
        .substringBefore('b')
        .split('.')
        .map { it.toIntOrNull() ?: 0 }

private fun isRelease(version: String): Boolean = version.none(Char::isLetter)

/** Marian/Bergamot options for one model directory, matching Firefox's inference settings. */
internal fun bergamotModelConfig(
    modelPath: String,
    lexicalShortlistPath: String,
    sourceVocabularyPath: String,
    targetVocabularyPath: String,
): String =
    """
    |bergamot-mode: native
    |models: [${yamlQuoted(modelPath)}]
    |vocabs: [${yamlQuoted(sourceVocabularyPath)}, ${yamlQuoted(targetVocabularyPath)}]
    |shortlist: [${yamlQuoted(lexicalShortlistPath)}, false]
    |beam-size: 1
    |normalize: 1.0
    |word-penalty: 0
    |max-length-break: 128
    |mini-batch-words: 1024
    |workspace: 128
    |max-length-factor: 2.0
    |skip-cost: true
    |cpu-threads: 0
    |quiet: true
    |quiet-translation: true
    |gemm-precision: int8shiftAlphaAll
    |alignment: soft
    |
    """.trimMargin()

private fun yamlQuoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
