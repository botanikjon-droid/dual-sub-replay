package com.kienhoang.dualsubreplay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class JapaneseMorphologyTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun loadAnalyzer() {
            assertTrue("Kuromoji should load from the bundled dictionary", JapaneseMorphology.awaitReady())
        }
    }

    private fun words(text: String) = LanguageAwareTokenizer.tokenize(text, "ja").map { it.text }

    @Test
    fun verbsKeepTheirPoliteAndPastEndings() {
        assertEquals(listOf("日本", "は", "まだ", "冬", "だ", "と", "思います", "。"), words("日本はまだ冬だと思います。"))
    }

    @Test
    fun ownerExampleSelectsWholeWords() {
        assertEquals(
            listOf("今", "２月", "です", "。", "台湾", "は", "もう", "あったかい", "です", "。", "春", "です", "ね", "。"),
            words("今２月です。台湾はもうあったかいです。春ですね。"),
        )
    }

    @Test
    fun teFormsAndAuxiliaryVerbsStayOnTheVerb() {
        assertEquals(
            listOf("５歳", "か", "６歳", "ぐらい", "の", "時", "に", "、", "毎日", "お母さん", "に", "手伝ってもらって", "、"),
            words("５歳か６歳ぐらいの時に、毎日お母さんに手伝ってもらって、"),
        )
        assertEquals(listOf("食べられなかった"), words("食べられなかった"))
        assertEquals(listOf("見てみたい", "と", "思ってる"), words("見てみたいと思ってる"))
        assertEquals(listOf("行っちゃった"), words("行っちゃった"))
    }

    @Test
    fun suruNounsJoinTheirVerb() {
        val tokens = LanguageAwareTokenizer.tokenize("日本語を勉強しています", "ja")

        assertEquals(listOf("日本語", "を", "勉強しています"), tokens.map { it.text })
        assertEquals(PartOfSpeech.VERB, tokens[2].partOfSpeech)
        assertEquals("勉強する", tokens[2].baseForm)
    }

    @Test
    fun copulasAndParticlesStaySeparate() {
        assertEquals(
            listOf("自転車", "が", "好き", "でした", "。"),
            words("自転車が好きでした。"),
        )
        assertEquals(listOf("台湾", "は", "あったかい", "です"), words("台湾はあったかいです"))
    }

    @Test
    fun pastTenseDaStaysOnTheVerb() {
        assertEquals(listOf("本", "を", "読んだ"), words("本を読んだ"))
    }

    @Test
    fun adjectivesKeepNegativeAndPastEndings() {
        assertEquals(listOf("高くない"), words("高くない"))
        assertEquals(listOf("暑かった"), words("暑かった"))
    }

    @Test
    fun wordsCarryPartOfSpeechReadingAndOffsets() {
        val text = "静かな部屋で本を読んでいた"
        val tokens = LanguageAwareTokenizer.tokenize(text, "ja")
        val verb = tokens.single { it.text == "読んでいた" }

        assertEquals(PartOfSpeech.VERB, verb.partOfSpeech)
        assertEquals("よんでいた", verb.reading)
        assertEquals("読む", verb.baseForm)
        assertEquals(text.indexOf("読"), verb.startIndex)
        assertEquals(text.length, verb.endIndex)
        assertEquals(PartOfSpeech.ADJECTIVE, tokens.first { it.text == "静か" }.partOfSpeech)
        assertEquals(PartOfSpeech.PARTICLE, tokens.first { it.text == "を" }.partOfSpeech)
        tokens.forEach { assertEquals(it.text, text.substring(it.startIndex, it.endIndex)) }
    }

    @Test
    fun kanaOnlyWordsHaveNoReading() {
        val tokens = LanguageAwareTokenizer.tokenize("ラーメンを食べる", "ja")

        assertEquals(listOf("ラーメン", "を", "食べる"), tokens.map { it.text })
        assertNull(tokens[0].reading)
        assertEquals("たべる", tokens[2].reading)
    }

    @Test
    fun spacesSplitWordsAndAreDropped() {
        val text = "こんにちは 世界"
        val tokens = LanguageAwareTokenizer.tokenize(text, "ja")

        assertTrue(tokens.none { it.text.isBlank() })
        tokens.forEach { assertEquals(it.text, text.substring(it.startIndex, it.endIndex)) }
    }

    @Test
    fun chineseKeepsTheHeuristicAndKanaMeansJapanese() {
        assertTrue(LanguageAwareTokenizer.isJapanese("思います", null))
        assertTrue(LanguageAwareTokenizer.isJapanese("日本", "ja-JP"))
        assertTrue(!LanguageAwareTokenizer.isJapanese("我们", "zh-Hans"))
        assertTrue(!LanguageAwareTokenizer.isJapanese("日本", null))
    }

    @Test
    fun groupingWorksOnHandBuiltMorphemes() {
        val morphemes =
            listOf(
                Morpheme("お", 0, "接頭詞", "名詞接続"),
                Morpheme("茶", 1, "名詞", "一般", reading = "チャ"),
                Morpheme("を", 2, "助詞", "格助詞"),
                Morpheme("飲み", 3, "動詞", "自立", conjugationForm = "連用形", baseForm = "飲む", reading = "ノミ"),
                Morpheme("ます", 5, "助動詞", baseForm = "ます", reading = "マス"),
            )

        val words = groupJapaneseMorphemes(morphemes)

        assertEquals(listOf("お茶", "を", "飲みます"), words.map { it.text })
        assertEquals("おちゃ", words[0].reading)
        assertEquals("飲む", words[2].baseForm)
    }
}
