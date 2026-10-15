package com.kienhoang.dualsubreplay.data

import org.json.JSONArray
import org.json.JSONObject

/*
 * A lesson's study notes: a short Uzbek summary (konspekt), its key terms and a multiple-choice quiz,
 * written by Gemini or Claude from the lesson's transcript in one request and kept per video, so a
 * reopened lesson costs no request. Pure model and JSON form; the file store is LessonLibraryStore.
 */

internal data class NoteTerm(
    val english: String,
    val uzbek: String,
)

internal data class QuizQuestion(
    val question: String,
    /** Exactly [QUIZ_OPTIONS] answers. */
    val options: List<String>,
    /** Index of the right answer in [options]. */
    val answer: Int,
    val explanation: String,
)

internal data class LessonNotes(
    val summary: List<String>,
    val terms: List<NoteTerm>,
    val quiz: List<QuizQuestion>,
    /** "Gemini" or "Claude". */
    val engine: String = "",
    val createdAtMs: Long = 0L,
)

internal const val QUIZ_OPTIONS = 4

/** Thrown when a model's notes are missing or unusable; the message is shown to the doctor. */
internal class LessonNotesException(
    message: String,
) : IllegalStateException(message)

/**
 * Reads notes from a model's answer (or the stored file). Questions without four distinct options or
 * with an answer out of range are dropped instead of being shown wrong; notes without a summary or
 * without any usable question are rejected.
 */
internal fun lessonNotesFromJson(json: JSONObject): LessonNotes {
    val summary = strings(json.optJSONArray("summary")).filter(String::isNotBlank)
    val termsArray = json.optJSONArray("key_terms") ?: JSONArray()
    val terms =
        (0 until termsArray.length()).mapNotNull { index ->
            val item = termsArray.optJSONObject(index) ?: return@mapNotNull null
            val english = item.optString("en").trim()
            val uzbek = item.optString("uz").trim()
            if (english.isEmpty() || uzbek.isEmpty()) null else NoteTerm(english, uzbek)
        }
    val quizArray = json.optJSONArray("quiz") ?: JSONArray()
    val quiz = (0 until quizArray.length()).mapNotNull { index -> quizQuestion(quizArray.optJSONObject(index)) }
    if (summary.isEmpty()) throw LessonNotesException("Konspekt bo‘sh qaytdi. Qayta urinib ko‘ring.")
    if (quiz.isEmpty()) throw LessonNotesException("Test savollari yaroqsiz qaytdi. Qayta urinib ko‘ring.")
    return LessonNotes(
        summary = summary,
        terms = terms,
        quiz = quiz,
        engine = json.optString("engine"),
        createdAtMs = json.optLong("created_at"),
    )
}

private fun quizQuestion(item: JSONObject?): QuizQuestion? {
    item ?: return null
    val question = item.optString("q").trim()
    val options = strings(item.optJSONArray("options")).map(String::trim)
    val answer = item.optInt("answer", -1)
    val valid =
        question.isNotEmpty() &&
            options.size == QUIZ_OPTIONS &&
            options.none(String::isEmpty) &&
            options.distinct().size == QUIZ_OPTIONS &&
            answer in 0 until QUIZ_OPTIONS
    return if (valid) QuizQuestion(question, options, answer, item.optString("explain").trim()) else null
}

private fun strings(array: JSONArray?): List<String> {
    array ?: return emptyList()
    return (0 until array.length()).map { array.optString(it) }
}

/** The stored form, the same shape the models answer in, plus who wrote it and when. */
internal fun LessonNotes.toJson(): JSONObject =
    JSONObject()
        .put("summary", JSONArray().also { array -> summary.forEach { array.put(it) } })
        .put(
            "key_terms",
            JSONArray().also { array -> terms.forEach { array.put(JSONObject().put("en", it.english).put("uz", it.uzbek)) } },
        ).put(
            "quiz",
            JSONArray().also { array ->
                quiz.forEach { question ->
                    array.put(
                        JSONObject()
                            .put("q", question.question)
                            .put("options", JSONArray().also { options -> question.options.forEach { options.put(it) } })
                            .put("answer", question.answer)
                            .put("explain", question.explanation),
                    )
                }
            },
        ).put("engine", engine)
        .put("created_at", createdAtMs)

/** How many quiz answers were right, for the line under the quiz. */
internal fun quizScore(
    quiz: List<QuizQuestion>,
    chosen: Map<Int, Int>,
): Int = quiz.indices.count { chosen[it] == quiz[it].answer }
