package com.kienhoang.dualsubreplay.translation

import com.kienhoang.dualsubreplay.data.GlossaryEntry
import com.kienhoang.dualsubreplay.data.LearnedTerm
import com.kienhoang.dualsubreplay.data.LearnedTermStatus
import com.kienhoang.dualsubreplay.data.SubtitleSegment
import com.kienhoang.dualsubreplay.data.uzbekPrimaryTerm
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/*
 * Pure helpers for "Claude bilan tarjima": the open lesson's whole transcript (the SubtitleMerger
 * sentences the app also exports as .srt) is translated by Claude in chunks, following the doctor's
 * medical-uzbek-translator rules, and imported as the lesson's translation. Claude also reports
 * terms it met that the glossary lacks (they grow the learned glossary) and places where the
 * automatic captions were probably misheard. Only public YouTube caption text is sent.
 */

internal const val CLAUDE_API_URL = "https://api.anthropic.com/v1/messages"
internal const val CLAUDE_API_VERSION = "2023-06-01"
internal const val CLAUDE_TOOL_NAME = "submit_translation"

/** The models the doctor can choose; the first is the default (best quality for the price). */
internal val CLAUDE_MODELS =
    listOf(
        ClaudeModelOption("claude-sonnet-5-5", "Sonnet (tavsiya)"),
        ClaudeModelOption("claude-haiku-5-5", "Haiku (tez, arzon)"),
        ClaudeModelOption("claude-opus-5-5", "Opus (eng aniq, qimmat)"),
    )

internal data class ClaudeModelOption(
    val id: String,
    val label: String,
)

/** Sentences per request: one reply stays well under the output limit and a failure costs little. */
internal const val CLAUDE_CHUNK_SIZE = 40

/** A chunk that keeps failing is split down to this size before the run gives up. */
internal const val CLAUDE_MIN_CHUNK_SIZE = 5

/** Sentences given only as context: before (with their finished Uzbek) and after the chunk. */
internal const val CLAUDE_CONTEXT_BEFORE = 3
internal const val CLAUDE_CONTEXT_AFTER = 2

internal const val CLAUDE_MAX_TOKENS = 8_192

/** One chunk of the lesson: [start] is the index of its first sentence in the whole lesson. */
internal data class ClaudeChunk(
    val start: Int,
    val sentences: List<String>,
)

internal fun claudeChunks(
    sentences: List<String>,
    size: Int = CLAUDE_CHUNK_SIZE,
): List<ClaudeChunk> =
    sentences.indices
        .chunked(size.coerceAtLeast(1))
        .map { indices -> ClaudeChunk(indices.first(), indices.map { sentences[it] }) }

/** Halves a chunk that failed; null when it is already as small as allowed. */
internal fun splitClaudeChunk(
    chunk: ClaudeChunk,
    minSize: Int = CLAUDE_MIN_CHUNK_SIZE,
): List<ClaudeChunk>? {
    if (chunk.sentences.size <= minSize) return null
    val half = chunk.sentences.size / 2
    return listOf(
        ClaudeChunk(chunk.start, chunk.sentences.take(half)),
        ClaudeChunk(chunk.start + half, chunk.sentences.drop(half)),
    )
}

/** The doctor's medical-uzbek-translator skill, as instructions for the API. */
internal fun claudeSystemPrompt(): String =
    """
    You are a medical translator. You translate English ultrasound (UZI) video lectures into Uzbek (Latin script) for an Uzbek ultrasound doctor. The text is YouTube automatic captions that the app has already joined into sentences; each item has an index "i".

    Work in this order:
    1. Read "context_before", the items and "context_after" together and rebuild the full English sentences in your head. Automatic captions have no punctuation and a sentence can continue into the next item.
    2. Find misheard words (ASR errors) from the medical context. Examples seen before: "a trophic" = atrophic, "Fri browsing" = fibrosing, "bankers head" = pancreas head, "hypo a coach in the city" = hypoechogenicity, "omental borsa" = omental bursa, "además" = edematous, "column" after left-sided = colon. Fix one only when the context clearly supports it. When several readings are possible, translate the most likely one and report the item in "uncertain" with the options; never present a guess as certain.
    3. Translate each item into natural, grammatical Uzbek, as an Uzbek doctor would say it, not word by word: subject first, verb at the end of the sentence. Do not copy English word order.
    4. Each item's Uzbek text must stand on its own on screen. When a sentence runs over two items, move words between the two Uzbek texts so that neither ends or starts with a dangling word (no lone "va", "bu", "bo‘lib,", "ikkita", or a genitive cut from its noun). Every item still gets a non-empty translation and keeps its own index; never merge, drop or reorder items.
    5. Keep the medical meaning exactly: anatomy, pathology names, numbers, measurements and units (mm, cm/s, kPa, MHz), device names and abbreviations (RI, TGC, SWE, CBD). Add no diagnosis, detail or explanation that is not in the source.
    6. Terms: "glossary" (doctor-approved) and "approved_terms" are mandatory; use the Uzbek term and add the Uzbek suffixes the sentence needs. "suggested_terms" are earlier suggestions not yet approved; use them for consistency unless the context clearly needs another word. The same structure must have the same Uzbek name throughout.
    7. Write o‘ and g‘ with the ‘ mark (U+2018) and the glottal stop with ’ (U+2019); never use a plain apostrophe.
    8. In "new_terms", list ultrasound or medical terms you translated that are in none of the term lists, with the Uzbek term you used (base form). Skip common words. In "uncertain", list misheard or doubtful places.

    Answer only by calling the submit_translation tool.
    """.trimIndent()

