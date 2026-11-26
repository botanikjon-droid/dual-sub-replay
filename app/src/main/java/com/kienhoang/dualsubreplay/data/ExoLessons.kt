package com.kienhoang.dualsubreplay.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/*
 * "ExoKG darslari": the echocardiography course shipped with SHOKALON (assets/exo_darslar.json).
 * Modules hold lessons; a lesson has a drawn echo picture (a view, optionally with a defect and its
 * shunt jets), paragraphs the Uzbek voice reads, and questions with an explanation for each.
 * The pictures are drawn from the shapes in the file, so the course ships no third-party images.
 */

internal const val EXO_LESSONS_ASSET = "exo_darslar.json"

/** One drawn element of an echo picture, in the 400 × 330 picture space: numbers, texts and its kind. */
internal data class ExoShape(
    val kind: String,
    val numbers: Map<String, Float>,
    val texts: Map<String, String>,
) {
    fun n(
        key: String,
        default: Float = 0f,
    ): Float = numbers[key] ?: default

    fun has(key: String): Boolean = (numbers[key] ?: 0f) != 0f

    fun text(key: String): String? = texts[key]

    /** This shape with a defect's changes applied (a moved valve, a smaller chamber, a new label). */
    fun with(change: ExoShape?): ExoShape =
        if (change == null) this else ExoShape(change.kind.ifEmpty { kind }, numbers + change.numbers, texts + change.texts)
}

internal data class ExoView(
    val name: String,
    val shapes: List<ExoShape>,
)

/** What a defect changes in its view: holes, added shapes, flow jets and changed shapes. */
internal data class ExoOverlay(
    val view: String,
    val gaps: List<ExoShape>,
    val added: List<ExoShape>,
    val jets: List<List<Float>>,
    val changes: Map<String, ExoShape>,
    /** AV canal: one common valve line replaces the two valves. */
    val commonValve: Boolean,
)

internal data class ExoQuestion(
    val question: String,
    val options: List<String>,
    val answer: Int,
    val explanation: String,
)

internal data class ExoLesson(
    val id: String,
    val title: String,
    val view: String?,
    val defect: String?,
    val paragraphs: List<String>,
    val questions: List<ExoQuestion>,
)

internal data class ExoModule(
    val id: String,
    val title: String,
    val subtitle: String,
    val lessons: List<ExoLesson>,
)

internal data class ExoCourse(
    val views: Map<String, ExoView>,
    val defects: Map<String, ExoOverlay>,
    val modules: List<ExoModule>,
) {
    val lessons: List<ExoLesson> get() = modules.flatMap { it.lessons }
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

private fun JSONObject.keyList(): List<String> = keys().asSequence().toList()

internal fun parseExoShape(json: JSONObject): ExoShape {
    val numbers = HashMap<String, Float>()
    val texts = HashMap<String, String>()
    json.keyList().filter { it != "k" }.forEach { key ->
        when (val value = json.get(key)) {
            is Number -> numbers[key] = value.toFloat()
            is Boolean -> numbers[key] = if (value) 1f else 0f
            else -> texts[key] = value.toString()
        }
    }
    return ExoShape(json.optString("k"), numbers, texts)
}

private fun parseOverlay(json: JSONObject): ExoOverlay {
    val mods = json.optJSONObject("mods") ?: JSONObject()
    val changes =
        mods
            .keyList()
            .mapNotNull { key -> mods.optJSONObject(key)?.let { key to parseExoShape(it) } }
            .toMap()
    val jets = json.optJSONArray("jets") ?: JSONArray()
    return ExoOverlay(
        view = json.getString("view"),
        gaps = (json.optJSONArray("gaps") ?: JSONArray()).objects().map(::parseExoShape),
        added = (json.optJSONArray("add") ?: JSONArray()).objects().map(::parseExoShape),
        jets =
            (0 until jets.length()).map { i ->
                jets.getJSONArray(i).let { a -> (0 until a.length()).map { a.getDouble(it).toFloat() } }
            },
        changes = changes,
        commonValve = mods.optString("lines") == "common",
    )
}

private fun parseQuestion(json: JSONObject) =
    ExoQuestion(
        question = json.getString("q"),
        options = json.getJSONArray("options").strings(),
        answer = json.getInt("answer"),
        explanation = json.optString("explain"),
    )

private fun parseLesson(json: JSONObject): ExoLesson {
    val sim = json.optJSONObject("sim")
    return ExoLesson(
        id = json.getString("id"),
        title = json.getString("title"),
        view = sim?.optString("view")?.ifEmpty { null },
        defect = sim?.optString("defect")?.ifEmpty { null },
        paragraphs = json.getJSONArray("paragraphs").strings(),
        questions = json.getJSONArray("questions").objects().map(::parseQuestion),
    )
}

internal fun parseExoCourse(text: String): ExoCourse {
    val root = JSONObject(text)
    val views = root.getJSONObject("views")
    val defects = root.getJSONObject("defects")
    return ExoCourse(
        views =
            views.keyList().associateWith { key ->
                val view = views.getJSONObject(key)
                ExoView(view.getString("name"), view.getJSONArray("shapes").objects().map(::parseExoShape))
            },
        defects = defects.keyList().associateWith { parseOverlay(defects.getJSONObject(it)) },
        modules =
            root.getJSONArray("modules").objects().map { module ->
                ExoModule(
                    id = module.getString("id"),
                    title = module.getString("title"),
                    subtitle = module.optString("subtitle"),
                    lessons = module.getJSONArray("lessons").objects().map(::parseLesson),
                )
            },
    )
}

/** The course and the doctor's best score per lesson (correct answers out of the lesson's questions). */
internal object ExoLessonStore {
    private const val PREFS = "exo_darslar"
    private val _course = MutableStateFlow<ExoCourse?>(null)
    val course: StateFlow<ExoCourse?> = _course
    private val _scores = MutableStateFlow<Map<String, Int>>(emptyMap())
    val scores: StateFlow<Map<String, Int>> = _scores

    suspend fun load(context: Context) {
        if (_course.value != null) return
        val app = context.applicationContext
        val loaded =
            withContext(Dispatchers.IO) {
                val course =
                    app.assets
                        .open(EXO_LESSONS_ASSET)
                        .bufferedReader()
                        .use { parseExoCourse(it.readText()) }
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                course to course.lessons.associate { it.id to prefs.getInt(it.id, -1) }.filterValues { it >= 0 }
            }
        _scores.value = loaded.second
        _course.value = loaded.first
    }

    fun saveScore(
        context: Context,
        lessonId: String,
        correct: Int,
    ) {
        if (correct <= (_scores.value[lessonId] ?: -1)) return
        _scores.value = _scores.value + (lessonId to correct)
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(lessonId, correct)
            .apply()
    }
}
