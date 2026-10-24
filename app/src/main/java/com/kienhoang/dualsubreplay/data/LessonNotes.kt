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

/** A moment of the lecture worth a picture: the video frame at [seconds], saved as [image] once taken. */
internal data class NoteFrame(
    val seconds: Int,
    /** Uzbek: what the picture shows. */
    val caption: String,
    /** File name in the lesson's notes folder ("754.jpg"), empty until the frame is taken. */
    val image: String = "",
)

internal data class LessonNotes(
    val summary: List<String>,
    val terms: List<NoteTerm>,
    val quiz: List<QuizQuestion>,
    /** "Gemini" or "Claude". */
    val engine: String = "",
    val createdAtMs: Long = 0L,
    val frames: List<NoteFrame> = emptyList(),
)

/** At most this many pictures per lesson; frames closer than [MIN_FRAME_GAP_SECONDS] are one picture. */
internal const val MAX_NOTE_FRAMES = 12
internal const val MIN_FRAME_GAP_SECONDS = 5

private val FRAME_IMAGE = Regex("[0-9]{1,6}\\.jpg")

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
internal fun lessonNotesFromJson(
    json: JSONObject,
    requireStudy: Boolean = true,
): LessonNotes {
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
    val frames = noteFrames(json.optJSONArray("key_frames"))
    // Stored notes may hold only the doctor's own pictures; a model's answer must be complete.
    if (requireStudy || frames.isEmpty()) {
        if (summary.isEmpty()) throw LessonNotesException("Konspekt bo‘sh qaytdi. Qayta urinib ko‘ring.")
        if (quiz.isEmpty()) throw LessonNotesException("Test savollari yaroqsiz qaytdi. Qayta urinib ko‘ring.")
    }
    return LessonNotes(
        summary = summary,
        terms = terms,
        quiz = quiz,
        engine = json.optString("engine"),
        createdAtMs = json.optLong("created_at"),
        frames = frames,
    )
}

/** Pictured moments in lecture order, near-duplicates and blank captions dropped, at most [MAX_NOTE_FRAMES]. */
private fun noteFrames(array: JSONArray?): List<NoteFrame> {
    array ?: return emptyList()
    val frames =
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val seconds = item.optInt("t", -1)
            val caption = item.optString("caption").trim()
            val image = item.optString("image").takeIf { FRAME_IMAGE.matches(it) }.orEmpty()
            if (seconds < 0 || caption.isEmpty()) null else NoteFrame(seconds, caption, image)
        }
    return distinctFrames(frames)
}

internal fun distinctFrames(frames: List<NoteFrame>): List<NoteFrame> {
    val kept = mutableListOf<NoteFrame>()
    for (frame in frames.sortedBy { it.seconds }) {
        val last = kept.lastOrNull()
        when {
            last == null || frame.seconds - last.seconds >= MIN_FRAME_GAP_SECONDS -> kept += frame
            // The doctor's own picture (or a taken one) wins over a planned one at the same moment.
            last.image.isEmpty() && frame.image.isNotEmpty() -> kept[kept.lastIndex] = frame
        }
    }
    // Taken pictures always stay; planned ones fill the rest of the limit.
    var planned = (MAX_NOTE_FRAMES - kept.count { it.image.isNotEmpty() }).coerceAtLeast(0)
    return kept.filter { it.image.isNotEmpty() || planned-- > 0 }
}

/** The notes with the picture of the frame at [seconds] saved as [image]. */
internal fun LessonNotes.withFrameImage(
    seconds: Int,
    image: String,
): LessonNotes = copy(frames = frames.map { if (it.seconds == seconds) it.copy(image = image) else it })

/** Adds the doctor's own picture; notes that do not exist yet hold only pictures until written. */
internal fun addManualFrame(
    notes: LessonNotes?,
    frame: NoteFrame,
): LessonNotes {
    val base = notes ?: LessonNotes(emptyList(), emptyList(), emptyList())
    val others = base.frames.filterNot { kotlin.math.abs(it.seconds - frame.seconds) < MIN_FRAME_GAP_SECONDS }
    // The doctor's pictures are never dropped by the limit; planned frames give way first.
    return base.copy(frames = (others + frame).sortedBy { it.seconds })
}

/** "12:34" or "1:02:03". */
internal fun formatVideoTime(seconds: Int): String {
    val h = seconds / SECONDS_PER_HOUR
    val m = seconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    val s = seconds % SECONDS_PER_MINUTE
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3_600

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
        ).put(
            "key_frames",
            JSONArray().also { array ->
                frames.forEach { array.put(JSONObject().put("t", it.seconds).put("caption", it.caption).put("image", it.image)) }
            },
        ).put("engine", engine)
        .put("created_at", createdAtMs)

/** How many quiz answers were right, for the line under the quiz. */
internal fun quizScore(
    quiz: List<QuizQuestion>,
    chosen: Map<Int, Int>,
): Int = quiz.indices.count { chosen[it] == quiz[it].answer }
