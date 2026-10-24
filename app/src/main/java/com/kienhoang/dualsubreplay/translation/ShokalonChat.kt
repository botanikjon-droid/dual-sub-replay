package com.kienhoang.dualsubreplay.translation

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/*
 * "SHOKALON chat": Claude, with the doctor's own key, as an assistant inside the app. It reads the open
 * lesson's translation and notes and the catalog through tools, and proposes changes (sentence fixes, a
 * replacement, new notes) that the doctor applies with a button; it never changes anything itself. Wishes
 * and faults of the app are kept as proposals for the developer. Pure request and reply; the loop is
 * ui/ShokalonChatAgent.kt.
 */

internal const val CHAT_MAX_TOKENS = 4_096

/** One tool Claude may call: [name], what it does, and its input schema. */
internal data class ChatTool(
    val name: String,
    val description: String,
    val schema: JSONObject,
)

internal object ChatTools {
    const val LESSON_SENTENCES = "get_lesson_sentences"
    const val LESSON_NOTES = "get_lesson_notes"
    const val CATALOG = "get_catalog"
    const val PROPOSE_FIXES = "propose_sentence_fixes"
    const val PROPOSE_REPLACE = "propose_replace"
    const val PROPOSE_NOTES = "propose_rewrite_notes"
    const val REPORT_ISSUE = "report_app_issue"
}

private fun obj(
    vararg properties: Pair<String, JSONObject>,
    required: List<String> = emptyList(),
): JSONObject =
    JSONObject()
        .put("type", "object")
        .put("properties", JSONObject().also { json -> properties.forEach { (key, value) -> json.put(key, value) } })
        .put("required", JSONArray(required))

private fun type(
    name: String,
    description: String = "",
): JSONObject = JSONObject().put("type", name).also { if (description.isNotEmpty()) it.put("description", description) }

internal val SHOKALON_CHAT_TOOLS: List<ChatTool> =
    listOf(
        ChatTool(
            ChatTools.LESSON_SENTENCES,
            "Numbered sentences of the open lesson: time, English heard, stored Uzbek. Up to 120 per call.",
            obj("from" to type("integer", "First sentence number, 1-based."), "to" to type("integer", "Last sentence number.")),
        ),
        ChatTool(ChatTools.LESSON_NOTES, "The open lesson's konspekt, key terms and quiz, if written.", obj()),
        ChatTool(
            ChatTools.CATALOG,
            "The ultrasound sections, their topics and lectures, with which have a translation and notes.",
            obj(),
        ),
        ChatTool(
            ChatTools.PROPOSE_FIXES,
            "Show corrected Uzbek sentences of the open lesson to the doctor; each is applied only when he taps it.",
            obj(
                "fixes" to
                    JSONObject().put("type", "array").put(
                        "items",
                        obj(
                            "n" to type("integer"),
                            "uzbek" to type("string"),
                            "reason" to type("string"),
                            required = listOf("n", "uzbek", "reason"),
                        ),
                    ),
                required = listOf("fixes"),
            ),
        ),
        ChatTool(
            ChatTools.PROPOSE_REPLACE,
            "Propose replacing a recurring wrong word or phrase in stored translations. The app shows how many " +
                "places match and applies it only when the doctor taps it.",
            obj(
                "find" to type("string"),
                "replacement" to type("string"),
                "scope" to JSONObject().put("type", "string").put("enum", JSONArray(listOf("lesson", "section", "all"))),
                "whole_word" to type("boolean"),
                required = listOf("find", "replacement", "scope"),
            ),
        ),
        ChatTool(
            ChatTools.PROPOSE_NOTES,
            "Offer to rewrite the open lesson's konspekt and test from its (corrected) translation.",
            obj("why" to type("string")),
        ),
        ChatTool(
            ChatTools.REPORT_ISSUE,
            "Record a fault or a wish about the app itself for the developer (the app's code cannot change here).",
            obj(
                "kind" to JSONObject().put("type", "string").put("enum", JSONArray(listOf("xato", "taklif"))),
                "title" to type("string"),
                "details" to type("string", "What happens, where, and what the doctor expects."),
                required = listOf("kind", "title", "details"),
            ),
        ),
    )

