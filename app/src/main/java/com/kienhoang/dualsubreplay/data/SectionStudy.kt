package com.kienhoang.dualsubreplay.data

import kotlin.random.Random

/*
 * Study by section: the konspekt of every lecture of an ultrasound section in one place, and one test made
 * of the lectures' own quiz questions. Pure; the screens are ui/SectionStudy.kt.
 */

/** A quiz question of a section test, with the lecture it comes from. */
internal data class SectionQuestion(
    val videoId: String,
    val lessonTitle: String,
    val question: QuizQuestion,
)

internal const val SECTION_TEST_SIZE = 20

/**
 * A section test of at most [size] questions: taken in turn from each lecture (so every lecture with notes
 * is asked about), in random order, with the answers of each question shuffled too (so the right answer is
 * not remembered by its place).
 */
internal fun sectionQuiz(
    lessons: List<PrintableLesson>,
    size: Int = SECTION_TEST_SIZE,
    random: Random = Random.Default,
): List<SectionQuestion> {
    val pools =
        lessons
            .map { lesson ->
                lesson.notes.quiz
                    .shuffled(random)
                    .map { SectionQuestion(lesson.videoId, lesson.title, it) }
            }.filter(List<SectionQuestion>::isNotEmpty)
            .shuffled(random)
    val picked = mutableListOf<SectionQuestion>()
    var round = 0
    while (picked.size < size && pools.any { round < it.size }) {
        pools.forEach { pool -> if (round < pool.size && picked.size < size) picked += pool[round] }
        round++
    }
    return picked.shuffled(random).map { it.copy(question = shuffledOptions(it.question, random)) }
}

/** The same question with its answers in another order; the right answer moves with them. */
internal fun shuffledOptions(
    question: QuizQuestion,
    random: Random,
): QuizQuestion {
    val order = question.options.indices.shuffled(random)
    return question.copy(options = order.map { question.options[it] }, answer = order.indexOf(question.answer))
}

/** "17 / 20 (85 %)" and a word on it, for the end of a test. */
internal fun sectionTestVerdict(
    right: Int,
    total: Int,
): String {
    if (total == 0) return ""
    val percent = right * PERCENT / total
    val word =
        when {
            percent >= EXCELLENT -> "a’lo"
            percent >= GOOD -> "yaxshi"
            percent >= PASS -> "qoniqarli"
            else -> "konspektni yana bir bor o‘qib chiqing"
        }
    return "$right / $total ($percent %) — $word"
}

private const val PERCENT = 100
private const val EXCELLENT = 90
private const val GOOD = 75
private const val PASS = 55
