package com.kienhoang.dualsubreplay.data

/*
 * The printable form of the lesson notes: one book of every lesson's konspekt with its pictures, terms
 * and quiz (answers at the end of each lesson, so the quiz can be done on paper). Pure blocks; the PDF
 * drawing and printing live in ui/NotesPdf.kt.
 */

internal data class PrintableLesson(
    val videoId: String,
    val title: String,
    val notes: LessonNotes,
)

internal sealed interface PrintBlock {
    data class BookTitle(
        val text: String,
        val subtitle: String,
    ) : PrintBlock

    /** Starts a lesson on a new page. */
    data class LessonTitle(
        val text: String,
        val detail: String,
    ) : PrintBlock

    data class Heading(
        val text: String,
    ) : PrintBlock

    data class Paragraph(
        val text: String,
        val bullet: Boolean = false,
    ) : PrintBlock

    data class Picture(
        val videoId: String,
        val image: String,
        val caption: String,
    ) : PrintBlock
}

/** Lessons in the list's order turned around (oldest first), then lessons with notes but no list entry. */
internal fun printOrder(
    notesIds: List<String>,
    records: List<LessonRecord>,
): List<String> {
    val listed = records.asReversed().map { it.videoId }.filter { it in notesIds }
    return listed + notesIds.filterNot { it in listed }.sorted()
}

internal fun notesPrintBlocks(
    lessons: List<PrintableLesson>,
    bookSubtitle: String,
): List<PrintBlock> =
    buildList {
        add(PrintBlock.BookTitle("UZI darslari: konspekt", bookSubtitle))
        lessons.forEach { lesson -> addAll(lessonBlocks(lesson)) }
    }

private fun lessonBlocks(lesson: PrintableLesson): List<PrintBlock> =
    buildList {
        val notes = lesson.notes
        add(PrintBlock.LessonTitle(lesson.title, "youtu.be/${lesson.videoId}"))
        val pictures = notes.frames.filter { it.image.isNotEmpty() }
        if (pictures.isNotEmpty()) {
            add(PrintBlock.Heading("Rasmli konspekt"))
            pictures.forEach { add(PrintBlock.Picture(lesson.videoId, it.image, "${formatVideoTime(it.seconds)} · ${it.caption}")) }
        }
        if (notes.summary.isNotEmpty()) {
            add(PrintBlock.Heading("Konspekt"))
            notes.summary.forEach { add(PrintBlock.Paragraph(it, bullet = true)) }
        }
        if (notes.terms.isNotEmpty()) {
            add(PrintBlock.Heading("Asosiy atamalar"))
            notes.terms.forEach { add(PrintBlock.Paragraph("${it.english} — ${it.uzbek}", bullet = true)) }
        }
        if (notes.quiz.isNotEmpty()) {
            add(PrintBlock.Heading("Test"))
            notes.quiz.forEachIndexed { index, question ->
                add(PrintBlock.Paragraph("${index + 1}. ${question.question}"))
                question.options.forEachIndexed { option, text -> add(PrintBlock.Paragraph("${optionLetter(option)}) $text")) }
            }
            add(PrintBlock.Heading("Javoblar"))
            notes.quiz.forEachIndexed { index, question ->
                val explanation =
                    question.explanation
                        .takeIf(String::isNotBlank)
                        ?.let { " — $it" }
                        .orEmpty()
                add(PrintBlock.Paragraph("${index + 1}-${optionLetter(question.answer)}$explanation"))
            }
        }
    }

private fun optionLetter(index: Int): Char = 'a' + index
