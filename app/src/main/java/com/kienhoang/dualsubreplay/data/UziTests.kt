package com.kienhoang.dualsubreplay.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject

/*
 * The UZI test bank shipped with SHOKALON (assets/uzi_tests.json): 465 qualification-exam questions
 * with their keyed answers, a short plain-language explanation for each, a warning where the key looks
 * doubtful, and ten topic lessons. The doctor listens to it with the Uzbek voice and tests himself.
 */

internal const val UZI_TESTS_ASSET = "uzi_tests.json"
internal const val UZI_ATTESTATION_ASSET = "uzi_attestation.json"
internal const val UZI_ORAL_ASSET = "uzi_oral.json"

/** The qualification levels of the oral questions, in the order of the official list. */
internal val UZI_ORAL_LEVELS =
    linkedMapOf(
        "2-3" to "3- va 2-toifa",
        "1" to "1-toifa",
        "oliy" to "Oliy toifa",
    )

/**
 * The two banks: the 465 keyed qualification questions (Cyrillic) and the 982 attestation questions
 * (Latin; the file had no key, so most answers are Claude's, marked as suggested, and the rest come from
 * the keyed bank where the same question is there).
 */
internal enum class UziTestBankKind(
    val asset: String,
    val title: String,
    val subtitle: String,
    /** False for oral questions: they have no options, so there is no self-test tab. */
    val quiz: Boolean = true,
) {
    QUALIFICATION(UZI_TESTS_ASSET, "UZI testlari", "465 savol · eshitib yodlash, o‘zini sinash, qidirish"),
    ATTESTATION(UZI_ATTESTATION_ASSET, "Atestatsiya testlari", "982 savol · har biriga oddiy tushuntirish"),
    ORAL(UZI_ORAL_ASSET, "Og‘zaki savollar (atestatsiya)", "450 savol · tayyor og‘zaki javob, Sardor o‘qiydi, izoh va savol", quiz = false),
}

internal data class UziTestOption(
    val text: String,
    val correct: Boolean,
)

internal data class UziTestQuestion(
    val number: Int,
    val topic: String,
    val question: String,
    val options: List<UziTestOption>,
    /** The keyed answer as one spoken phrase. */
    val answer: String,
    /** Why the answer is right, in simple words. */
    val explanation: String,
    /** Non-empty when the key looks doubtful: what is probably right. */
    val warning: String,
    /** True when the source file had no key and the answer was suggested by Claude. */
    val suggested: Boolean,
    /** [question] and options folded by [uziSearchKey]. */
    val searchKey: String,
    /** The qualification level of an oral question ([UZI_ORAL_LEVELS]); empty in the test banks. */
    val level: String = "",
) {
    val correctCount: Int get() = options.count { it.correct }
}

internal data class UziTestLesson(
    val id: String,
    val title: String,
    val paragraphs: List<String>,
)

internal data class UziTestBank(
    val questions: List<UziTestQuestion>,
    val lessons: List<UziTestLesson>,
)

/** Topic ids of the bank in display order, with their Uzbek names. */
internal val UZI_TEST_TOPICS =
    linkedMapOf(
        "jigar" to "Jigar",
        "otpuf" to "O‘t pufagi",
        "pank" to "Me’da osti bezi",
        "dopp" to "Doppler",
        "qalqon" to "Qalqonsimon bez",
        "neyro" to "Neyrosonografiya",
        "homila" to "Akusherlik",
        "gin" to "Ginekologiya",
        "fizika" to "Fizika",
        "buyrak" to "Buyrak va boshqa",
        "kokrak" to "Sut bezi",
        "yurak" to "Exokardiografiya",
        "tashkil" to "Tashkiliy masalalar",
        "boshqa" to "Boshqa a’zolar",
    )

internal fun uziTopicName(id: String): String = UZI_TEST_TOPICS[id] ?: id

internal fun parseUziTestBank(json: String): UziTestBank {
    val root = JSONObject(json)
    val questions = root.getJSONArray("questions")
    val lessons = root.getJSONArray("lessons")
    return UziTestBank(
        questions = (0 until questions.length()).map { parseQuestion(questions.getJSONObject(it)) },
        lessons =
            (0 until lessons.length()).map { index ->
                val lesson = lessons.getJSONObject(index)
                val paragraphs = lesson.getJSONArray("p")
                UziTestLesson(
                    id = lesson.getString("id"),
                    title = lesson.getString("t"),
                    paragraphs = (0 until paragraphs.length()).map(paragraphs::getString),
                )
            },
    )
}

private fun parseQuestion(item: JSONObject): UziTestQuestion {
    val rawOptions = item.getJSONArray("o")
    val options =
        (0 until rawOptions.length()).map { index ->
            val option = rawOptions.getJSONArray(index)
            UziTestOption(option.getString(0), option.getInt(1) == 1)
        }
    val question = item.getString("q")
    return UziTestQuestion(
        number = item.getInt("n"),
        topic = item.getString("t"),
        question = question,
        options = options,
        answer = item.getString("a"),
        explanation = item.optString("e"),
        warning = item.optString("f"),
        suggested = item.optInt("s") == 1,
        searchKey = uziSearchKey(question + " | " + options.joinToString(" | ") { it.text }),
        level = item.optString("l"),
    )
}

/** The questions of [topic] (all of them for null) that match [query]. */
internal fun UziTestBank.filter(
    topic: String?,
    query: String = "",
    level: String? = null,
): List<UziTestQuestion> {
    val terms = uziSearchTerms(query)
    val number = query.trim().removePrefix("#").toIntOrNull()
    val chosen =
        questions.filter { question ->
            when {
                number != null -> question.number == number
                topic != null && question.topic != topic -> false
                level != null && question.level != level -> false
                else -> uziMatches(question.searchKey, terms)
            }
        }
    // An oral question asked at several levels is shown once when no level is chosen.
    return if (level ==
        null
    ) {
        chosen.distinctBy { if (it.options.isEmpty()) it.question.trim().lowercase() else "#${it.number}" }
    } else {
        chosen
    }
}

/** Loads each bank once from the assets; a bank is missing from [banks] until then. */
internal object UziTestStore {
    private val _banks = MutableStateFlow<Map<UziTestBankKind, UziTestBank>>(emptyMap())
    val banks: StateFlow<Map<UziTestBankKind, UziTestBank>> = _banks

    suspend fun load(
        context: Context,
        kind: UziTestBankKind,
    ) {
        if (kind in _banks.value) return
        val parsed =
            withContext(Dispatchers.IO) {
                parseUziTestBank(
                    context.assets
                        .open(kind.asset)
                        .bufferedReader(Charsets.UTF_8)
                        .use { it.readText() },
                )
            }
        _banks.update { it + (kind to parsed) }
    }
}
