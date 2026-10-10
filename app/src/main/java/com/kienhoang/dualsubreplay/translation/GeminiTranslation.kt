package com.kienhoang.dualsubreplay.translation

import com.kienhoang.dualsubreplay.data.GlossaryEntry
import com.kienhoang.dualsubreplay.data.SubtitleStore
import com.kienhoang.dualsubreplay.data.UziGlossary
import com.kienhoang.dualsubreplay.data.glossaryApplies
import com.kienhoang.dualsubreplay.data.uzbekPrimaryTerm
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/*
 * Pure helpers for the optional Gemini translator of the "uz" build. Gemini gets several caption
 * sentences in one request (to save the free quota), the sentences said just before them as
 * context, and the approved UZI glossary terms found in them, and answers with one translation
 * per sentence. Only public YouTube caption text is sent. The HTTP call lives in src/uz.
 */

internal const val TRANSLATION_PROVIDER_GOOGLE = "google"
internal const val TRANSLATION_PROVIDER_GEMINI = "gemini"
internal val TRANSLATION_PROVIDERS = listOf(TRANSLATION_PROVIDER_GOOGLE, TRANSLATION_PROVIDER_GEMINI)

internal const val GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models"

/** Tried in order; a model the key cannot use (HTTP 404) moves on to the next one. */
internal val GEMINI_MODELS = listOf("gemini-flash-latest", "gemini-3.8-flash", "gemini-flash-lite-latest")

/** Sentences per background request: about 10 requests for a 15-minute lecture. */
internal const val GEMINI_BATCH_SIZE = 25

/**
 * Sentences per request while playback waits for one (the first sentence of a video, or after a seek):
 * a small batch answers in a few seconds, and the larger batches follow in the background.
 */
internal const val GEMINI_FIRST_BATCH_SIZE = 6

/** Sentences said just before the batch, sent only as context. */
internal const val GEMINI_CONTEXT_BEFORE = 3

/** When one of the next sentences is not translated yet, the next batch is fetched in advance. */
internal const val GEMINI_PREFETCH_AHEAD = 20

/** The sentences to translate in one request, and the ones before them given only as context. */
internal data class SentenceBatch(
    val context: List<String>,
    val sentences: List<String>,
)

/**
 * The batch that starts at [text] inside the video's ordered sentence list [all]: up to [size]
 * sentences from there that [isDone] does not already cover, plus [before] earlier ones as context.
 * A text not in the list is translated alone.
 */
internal fun sentenceBatch(
    all: List<String>,
    text: String,
    size: Int = GEMINI_BATCH_SIZE,
    before: Int = GEMINI_CONTEXT_BEFORE,
    isDone: (String) -> Boolean = { false },
): SentenceBatch {
    val wanted = text.trim()
    val index = all.indexOfFirst { it.trim() == wanted }
    if (index < 0) return SentenceBatch(emptyList(), listOf(text))
    val sentences =
        all
            .drop(index + 1)
            .asSequence()
            .filter { it.isNotBlank() && it.trim() != wanted && !isDone(it) }
            .distinct()
            .take(size - 1)
            .toList()
    return SentenceBatch(all.subList(maxOf(0, index - before), index), listOf(text) + sentences)
}

/** The next sentences after [text] in [all], to see whether the coming batch is needed yet. */
internal fun sentencesAfter(
    all: List<String>,
    text: String,
    count: Int = GEMINI_PREFETCH_AHEAD,
): List<String> {
    val index = all.indexOfFirst { it.trim() == text.trim() }
    if (index < 0) return emptyList()
    return all.drop(index + 1).filter(String::isNotBlank).take(count)
}

/** The approved glossary terms (common words excepted) found in the batch, each once. */
internal fun glossaryForBatch(
    glossary: UziGlossary?,
    sentences: List<String>,
): List<GlossaryEntry> =
    glossary
        ?.let { found -> sentences.flatMap { found.findTerms(it) }.map { it.entry }.distinctBy { it.id } }
        .orEmpty()

internal fun geminiSystemInstruction(): String =
    """
    You translate English ultrasound (UZI) lecture captions into Uzbek (Latin script) for an Uzbek ultrasound doctor.
    Rules:
    - Return a JSON array of strings: exactly one Uzbek translation per item of "sentences", in the same order. Never merge, split, skip or reorder items.
    - The captions are automatic speech recognition: punctuation is missing and a sentence may be cut between items. Use "context" and the neighbouring items to understand the meaning, but translate only the words of each item.
    - Translate the meaning naturally, as an Uzbek doctor would say it, not word by word. Example: "I've had a great patient here today" means "bugun bizda yaxshi bemor bor".
    - When an English term is in "glossary", use its Uzbek term (the words before any parentheses) and add the Uzbek suffixes the sentence needs.
    - Keep numbers, units (mm, cm/s, kPa, MHz), device and model names (for example C1-7) and abbreviations (RI, TGC, SWE) as they are.
    - Write oʻ and gʻ with the ‘ mark (o‘, g‘) and the glottal stop with ’.
    - No explanations, notes or English words that have an Uzbek equivalent.
    """.trimIndent()

