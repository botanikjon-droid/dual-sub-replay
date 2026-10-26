package com.kienhoang.dualsubreplay.translation

import com.kienhoang.dualsubreplay.data.GlossaryEntry
import com.kienhoang.dualsubreplay.data.ReviewSentence
import com.kienhoang.dualsubreplay.data.uzbekPrimaryTerm
import org.json.JSONArray
import org.json.JSONObject

/*
 * "Claude bilan tuzatish": Claude checks the Uzbek of sentences the doctor doubts (one long-pressed sentence,
 * or the marked ones of a lesson) against the English heard, and answers each with a corrected Uzbek and a
 * short reason. Nothing is changed until the doctor applies a fix. Pure request and reply; the screens are
 * ui/ClaudeFixes.kt.
 */

/** Claude's answer for one sentence: [uzbek] is the sentence as it should be; [changed] is false when it was right. */
internal data class SentenceFix(
    val sentence: ReviewSentence,
    val uzbek: String,
    val reason: String,
    val changed: Boolean,
)

internal const val SENTENCE_FIX_TOOL = "submit_fixes"
internal const val MAX_FIX_SENTENCES = 60

internal val SENTENCE_FIX_SYSTEM =
    """
    You are a senior ultrasound physician and medical translator who checks Uzbek translations of English
    ultrasound (sonography) lectures for Uzbek doctors.
    For every numbered sentence you get the English that was heard (automatic captions: may lack punctuation
    or mishear words) and the current Uzbek. Check the Uzbek for: wrong medical meaning, wrong or inconsistent
    terms (prefer the glossary terms), missing or added content, unnatural word order, grammar and spelling
    (Uzbek Latin script; write o‘ and g‘ with the ‘ sign, and ’ for the tutuq belgisi).
    Translate idioms by meaning, say spoken filler and repeated stage talk once and briefly, and ignore the
    stray capital letters of automatic captions in the middle of sentences.
    If the Uzbek is already correct, return it unchanged with changed=false. Otherwise return the whole
    corrected sentence and changed=true. Keep numbers, units and abbreviations as in the lecture.
    The reason is one short Uzbek sentence naming what was wrong (empty when unchanged).
    The doctor's own rules (Uzbek) decide style and meaning:
    {{DOCTOR_RULES}}
    Answer every sentence with the $SENTENCE_FIX_TOOL tool.
    """.trimIndent().replace("{{DOCTOR_RULES}}", DOCTOR_RULES)

/** The user message: the lecture, the glossary terms that occur, and the numbered sentences. */
internal fun sentenceFixPrompt(
    lessonTitle: String?,
    sentences: List<ReviewSentence>,
    glossary: List<GlossaryEntry>,
    request: String? = null,
): String =
    buildString {
        lessonTitle?.takeIf(String::isNotBlank)?.let { append("Lecture: ").append(it).append("\n\n") }
        if (glossary.isNotEmpty()) {
            append("Glossary (English = Uzbek):\n")
            glossary.forEach { append(it.english).append(" = ").append(uzbekPrimaryTerm(it.uzbek)).append('\n') }
            append('\n')
        }
        request?.takeIf(String::isNotBlank)?.let { append("The doctor's note: ").append(it).append("\n\n") }
        sentences.forEachIndexed { index, sentence ->
            append('[').append(index + 1).append("]\nEN: ").append(sentence.english.trim())
            append("\nUZ: ").append(sentence.uzbek.trim()).append("\n\n")
        }
    }

internal fun sentenceFixRequestBody(
    model: String,
    prompt: String,
): String {
    val item =
        JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("n", JSONObject().put("type", "integer").put("description", "The sentence number."))
                    .put("uzbek", JSONObject().put("type", "string"))
                    .put("changed", JSONObject().put("type", "boolean"))
                    .put("reason", JSONObject().put("type", "string")),
            ).put("required", JSONArray(listOf("n", "uzbek", "changed", "reason")))
    val schema =
        JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("fixes", JSONObject().put("type", "array").put("items", item)))
            .put("required", JSONArray(listOf("fixes")))
    return JSONObject()
        .put("model", model)
        .put("max_tokens", CLAUDE_MAX_TOKENS)
        .put("system", SENTENCE_FIX_SYSTEM)
        .put(
            "tools",
            JSONArray().put(
                JSONObject()
                    .put("name", SENTENCE_FIX_TOOL)
                    .put("description", "Submit the checked Uzbek of every sentence.")
                    .put("input_schema", schema),
            ),
        ).put("tool_choice", JSONObject().put("type", "tool").put("name", SENTENCE_FIX_TOOL))
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        .toString()
}

/** Claude's fixes, in the sentences' order; sentences it skipped come back unchanged. */
internal fun parseSentenceFixes(
    body: String,
    sentences: List<ReviewSentence>,
): List<SentenceFix> {
    val input = claudeToolInput(body, SENTENCE_FIX_TOOL, "Claude tuzatishlarni qaytarmadi. Qayta urinib ko‘ring.")
    val array = input.optJSONArray("fixes") ?: JSONArray()
    val byNumber = HashMap<Int, JSONObject>()
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        byNumber[item.optInt("n", -1)] = item
    }
    return sentences.mapIndexed { index, sentence ->
        val item = byNumber[index + 1]
        val uzbek = item?.optString("uzbek")?.trim().orEmpty()
        // Claude's text counts whenever it differs, even if it forgot to set "changed".
        val changed = item != null && uzbek.isNotEmpty() && uzbek != sentence.uzbek.trim()
        SentenceFix(
            sentence,
            if (changed) uzbek else sentence.uzbek,
            if (changed) item?.optString("reason")?.trim().orEmpty() else "",
            changed,
        )
    }
}

/** Asks Claude to check [sentences] (at most [MAX_FIX_SENTENCES] at a time). */
internal suspend fun requestSentenceFixes(
    backend: ClaudeBackend,
    lessonTitle: String?,
    sentences: List<ReviewSentence>,
    glossary: List<GlossaryEntry>,
    request: String? = null,
): List<SentenceFix> =
    sentences.chunked(MAX_FIX_SENTENCES).flatMap { part ->
        val body = backend.request(sentenceFixRequestBody(backend.model, sentenceFixPrompt(lessonTitle, part, glossary, request)))
        parseSentenceFixes(body, part)
    }
