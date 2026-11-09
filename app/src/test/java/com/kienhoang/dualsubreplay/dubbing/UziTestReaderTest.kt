package com.kienhoang.dualsubreplay.dubbing

import com.kienhoang.dualsubreplay.data.UziTestLesson
import com.kienhoang.dualsubreplay.data.UziTestOption
import com.kienhoang.dualsubreplay.data.UziTestQuestion
import org.junit.Assert.assertEquals
import org.junit.Test

class UziTestReaderTest {
    private val question =
        UziTestQuestion(
            number = 1,
            topic = "jigar",
            question = "Жигар нечта сегментга бўлинади?",
            options = listOf(UziTestOption("8", true), UziTestOption("6", false)),
            answer = "саккизта сегментга",
            explanation = "Куино бўйича саккизта.",
            warning = "",
            suggested = false,
            searchKey = "",
        )

    @Test
    fun questionIsReadThenPauseThenRepeatedAnswerThenExplanation() {
        val lines = questionScript(question, ReaderSettings(thinkSeconds = 4, repeats = 2, explain = true))
        assertEquals(
            listOf(ReadStage.QUESTION, ReadStage.THINK, ReadStage.ANSWER, ReadStage.ANSWER, ReadStage.EXPLANATION, ReadStage.THINK),
            lines.map { it.stage },
        )
        assertEquals(4000L, lines[1].pauseAfterMs)
        assertEquals("Савол 1. Жигар нечта сегментга бўлинади?", lines[0].text)
    }

    @Test
    fun noPauseNoExplanationButTheWarningIsAlwaysRead() {
        val lines = questionScript(question.copy(warning = "калит шубҳали"), ReaderSettings(thinkSeconds = 0, repeats = 1, explain = false))
        assertEquals(listOf(ReadStage.QUESTION, ReadStage.ANSWER, ReadStage.WARNING, ReadStage.THINK), lines.map { it.stage })
    }

    @Test
    fun lessonIsTitleThenParagraphs() {
        val lines = lessonScript(UziTestLesson("jigar", "Жигар", listOf("Биринчи.", "Иккинчи.")))
        assertEquals(listOf(-1, 0, 1), lines.map { it.paragraph })
    }
}
