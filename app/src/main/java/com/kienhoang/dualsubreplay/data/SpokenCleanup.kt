package com.kienhoang.dualsubreplay.data

/*
 * Two things a translated line should not carry to the screen and the voice: the lecturer's hesitations
 * ("uh", "um") copied from the captions, and numbers written with thousands separators ("20,000"), which the
 * Uzbek voice reads as "yigirma, nol nol nol". Numbers of four digits and more are spelled out in Uzbek.
 */

private val HESITATION =
    Regex("""(?<![\p{L}\p{N}’'‘-])(?:u+h+|u+h*m+|e+r+m+|e+h+|a+h+|h+m+)(?![\p{L}\p{N}’'‘-])(?:,|\.{2,}|…)*\s*""", RegexOption.IGNORE_CASE)
private val SPACE_BEFORE_MARK = Regex("""\s+([,.;:!?…])""")
private val DOUBLE_MARK = Regex("""([,;])\s*([,;.!?…])""")
private val SPACES = Regex("""[ \t]+""")

/** [text] without "uh", "um" and the like; the rest of the sentence is left as it is. */
internal fun withoutHesitations(text: String): String {
    if (!HESITATION.containsMatchIn(text)) return text
    val cleaned =
        text
            .replace(HESITATION, " ")
            .replace(SPACES, " ")
            .replace(SPACE_BEFORE_MARK, "$1")
            .replace(DOUBLE_MARK, "$2")
            .trim()
            .trimStart(',', ';', ' ')
    return cleaned.replaceFirstChar { if (text.firstOrNull()?.isUpperCase() == true) it.uppercaseChar() else it }
}

/** A whole number with thousands groups ("20,000", "1 500 000") or of four digits and more ("20000"). */
private val BIG_NUMBER = Regex("""(?<![\p{N},.])(\d{1,3}(?:[,   ]\d{3})+|\d{4,12})(?![\p{N}]|[,.]\d)""")

private val ONES = listOf("", "bir", "ikki", "uch", "to‘rt", "besh", "olti", "yetti", "sakkiz", "to‘qqiz")
private val TENS = listOf("", "o‘n", "yigirma", "o‘ttiz", "qirq", "ellik", "oltmish", "yetmish", "sakson", "to‘qson")
private val SCALES = listOf("" to 1L, "ming" to 1_000L, "million" to 1_000_000L, "milliard" to 1_000_000_000L)

private const val HUNDRED = 100
private const val TEN = 10

private fun belowThousand(n: Int): List<String> {
    val words = mutableListOf<String>()
    val hundreds = n / HUNDRED
    if (hundreds > 0) {
        if (hundreds > 1) words += ONES[hundreds]
        words += "yuz"
    }
    val rest = n % HUNDRED
    if (rest / TEN > 0) words += TENS[rest / TEN]
    if (rest % TEN > 0) words += ONES[rest % TEN]
    return words
}

/** [n] in Uzbek words: 20000 = "yigirma ming", 1500 = "ming besh yuz", 2018 = "ikki ming o‘n sakkiz". */
internal fun uzbekNumberWords(n: Long): String {
    if (n == 0L) return "nol"
    val words = mutableListOf<String>()
    var rest = n
    for ((name, size) in SCALES.asReversed()) {
        val count = (rest / size).toInt()
        rest %= size
        if (count == 0) continue
        if (name.isEmpty()) {
            words += belowThousand(count)
        } else {
            if (!(name == "ming" && count == 1)) words += belowThousand(count)
            words += name
        }
    }
    return words.joinToString(" ")
}

/** [text] with big numbers spelled out, so the voice reads "20,000" as "yigirma ming". */
internal fun spokenNumbers(text: String): String =
    text.replace(BIG_NUMBER) { match ->
        val digits = match.value.filter(Char::isDigit)
        digits.toLongOrNull()?.let(::uzbekNumberWords) ?: match.value
    }
