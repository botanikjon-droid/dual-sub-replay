package com.kienhoang.dualsubreplay.translation

import com.kienhoang.dualsubreplay.data.LessonNotes
import com.kienhoang.dualsubreplay.data.MAX_NOTE_FRAMES
import com.kienhoang.dualsubreplay.data.QUIZ_OPTIONS
import com.kienhoang.dualsubreplay.data.formatVideoTime
import com.kienhoang.dualsubreplay.data.lessonNotesFromJson
import org.json.JSONArray
import org.json.JSONObject

/*
 * Study notes for one lesson (konspekt, key terms, quiz) in ONE request to Gemini or Claude, from the
 * lesson's English transcript and, when there is one, its finished Uzbek translation, so the notes
 * use the same Uzbek terms the doctor already reads under the video. Only public caption text is sent.
 */

/** Long lectures are cut to this many characters of transcript; a 45-minute talk is about 40,000. */
internal const val NOTES_MAX_TRANSCRIPT_CHARS = 120_000

internal const val NOTES_MIN_QUESTIONS = 5
internal const val NOTES_MAX_QUESTIONS = 10
private const val NOTES_TOOL_NAME = "submit_lesson_notes"
private const val NOTES_MAX_OUTPUT_TOKENS = 16_384

internal fun lessonNotesSystemPrompt(answerInstruction: String): String =
    """
    You prepare study notes for an Uzbek ultrasound (UZI) doctor who learns from English video lectures.
    You receive the lecture's English transcript (automatic captions, so some words may be misheard) and,
    when available, its Uzbek translation line by line.

    Write in Uzbek, Latin script, standard medical register. Use o‘ and g‘ with the character U+2018 and
    the glottal stop with U+2019. Never use Cyrillic.

    1. summary: 6-12 short points (konspekt) that keep the lecture's medical content: definitions,
       normal values and measurements with their units, criteria, sonographic signs, differential
       diagnosis, pitfalls. Keep every number and unit exactly as the lecturer said it. Do not add
       facts, values or advice that the lecture does not contain. Skip greetings, adverts and jokes.
    2. key_terms: 5-15 important terms of the lecture: "en" the English term as said, "uz" the Uzbek
       medical term. When the Uzbek translation is given, use its wording for the same term.
    3. quiz: $NOTES_MIN_QUESTIONS-$NOTES_MAX_QUESTIONS multiple-choice questions that check understanding
       of THIS lecture (not general trivia). Each has exactly $QUIZ_OPTIONS different options, one
       right; "answer" is the 0-based index of the right option; mix the right option's position;
       wrong options must be plausible. "explain" says in one sentence why the answer is right, using
       the lecture.

    4. key_frames: 4-$MAX_NOTE_FRAMES moments where the lecturer SHOWS an ultrasound image worth keeping
       as a picture: a sign, a measurement, a normal versus abnormal finding, a pathology. Find them by
       what is said ("as you can see", "here", "this is", "notice", "measure", "arrow", "on this image").
       "t" is the second of that line (the [m:ss] before it), plus 2 seconds so the image is on screen;
       "caption" says in Uzbek what the picture shows (one short sentence). Skip moments that only show
       the lecturer, slides of plain text or adverts.

    If a misheard word makes a fact unclear, leave that fact out rather than guess.
    $answerInstruction
    """.trimIndent()

internal fun lessonNotesUserPrompt(
    title: String?,
    english: List<String>,
    uzbek: List<String>?,
    startSeconds: List<Int>? = null,
): String =
    buildString {
        title?.takeIf(String::isNotBlank)?.let { append("Lecture title: ").append(it).append("\n\n") }
        val withUzbek = uzbek != null && uzbek.size == english.size
        val timed = startSeconds != null && startSeconds.size == english.size
        append(if (withUzbek) "Transcript, each line: [m:ss] English ||| Uzbek\n" else "Transcript, each line: [m:ss] English\n")
        for (index in english.indices) {
            val time = if (timed) "[${formatVideoTime(startSeconds!![index])}] " else ""
            val line = time + if (withUzbek) "${english[index]} ||| ${uzbek!![index]}" else english[index]
            if (length + line.length > NOTES_MAX_TRANSCRIPT_CHARS) {
                append("[the rest of the lecture is cut]\n")
                break
            }
            append(line).append('\n')
        }
    }

