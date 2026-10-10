package com.kienhoang.dualsubreplay.dubbing

/*
 * Uzbek text as the speech voice wants it. Three layers are kept apart:
 *  A. display text: the subtitle on screen, never changed here;
 *  B. TTS input: the copy sent to the voice ([PreparedSpeech.tts]);
 *  C. pronunciation rules: each one named, and listed in [PreparedSpeech.applied] with the text
 *     before and after it, so every change can be checked.
 *
 * Rules marked confirmed fix a reported voice error: the official Latin letters Oʻ and Gʻ are
 * written with U+02BB (modifier letter turned comma), while machine translation and most keyboards
 * give a plain apostrophe or a curly quote, and the voice then reads "o'zbek" as "ozbek". The
 * other rules are experiments, off unless switched on in the settings, until a doctor confirms by
 * ear that the voice needs them.
 */

/** Every character that stands in for the apostrophe of oʻ and gʻ. */
private const val APOSTROPHE_LOOKALIKES = "'\u2018\u2019\u02BC\u02B9\u2032\u0060\u00B4\uFF07"

private val OKINA_AFTER_O_OR_G = Regex("([oOgG])[$APOSTROPHE_LOOKALIKES]")
private val CURLY_BETWEEN_LETTERS = Regex("(?<=\\p{L})[\u2018\u2019\u02BC\u02B9\u2032\u0060\u00B4\uFF07](?=\\p{L})")

/** U+02BB, the turned comma that completes Oʻ and Gʻ. */
private const val OKINA = "\u02BB"

internal data class SpeechRule(
    val id: String,
    val description: String,
    /** True when a real voice error was reported for this pattern; false for an experiment. */
    val confirmed: Boolean,
)

internal data class AppliedSpeechRule(
    val rule: SpeechRule,
    val before: String,
    val after: String,
)

/** The subtitle as shown, the text the voice reads, and which rules turned one into the other. */
internal data class PreparedSpeech(
    val display: String,
    val tts: String,
    val applied: List<AppliedSpeechRule>,
)

internal data class SpeechOptions(
    /** "12 mm" -> "12 millimetr", "kPa" -> "kilopaskal" (experiment). */
    val readUnits: Boolean = false,
    /** "RI" -> "rezistivlik indeksi" from the UZI glossary (experiment). */
    val expandAcronyms: Boolean = false,
)

internal val RULE_OKINA =
    SpeechRule("okina", "o\u2018 va g\u2018 apostrofi U+02BB (\u02BB) ga o\u2018giriladi", confirmed = true)
internal val RULE_GLOTTAL_STOP =
    SpeechRule("tutuq", "so\u2018z ichidagi egri tutuq belgisi oddiy apostrofga o\u2018giriladi", confirmed = true)
internal val RULE_UNITS = SpeechRule("birliklar", "raqamdan keyingi birlik so\u2018z bilan yoziladi (sinov)", confirmed = false)
internal val RULE_ACRONYMS =
    SpeechRule("qisqartmalar", "UZI qisqartmasi lug\u2018atdagi to\u2018liq nomga almashadi (sinov)", confirmed = false)

/** Longest first, so "sm/s" is read before "sm". Only after a number, so words are never touched. */
private val UNIT_WORDS =
    listOf(
        "sm/s" to "santimetr sekundiga",
        "cm/s" to "santimetr sekundiga",
        "m/s" to "metr sekundiga",
        "kPa" to "kilopaskal",
        "MHz" to "megagers",
        "Hz" to "gers",
        "mm" to "millimetr",
        "sm" to "santimetr",
        "cm" to "santimetr",
        "ml" to "millilitr",
        "%" to "foiz",
    )
private val UNIT_AFTER_NUMBER =
    Regex(
        "(?<=\\d)\\s?(" + UNIT_WORDS.joinToString("|") { Regex.escape(it.first) } + ")(?![\\p{L}\\d])",
    )

/** The voice's copy of [display]; the subtitle itself is never changed. */
internal fun prepareUzbekSpeech(
    display: String,
    options: SpeechOptions = SpeechOptions(),
    acronyms: Map<String, String> = emptyMap(),
): PreparedSpeech {
    val applied = mutableListOf<AppliedSpeechRule>()
    var text = display

    fun apply(
        rule: SpeechRule,
        change: (String) -> String,
    ) {
        val next = change(text)
        if (next != text) applied += AppliedSpeechRule(rule, text, next)
        text = next
    }
    // Word-adding experiments first, so the words they add get the apostrophe rules too.
    if (options.readUnits) {
        apply(RULE_UNITS) { current ->
            current.replace(UNIT_AFTER_NUMBER) { match ->
                " " + UNIT_WORDS.first { it.first == match.groupValues[1] }.second
            }
        }
    }
    if (options.expandAcronyms && acronyms.isNotEmpty()) apply(RULE_ACRONYMS) { expandAcronyms(it, acronyms) }
    // o' g' (any apostrophe look-alike, either case) become oʻ gʻ
    apply(RULE_OKINA) { it.replace(OKINA_AFTER_O_OR_G) { match -> match.groupValues[1] + OKINA } }
    // the remaining apostrophes (ma'no, sa'nat) are the glottal stop; curly ones become plain
    apply(RULE_GLOTTAL_STOP) { it.replace(CURLY_BETWEEN_LETTERS, "'") }
    return PreparedSpeech(display, text, applied)
}

/** Kept for existing callers: only the confirmed apostrophe rules. */
internal fun uzbekSpeechText(text: String): String = prepareUzbekSpeech(text).tts

/**
 * "rezistivlik indeksi (RI)" -> "rezistivlik indeksi" (the name is already said) and a lone
 * "RI" -> "rezistivlik indeksi". Only whole upper-case words listed in [acronyms] change.
 */
internal fun expandAcronyms(
    text: String,
    acronyms: Map<String, String>,
): String {
    val names = acronyms.keys.sortedByDescending(String::length).joinToString("|") { Regex.escape(it) }
    if (names.isEmpty()) return text
    val inParentheses = Regex("\\s*\\((?:$names)\\)")
    val alone = Regex("(?<![\\p{L}\\d])($names)(?![\\p{L}\\d])")
    return text
        .replace(inParentheses, "")
        .replace(alone) { match -> acronyms.getValue(match.groupValues[1]) }
}

/** A short trace for logs and the voice test: which rules ran. */
internal fun PreparedSpeech.ruleSummary(): String =
    if (applied.isEmpty()) "o\u2018zgarishsiz" else applied.joinToString(", ") { it.rule.id }