internal fun shokalonChatSystem(context: String): String =
    """
    You are the assistant inside SHOKALON, an Android app in which an Uzbek ultrasound doctor studies English
    ultrasound lectures from YouTube with Uzbek subtitles, an Uzbek voice, notes (konspekt) and tests.
    Always answer in Uzbek (Latin script, o‘ and g‘ with the ‘ sign), briefly and clearly, like a colleague.
    You can: explain ultrasound topics of the lectures; read the open lesson's sentences and notes and the
    catalog with tools; find translation mistakes and propose fixes with propose_sentence_fixes or, for a
    mistake that recurs, propose_replace; offer new notes with propose_rewrite_notes. Proposals are shown to
    the doctor as buttons; never say a change is done — say it is waiting for his tap.
    You cannot change the app's program. When the doctor reports a fault of the app or wants a new feature,
    ask what is needed if it is unclear, then record it with report_app_issue and say it will reach the
    developer when he shares the list ("Dastur takliflari").
    Medical facts: be accurate; when unsure, say so. Do not invent what the lecture says — read it.
    Current state of the app:
    $context
    """.trimIndent()

internal fun chatRequestBody(
    model: String,
    system: String,
    messages: JSONArray,
    tools: List<ChatTool> = SHOKALON_CHAT_TOOLS,
): String =
    JSONObject()
        .put("model", model)
        .put("max_tokens", CHAT_MAX_TOKENS)
        .put("system", system)
        .put(
            "tools",
            JSONArray().also { array ->
                tools.forEach {
                    array.put(JSONObject().put("name", it.name).put("description", it.description).put("input_schema", it.schema))
                }
            },
        ).put("messages", messages)
        .toString()

/** One block of Claude's answer. */
internal sealed interface ChatBlock {
    data class Text(
        val text: String,
    ) : ChatBlock

    data class ToolUse(
        val id: String,
        val name: String,
        val input: JSONObject,
    ) : ChatBlock
}

internal data class ChatReply(
    /** The answer's content as sent back in the next request. */
    val content: JSONArray,
    val blocks: List<ChatBlock>,
    val wantsTools: Boolean,
)

internal fun parseChatReply(body: String): ChatReply {
    val root =
        try {
            JSONObject(body)
        } catch (error: org.json.JSONException) {
            throw IOException("Claude javobini o‘qib bo‘lmadi.", error)
        }
    root.optJSONObject("error")?.let { throw IOException("Claude: ${it.optString("message")}") }
    val content = root.optJSONArray("content") ?: JSONArray()
    val blocks =
        (0 until content.length()).mapNotNull { index ->
            val block = content.optJSONObject(index) ?: return@mapNotNull null
            when (block.optString("type")) {
                "text" -> block.optString("text").takeIf(String::isNotBlank)?.let { ChatBlock.Text(it.trim()) }
                "tool_use" ->
                    ChatBlock.ToolUse(
                        block.optString("id"),
                        block.optString("name"),
                        block.optJSONObject("input") ?: JSONObject(),
                    )
                else -> null
            }
        }
    return ChatReply(content, blocks, root.optString("stop_reason") == "tool_use")
}

internal fun userMessage(text: String): JSONObject = JSONObject().put("role", "user").put("content", text)

internal fun assistantMessage(content: JSONArray): JSONObject = JSONObject().put("role", "assistant").put("content", content)

/** The answers of the tools Claude called, as the next user message. */
internal fun toolResultsMessage(results: List<Pair<String, String>>): JSONObject =
    JSONObject().put("role", "user").put(
        "content",
        JSONArray().also { array ->
            results.forEach { (id, text) -> array.put(JSONObject().put("type", "tool_result").put("tool_use_id", id).put("content", text)) }
        },
    )

internal const val MAX_TOOL_RESULT_CHARS = 24_000

/** A tool's answer cut to a size that keeps requests cheap. */
internal fun boundedToolResult(text: String): String =
    if (text.length <= MAX_TOOL_RESULT_CHARS) text else text.take(MAX_TOOL_RESULT_CHARS) + "\n[cut: ask for a smaller range]"

/** Keeps the conversation sent to Claude short: the last [keep] messages, starting with a plain user message. */
internal fun trimmedHistory(
    messages: List<JSONObject>,
    keep: Int = MAX_HISTORY_MESSAGES,
): List<JSONObject> {
    if (messages.size <= keep) return messages
    var start = messages.size - keep
    while (start < messages.size && !(messages[start].optString("role") == "user" && messages[start].opt("content") is String)) start++
    return if (start < messages.size) messages.subList(start, messages.size) else messages.takeLast(1)
}

internal const val MAX_HISTORY_MESSAGES = 30
