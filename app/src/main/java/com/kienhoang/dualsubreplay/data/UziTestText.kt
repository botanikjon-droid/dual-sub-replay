package com.kienhoang.dualsubreplay.data

/*
 * Text helpers for the UZI test bank (assets/uzi_tests.json). The bank is written in Uzbek Cyrillic,
 * like the real qualification exam, while the Uzbek voice (Sardor/Madina) reads Uzbek Latin. So the
 * screen shows the Cyrillic text and the voice gets a Latin copy; search folds both scripts into one
 * key, so the doctor can type "portal vena" or "портал вена" and find the same questions.
 */

private const val CYRILLIC_VOWELS = "аеёиоуэюяўАЕЁИОУЭЮЯЎ"

/** One Cyrillic letter in Uzbek Latin; е and ц depend on their neighbours and are handled apart. */
private val CYRILLIC_TO_LATIN =
    mapOf(
        'а' to "a",
        'б' to "b",
        'в' to "v",
        'г' to "g",
        'д' to "d",
        'ё' to "yo",
        'ж' to "j",
        'з' to "z",
        'и' to "i",
        'й' to "y",
        'к' to "k",
        'л' to "l",
        'м' to "m",
        'н' to "n",
        'о' to "o",
        'п' to "p",
        'р' to "r",
        'с' to "s",
        'т' to "t",
        'у' to "u",
        'ф' to "f",
        'х' to "x",
        'ч' to "ch",
        'ш' to "sh",
        'щ' to "sh",
        'ъ' to "'",
        'ы' to "i",
        'ь' to "",
        'э' to "e",
        'ю' to "yu",
        'я' to "ya",
        'ў' to "oʻ",
        'қ' to "q",
        'ғ' to "gʻ",
        'ҳ' to "h",
    )

/** Uzbek Cyrillic to Uzbek Latin, following the official letter correspondences. Latin text is kept. */
internal fun cyrillicToUzbekLatin(text: String): String {
    val out = StringBuilder(text.length + text.length / 4)
    text.forEachIndexed { index, char ->
        val lower = char.lowercaseChar()
        val previous = text.getOrNull(index - 1)
        val wordStart = previous == null || !previous.isLetter()
        val latin =
            when (lower) {
                'е' -> if (wordStart || previous in CYRILLIC_VOWELS || previous == 'ъ' || previous == 'ь') "ye" else "e"
                'ц' -> if (!wordStart && previous in CYRILLIC_VOWELS) "ts" else "s"
                else -> CYRILLIC_TO_LATIN[lower]
            }
        when {
            latin == null -> out.append(char)
            char.isUpperCase() -> out.append(latin.replaceFirstChar { it.uppercaseChar() })
            else -> out.append(latin)
        }
    }
    return out.toString()
}

/** Units after a number, read as words before ranges are spelled out ("9-13 mm" -> "9 dan 13 gacha millimetr"). */
private val UNIT_AFTER_NUMBER = Regex("(?<=\\d)\\s?(sm/s|m/s|mm|sm|cm|ml|%)(?![\\p{L}\\d])")
private val UNIT_WORDS =
    mapOf(
        "sm/s" to "santimetr sekundiga",
        "m/s" to "metr sekundiga",
        "mm" to "millimetr",
        "sm" to "santimetr",
        "cm" to "santimetr",
        "ml" to "millilitr",
        "%" to "foiz",
    )

private val NUMBER_RANGE = Regex("(\\d+)\\s*[-–]\\s*(\\d+)")
private val DECIMAL_COMMA = Regex("(\\d+),(\\d+)")
private val BLANKS = Regex("_{2,}|\\.{3,}|…")
private val READ_AS_IS = Regex("[«»\"“”*()]")
private val SPACES = Regex("\\s+")

/** Spoken spellings the voice cannot guess from the letters. */
private val SPOKEN_WORDS =
    listOf(
        "МГц" to "megagers",
        "Мгц" to "megagers",
        "УТТ" to "ultratovush tekshiruvi",
        "БИВ" to "bachadon ichi vositasi",
        "PSV" to "pi es vi",
    )

/**
 * The Latin text the voice reads for a bank line: Cyrillic letters become Latin, "9-13" becomes
 * "9 dan 13 gacha", "1,5" becomes "1 butun 5", units after numbers become words and blanks become a
 * pause.
 */
internal fun uziTestSpeechText(text: String): String {
    var spoken = text
    SPOKEN_WORDS.forEach { (written, said) -> spoken = spoken.replace(written, said) }
    spoken = cyrillicToUzbekLatin(spoken)
    spoken = spoken.replace(UNIT_AFTER_NUMBER) { " " + UNIT_WORDS.getValue(it.groupValues[1]) }
    spoken = spoken.replace(NUMBER_RANGE, "$1 dan $2 gacha")
    spoken = spoken.replace(DECIMAL_COMMA, "$1 butun $2")
    spoken = spoken.replace(BLANKS, ", ").replace(READ_AS_IS, " ")
    return spoken.replace(SPACES, " ").trim()
}

private val NOT_SEARCHABLE = Regex("[^a-z0-9 ]")

/**
 * One search key for both scripts: Latin, lower case, without the oʻ/gʻ marks, with x and h, q and k
 * treated alike (people type them interchangeably on a phone keyboard).
 */
internal fun uziSearchKey(text: String): String =
    cyrillicToUzbekLatin(text)
        .lowercase()
        .replace("ʻ", "")
        .replace("'", "")
        .replace('x', 'h')
        .replace('q', 'k')
        .replace("ye", "e")
        .replace(NOT_SEARCHABLE, " ")
        .replace(SPACES, " ")

/** The query's words, each folded like [uziSearchKey]; empty for a blank query. */
internal fun uziSearchTerms(query: String): List<String> = uziSearchKey(query).split(' ').filter { it.isNotBlank() }

/** True when every term occurs in [key] (an already folded question with its options). */
internal fun uziMatches(
    key: String,
    terms: List<String>,
): Boolean = terms.all { key.contains(it) }