/** The user message: the glossary, the context and the sentences, as JSON. */
internal fun geminiUserPrompt(
    batch: SentenceBatch,
    glossary: List<GlossaryEntry>,
): String {
    val terms = JSONArray()
    glossary.forEach { entry ->
        terms.put(JSONObject().put("en", entry.english).put("uz", uzbekPrimaryTerm(entry.uzbek)))
    }
    return JSONObject()
        .put("glossary", terms)
        .put("context", JSONArray(batch.context))
        .put("sentences", JSONArray(batch.sentences))
        .toString()
}

/**
 * A generateContent request asking for a JSON array of strings. With [lowThinking] the model is asked
 * to think little before answering: translation needs no long reasoning, and thinking is most of the
 * wait. A model that does not know the setting is asked again without it.
 */
internal fun geminiRequestBody(
    batch: SentenceBatch,
    glossary: List<GlossaryEntry>,
    lowThinking: Boolean = true,
): String {
    val stringArray = JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "STRING"))
    val config = JSONObject().put("responseMimeType", "application/json").put("responseSchema", stringArray)
    if (lowThinking) config.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
    return JSONObject()
        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", geminiSystemInstruction()))))
        .put(
            "contents",
            JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", geminiUserPrompt(batch, glossary)))),
            ),
        ).put("generationConfig", config)
        .toString()
}

/** The translations in a generateContent reply; anything but [expected] strings is an error. */
internal fun parseGeminiTranslations(
    body: String,
    expected: Int,
): List<String> {
    apiErrorMessage(body)?.let { throw IOException("Gemini: $it") }
    val root = readJson { JSONObject(body) }
    root
        .optJSONObject("promptFeedback")
        ?.optString("blockReason")
        ?.takeIf(String::isNotBlank)
        ?.let { throw IOException("Gemini refused the text ($it).") }
    val parts =
        root
            .optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts") ?: throw IOException("Gemini returned no translation.")
    val text =
        buildString {
            for (index in 0 until parts.length()) append(parts.optJSONObject(index)?.optString("text").orEmpty())
        }.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    val array = readJson { JSONArray(text) }
    val translations = (0 until array.length()).map { array.optString(it).trim() }
    if (translations.size != expected) {
        throw IOException("Gemini returned ${translations.size} translations for $expected sentences.")
    }
    if (translations.any(String::isBlank)) throw IOException("Gemini left a sentence untranslated.")
    return translations
}

private inline fun <T> readJson(parse: () -> T): T =
    try {
        parse()
    } catch (error: JSONException) {
        throw IOException("Gemini returned an unreadable response.", error)
    }

/** Shared by the uz translator and AppViewModel: the open video's sentences, in playback order. */
internal object TranslationLookahead {
    @Volatile
    var sentences: List<String> = emptyList()
}

/** The translation units of a prepared caption track (a sentence once, even when split over rows). */
internal suspend fun sentenceTexts(
    store: SubtitleStore,
    chunk: Int = SENTENCE_READ_CHUNK,
): List<String> {
    val texts = mutableListOf<String>()
    var start = 0
    while (start < store.size) {
        val end = minOf(store.size, start + chunk)
        store.read(start until end).forEach { row ->
            val text = row.sentence?.text ?: row.originalText
            if (text.isNotBlank() && texts.lastOrNull() != text) texts += text
        }
        start = end
    }
    return texts
}

private const val SENTENCE_READ_CHUNK = 256

/** A 400 reply about the thinking setting: the model does not support it, so ask without it. */
internal fun isThinkingConfigRejected(
    code: Int,
    message: String?,
): Boolean = code == 400 && message.orEmpty().contains("thinking", ignoreCase = true)

/** Gemini translates only when chosen, given a key, English to Uzbek, and not resting after a failure. */
internal fun geminiEnabled(
    provider: String,
    apiKey: String,
    sourceLanguage: String,
    targetLanguage: String,
    pausedUntilMs: Long,
    nowMs: Long,
): Boolean =
    provider == TRANSLATION_PROVIDER_GEMINI &&
        apiKey.isNotBlank() &&
        glossaryApplies(sourceLanguage, targetLanguage) &&
        nowMs >= pausedUntilMs

/** Runs [primary]; on an IOException reports it and returns [fallback] instead. Cancellation passes through. */
internal suspend fun <T> withFallback(
    primary: suspend () -> T,
    onFailure: (java.io.IOException) -> Unit,
    fallback: suspend () -> T,
): T =
    try {
        primary()
    } catch (error: java.io.IOException) {
        onFailure(error)
        fallback()
    }
