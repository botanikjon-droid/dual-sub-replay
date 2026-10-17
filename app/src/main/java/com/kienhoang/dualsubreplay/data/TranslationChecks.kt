package com.kienhoang.dualsubreplay.data

/*
 * Automatic safety checks of one translated sentence, English caption -> Uzbek translation, for the
 * three mistakes that change a medical meaning most: a number that changed or vanished, a unit that
 * vanished, and a "no/not" that vanished. They only point at a place to check (⚠ under the caption);
 * the translation is never changed. Pure functions, no network.
 */

internal enum class TranslationIssueKind { NUMBER, UNIT, NEGATION }

internal data class TranslationIssue(
    val kind: TranslationIssueKind,
    /** What is missing, as the English caption says it ("12", "mm", "not"). */
    val detail: String,
) {
    /** Short Uzbek text for the chip under the caption. */
    val label: String
        get() =
            when (kind) {
                TranslationIssueKind.NUMBER -> "⚠ son $detail tarjimada yo‘q"
                TranslationIssueKind.UNIT -> "⚠ birlik $detail tarjimada yo‘q"
                TranslationIssueKind.NEGATION -> "⚠ inkor (“$detail”) tarjimada yo‘qolgan bo‘lishi mumkin"
            }
}

private val ENGLISH_NUMBER_WORDS =
    mapOf(
        "zero" to 0.0,
        "one" to 1.0,
        "two" to 2.0,
        "three" to 3.0,
        "four" to 4.0,
        "five" to 5.0,
        "six" to 6.0,
        "seven" to 7.0,
        "eight" to 8.0,
        "nine" to 9.0,
        "ten" to 10.0,
        "eleven" to 11.0,
        "twelve" to 12.0,
        "thirteen" to 13.0,
        "fourteen" to 14.0,
        "fifteen" to 15.0,
        "sixteen" to 16.0,
        "seventeen" to 17.0,
        "eighteen" to 18.0,
        "nineteen" to 19.0,
        "twenty" to 20.0,
        "thirty" to 30.0,
        "forty" to 40.0,
        "fifty" to 50.0,
        "sixty" to 60.0,
        "seventy" to 70.0,
        "eighty" to 80.0,
        "ninety" to 90.0,
        "hundred" to 100.0,
    )

private val UZBEK_NUMBER_WORDS =
    mapOf(
        "nol" to 0.0,
        "bir" to 1.0,
        "ikki" to 2.0,
        "uch" to 3.0,
        "to‘rt" to 4.0,
        "besh" to 5.0,
        "olti" to 6.0,
        "yetti" to 7.0,
        "sakkiz" to 8.0,
        "to‘qqiz" to 9.0,
        "o‘n" to 10.0,
        "yigirma" to 20.0,
        "o‘ttiz" to 30.0,
        "qirq" to 40.0,
        "ellik" to 50.0,
        "oltmish" to 60.0,
        "yetmish" to 70.0,
        "sakson" to 80.0,
        "to‘qson" to 90.0,
        "yuz" to 100.0,
    )

/** English unit spellings -> the unit's id; Uzbek spellings that count as the same unit. */
private val UNITS =
    listOf(
        Triple("mm", listOf("mm", "millimeter", "millimeters", "millimetre", "millimetres"), listOf("mm", "millimetr")),
        Triple("cm", listOf("cm", "centimeter", "centimeters", "centimetre", "centimetres"), listOf("sm", "cm", "santimetr")),
        Triple("cm/s", listOf("cm/s", "cm/sec"), listOf("cm/s", "sm/s", "sm/sek", "santimetr")),
        Triple("m/s", listOf("m/s"), listOf("m/s", "metr")),
        Triple("ml", listOf("ml", "milliliter", "milliliters", "millilitre", "mls", "cc"), listOf("ml", "millilitr")),
        Triple("kPa", listOf("kpa", "kilopascal", "kilopascals"), listOf("kpa", "kilopaskal")),
        Triple("MHz", listOf("mhz", "megahertz"), listOf("mhz", "megagers", "megages")),
        Triple("%", listOf("%", "percent", "per cent"), listOf("%", "foiz")),
        Triple("weeks", listOf("week", "weeks"), listOf("hafta")),
    )

private val NEGATIONS = listOf("not", "no", "never", "without", "absent", "absence", "none", "neither", "nor", "n't", "negative")

/** Uzbek signs of negation: separate words and verb/adjective endings (-ma-, -mas, -siz). */
private val UZBEK_NEGATION =
    Regex(
        "\\b(yo‘q|yo'q|yoq|emas|hech|na\\b|manfiy|inkor)|siz\\b|sizlik|masdan|ma(y|s|di|gan|ydi|ng|ylik|sligi)\\b|may(di|man|miz|siz|lar)",
        RegexOption.IGNORE_CASE,
    )

private val DIGITS = Regex("\\d+(?:[.,]\\d+)?")
private val WORD = Regex("[\\p{L}‘’'/%]+")

private fun numbersIn(
    text: String,
    words: Map<String, Double>,
): List<Double> {
    val lower = text.lowercase().replace('\'', '‘').replace('’', '‘')
    val digits = DIGITS.findAll(lower).mapNotNull { it.value.replace(',', '.').toDoubleOrNull() }
    val spelled = WORD.findAll(lower).mapNotNull { words[it.value] }
    return (digits + spelled).toList()
}

/**
 * The checks for one sentence. A number counts as kept when the Uzbek has the same value written in
 * digits or as a single number word (compound words like "yigirma besh" are only compared as digits,
 * so a spelled-out compound never raises a false alarm about its parts).
 */
internal fun checkTranslation(
    english: String,
    uzbek: String?,
): List<TranslationIssue> {
    if (uzbek.isNullOrBlank() || english.isBlank()) return emptyList()
    val issues = mutableListOf<TranslationIssue>()
    val uzNumbers = numbersIn(uzbek, UZBEK_NUMBER_WORDS).toMutableList()
    DIGITS.findAll(english).forEach { match ->
        val value = match.value.replace(',', '.').toDoubleOrNull() ?: return@forEach
        if (!uzNumbers.remove(value)) issues += TranslationIssue(TranslationIssueKind.NUMBER, match.value)
    }
    val englishLower = " " + english.lowercase() + " "
    val uzbekLower = uzbek.lowercase()
    UNITS.forEach { (id, englishForms, uzbekForms) ->
        val inEnglish = englishForms.any { form -> Regex("(?<![\\p{L}])${Regex.escape(form)}(?![\\p{L}])").containsMatchIn(englishLower) }
        if (inEnglish && uzbekForms.none { uzbekLower.contains(it) }) issues += TranslationIssue(TranslationIssueKind.UNIT, id)
    }
    val negation =
        NEGATIONS.firstOrNull { word ->
            if (word == "n't") englishLower.contains("n't") else Regex("\\b$word\\b").containsMatchIn(englishLower)
        }
    if (negation != null && !UZBEK_NEGATION.containsMatchIn(uzbekLower.replace('\'', '‘'))) {
        issues += TranslationIssue(TranslationIssueKind.NEGATION, negation)
    }
    return issues
}

/** Checks for a caption row: only when the row holds its whole sentence, so a split row is not judged. */
internal fun translationChecksForRow(segment: SubtitleSegment): List<TranslationIssue> {
    val sentence = segment.sentence
    if (sentence != null && sentence.cuts.isNotEmpty()) return emptyList()
    return checkTranslation(segment.originalText, segment.translatedText)
}
