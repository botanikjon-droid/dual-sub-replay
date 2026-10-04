package com.kienhoang.dualsubreplay.dubbing

/*
 * Uzbek text as the speech voice wants it. The official Latin letters Oʻ and Gʻ are written with
 * U+02BB (modifier letter turned comma). Machine translation (and most keyboards) give a plain
 * apostrophe or a curly quote instead, and the voice then reads "o'zbek" as "ozbek" and "to'g'ri"
 * as "togri". Only the copy sent to the voice is changed; the subtitles on screen are not.
 */

/** Every character that stands in for the apostrophe of oʻ and gʻ. */
private const val APOSTROPHE_LOOKALIKES = "'\u2018\u2019\u02BC\u02B9\u2032\u0060\u00B4\uFF07"

private val OKINA_AFTER_O_OR_G = Regex("([oOgG])[$APOSTROPHE_LOOKALIKES]")
private val CURLY_BETWEEN_LETTERS = Regex("(?<=\\p{L})[\u2018\u2019\u02BC\u02B9\u2032\u0060\u00B4\uFF07](?=\\p{L})")

/** U+02BB, the turned comma that completes Oʻ and Gʻ. */
private const val OKINA = "\u02BB"

internal fun uzbekSpeechText(text: String): String =
    text
        // o' g' (any apostrophe look-alike, either case) become oʻ gʻ
        .replace(OKINA_AFTER_O_OR_G) { match -> match.groupValues[1] + OKINA }
        // the remaining apostrophes (ma'no, sa'nat) are the glottal stop; curly ones become plain
        .replace(CURLY_BETWEEN_LETTERS, "'")
