package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.data.UziTestBankKind
import com.kienhoang.dualsubreplay.data.parseUziTestBank
import org.junit.Assert.assertTrue
import org.junit.Test

class UziAskTextTest {
    @Test
    fun askingCarriesTheQuestionTheAnswerAndTheNote() {
        val question =
            parseUziTestBank(
                """{"version":1,"lessons":[],"questions":[{"n":7,"l":"1","t":"jigar","q":"Jigar abssessi","o":[],"a":"Gipoexogen o‘choq.","e":"x","f":"","s":0}]}""",
            ).questions.single()
        val text = uziAskText(UziTestBankKind.ORAL, question, "kapsula nima?")
        assertTrue(text.contains("7-savol: «Jigar abssessi»"))
        assertTrue(text.contains("Javob: «Gipoexogen o‘choq.»"))
        assertTrue(text.contains("Mening izohim: kapsula nima?"))
        assertTrue(text.endsWith("Tushunmagan joyim: "))
    }
}