/** The user message: the terms that apply, the context and the chunk's items, as JSON. */
internal fun claudeUserPrompt(
    chunk: ClaudeChunk,
    allSentences: List<String>,
    finished: Map<Int, String>,
    glossary: List<GlossaryEntry>,
    learned: List<LearnedTerm>,
): String {
    val before = JSONArray()
    for (index in maxOf(0, chunk.start - CLAUDE_CONTEXT_BEFORE) until chunk.start) {
        val item = JSONObject().put("en", allSentences[index])
        finished[index]?.let { item.put("uz", it) }
        before.put(item)
    }
    val end = chunk.start + chunk.sentences.size
    val after = JSONArray()
    for (index in end until minOf(allSentences.size, end + CLAUDE_CONTEXT_AFTER)) after.put(allSentences[index])
    val items = JSONArray()
    chunk.sentences.forEachIndexed { offset, text -> items.put(JSONObject().put("i", chunk.start + offset).put("en", text)) }
    return JSONObject()
        .put("glossary", termArray(glossary.map { it.english to uzbekPrimaryTerm(it.uzbek) }))
        .put("approved_terms", termArray(learned.filter { it.status == LearnedTermStatus.APPROVED }.map { it.english to it.uzbek }))
        .put("suggested_terms", termArray(learned.filter { it.status == LearnedTermStatus.PENDING }.map { it.english to it.uzbek }))
        .put("context_before", before)
        .put("items", items)
        .put("context_after", after)
        .toString()
}

private fun termArray(terms: List<Pair<String, String>>): JSONArray {
    val array = JSONArray()
    terms.forEach { (en, uz) -> array.put(JSONObject().put("en", en).put("uz", uz)) }
    return array
}

private fun stringSchema() = JSONObject().put("type", "string")

private fun objectSchema(
    properties: JSONObject,
    required: List<String>,
) = JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(required))

private fun arraySchema(items: JSONObject) = JSONObject().put("type", "array").put("items", items)

/** The tool Claude must call: one translation per item, new terms and doubtful places. */
internal fun claudeToolSchema(): JSONObject {
    val translation =
        objectSchema(
            JSONObject().put("i", JSONObject().put("type", "integer")).put("uz", stringSchema()),
            listOf("i", "uz"),
        )
    val term =
        objectSchema(
            JSONObject().put("en", stringSchema()).put("uz", stringSchema()).put("note", stringSchema()),
            listOf("en", "uz"),
        )
    val uncertain =
        objectSchema(
            JSONObject()
                .put("i", JSONObject().put("type", "integer"))
                .put("heard", stringSchema())
                .put("options", arraySchema(stringSchema()))
                .put("reason", stringSchema()),
            listOf("i", "heard", "reason"),
        )
    return objectSchema(
        JSONObject()
            .put("translations", arraySchema(translation))
            .put("new_terms", arraySchema(term))
            .put("uncertain", arraySchema(uncertain)),
        listOf("translations", "new_terms", "uncertain"),
    )
}

internal fun claudeRequestBody(
    model: String,
    userPrompt: String,
): String =
    JSONObject()
        .put("model", model)
        .put("max_tokens", CLAUDE_MAX_TOKENS)
        .put("system", claudeSystemPrompt())
        .put(
            "tools",
            JSONArray().put(
                JSONObject()
                    .put("name", CLAUDE_TOOL_NAME)
                    .put("description", "Submit the Uzbek translation of every item, new terms and uncertain places.")
                    .put("input_schema", claudeToolSchema()),
            ),
        ).put("tool_choice", JSONObject().put("type", "tool").put("name", CLAUDE_TOOL_NAME))
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", userPrompt)))
        .toString()

/** A doubtful place Claude reported, by lesson sentence index. */
internal data class ClaudeUncertainty(
    val index: Int,
    val heard: String,
    val options: List<String>,
    val reason: String,
)

internal data class ClaudeChunkResult(
    /** Lesson sentence index -> Uzbek text, exactly one for every sentence of the chunk. */
    val translations: Map<Int, String>,
    val newTerms: List<LearnedTerm>,
    val uncertain: List<ClaudeUncertainty>,
)

/** A reply Claude cut short or answered wrongly; a smaller chunk usually succeeds. */
internal class ClaudeReplyException(
    message: String,
) : IOException(message)