/** The answer's shape, shared by Claude's tool and Gemini's response schema. */
internal fun lessonNotesSchema(): JSONObject {
    fun string() = JSONObject().put("type", "string")

    fun array(items: JSONObject) = JSONObject().put("type", "array").put("items", items)

    fun obj(
        properties: JSONObject,
        vararg required: String,
    ) = JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(required.toList()))

    val term = obj(JSONObject().put("en", string()).put("uz", string()), "en", "uz")
    val question =
        obj(
            JSONObject()
                .put("q", string())
                .put("options", array(string()))
                .put("answer", JSONObject().put("type", "integer"))
                .put("explain", string()),
            "q",
            "options",
            "answer",
            "explain",
        )
    val frame = obj(JSONObject().put("t", JSONObject().put("type", "integer")).put("caption", string()), "t", "caption")
    return obj(
        JSONObject()
            .put("summary", array(string()))
            .put("key_terms", array(term))
            .put("quiz", array(question))
            .put("key_frames", array(frame)),
        "summary",
        "key_terms",
        "quiz",
        "key_frames",
    )
}

internal fun geminiNotesRequestBody(userPrompt: String): String {
    val config =
        JSONObject()
            .put("responseMimeType", "application/json")
            .put("responseSchema", geminiSchema(lessonNotesSchema()))
            .put("maxOutputTokens", NOTES_MAX_OUTPUT_TOKENS)
            .put("temperature", 0.3)
    val system = lessonNotesSystemPrompt("Answer only with the JSON object described by the response schema, with no text around it.")
    return JSONObject()
        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        .put(
            "contents",
            JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))),
        ).put("generationConfig", config)
        .toString()
}

internal fun claudeNotesRequestBody(
    model: String,
    userPrompt: String,
): String =
    JSONObject()
        .put("model", model)
        .put("max_tokens", CLAUDE_MAX_TOKENS)
        .put("system", lessonNotesSystemPrompt("Submit the notes with the $NOTES_TOOL_NAME tool."))
        .put(
            "tools",
            JSONArray().put(
                JSONObject()
                    .put("name", NOTES_TOOL_NAME)
                    .put("description", "Submit the lesson's Uzbek summary, key terms, quiz and pictured moments.")
                    .put("input_schema", lessonNotesSchema()),
            ),
        ).put("tool_choice", JSONObject().put("type", "tool").put("name", NOTES_TOOL_NAME))
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", userPrompt)))
        .toString()

private const val NOTES_MISSING = "Konspekt qaytmadi. Qayta urinib ko‘ring."

internal fun parseGeminiNotesReply(body: String): LessonNotes = lessonNotesFromJson(geminiAnswerJson(body, NOTES_MISSING))

internal fun parseClaudeNotesReply(body: String): LessonNotes = lessonNotesFromJson(claudeToolInput(body, NOTES_TOOL_NAME, NOTES_MISSING))

/** One engine that writes a lesson's notes from the prompt; faked in unit tests. */
internal fun interface LessonNotesWriter {
    suspend fun write(userPrompt: String): LessonNotes
}

internal fun geminiNotesWriter(backend: GeminiBackend) =
    LessonNotesWriter { prompt -> parseGeminiNotesReply(backend.request(geminiNotesRequestBody(prompt))) }

internal fun claudeNotesWriter(backend: ClaudeBackend) =
    LessonNotesWriter { prompt -> parseClaudeNotesReply(backend.request(claudeNotesRequestBody(backend.model, prompt))) }