/** Reads the tool call from a Messages API reply and checks it covers exactly the chunk's items. */
internal fun parseClaudeReply(
    body: String,
    chunk: ClaudeChunk,
): ClaudeChunkResult {
    val root = readClaudeJson { JSONObject(body) }
    root.optJSONObject("error")?.let { throw IOException("Claude: ${it.optString("message")}") }
    if (root.optString("stop_reason") == "max_tokens") throw ClaudeReplyException("Claude javobi uzunlik chegarasida kesildi.")
    val content = root.optJSONArray("content") ?: throw ClaudeReplyException("Claude tarjima qaytarmadi.")
    var input: JSONObject? = null
    for (index in 0 until content.length()) {
        val block = content.optJSONObject(index) ?: continue
        if (block.optString("type") == "tool_use" && block.optString("name") == CLAUDE_TOOL_NAME) input = block.optJSONObject("input")
    }
    val answer = input ?: throw ClaudeReplyException("Claude tarjima qaytarmadi.")
    val wanted =
        chunk.sentences.indices
            .map { chunk.start + it }
            .toSet()
    val translations = linkedMapOf<Int, String>()
    val array = answer.optJSONArray("translations") ?: JSONArray()
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val i = item.optInt("i", -1)
        val uz = item.optString("uz").trim()
        if (i in wanted && uz.isNotEmpty()) translations[i] = normalizeUzbekApostrophes(uz)
    }
    val missing = wanted - translations.keys
    if (missing.isNotEmpty()) {
        throw ClaudeReplyException("Claude ${missing.size} ta gapni tarjima qilmadi (${missing.sorted().take(5).joinToString()}).")
    }
    return ClaudeChunkResult(
        translations = wanted.sorted().associateWith { translations.getValue(it) },
        newTerms = parseTerms(answer.optJSONArray("new_terms")),
        uncertain = parseUncertain(answer.optJSONArray("uncertain"), wanted),
    )
}

private fun parseTerms(array: JSONArray?): List<LearnedTerm> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val en = item.optString("en").trim()
        val uz = normalizeUzbekApostrophes(item.optString("uz").trim())
        if (en.isEmpty() || uz.isEmpty()) null else LearnedTerm(en, uz, item.optString("note").trim())
    }
}

private fun parseUncertain(
    array: JSONArray?,
    wanted: Set<Int>,
): List<ClaudeUncertainty> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val i = item.optInt("i", -1).takeIf { it in wanted } ?: return@mapNotNull null
        val options = item.optJSONArray("options")
        ClaudeUncertainty(
            index = i,
            heard = item.optString("heard").trim(),
            options = (0 until (options?.length() ?: 0)).map { options!!.optString(it).trim() }.filter(String::isNotEmpty),
            reason = item.optString("reason").trim(),
        )
    }
}

/**
 * One apostrophe style for the whole file (the skill's rule 7): o'/g' with U+2018, every other
 * apostrophe (the glottal stop) with U+2019. Speech preparation converts these for the voice.
 */
internal fun normalizeUzbekApostrophes(text: String): String {
    val marks = setOf('\'', '`', '‘', '’', 'ʻ', 'ʼ')
    val out = StringBuilder(text.length)
    text.forEachIndexed { index, char ->
        if (char in marks) {
            val previous = text.getOrNull(index - 1)
            out.append(if (previous == 'o' || previous == 'O' || previous == 'g' || previous == 'G') '‘' else '’')
        } else {
            out.append(char)
        }
    }
    return out.toString()
}

/** The lesson's sentences with their Uzbek text, ready for [com.kienhoang.dualsubreplay.data.formatSrt]. */
internal fun translatedSegments(
    segments: List<SubtitleSegment>,
    translations: Map<Int, String>,
): List<SubtitleSegment> =
    segments.mapIndexedNotNull { index, segment ->
        translations[index]?.let { segment.copy(originalText = it, translatedText = null, words = emptyList(), sentence = null) }
    }

/** A short Uzbek reason for an HTTP failure, so the doctor knows what to fix. */
internal fun claudeHttpError(
    code: Int,
    body: String,
): String {
    val detail =
        runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
    val reason =
        when (code) {
            401 -> "API kaliti noto‘g‘ri yoki o‘chirilgan."
            403 -> "Bu kalitga ruxsat yo‘q."
            404 -> "Tanlangan model topilmadi. Boshqa modelni tanlang."
            429 -> "So‘rovlar chegarasi tugadi. Birozdan keyin qayta urinib ko‘ring."
            529, 503 -> "Claude hozir band. Birozdan keyin qayta urinib ko‘ring."
            else -> "Claude xatosi (HTTP $code)."
        }
    return if (detail == null) reason else "$reason ($detail)"
}

/** Busy or rate-limited replies are worth waiting for; a wrong key or model is not. */
internal fun isRetryableClaudeStatus(code: Int): Boolean = code == 429 || code == 500 || code == 502 || code == 503 || code == 529

private inline fun <T> readClaudeJson(parse: () -> T): T =
    try {
        parse()
    } catch (error: JSONException) {
        throw ClaudeReplyException("Claude javobini o‘qib bo‘lmadi.")
    }
