package com.kienhoang.dualsubreplay.dubbing

/*
 * What the voice reads of a subtitle line. Music marks and sound labels ("[Musiqa]", "♪", "[Qarsaklar]")
 * are for the eyes only: read aloud they cover the lecture's own background music and say nothing.
 */

private val BRACKETED = Regex("""\[[^\]]*]|\([^)]*\b(musiqa|music|qarsak|applause|kulgi|laughter)\b[^)]*\)""", RegexOption.IGNORE_CASE)
private val MUSIC_SIGNS = Regex("[♪♫♬♩🎵🎶]")
private val ONLY_SOUND_WORD = Regex("""^(musiqa|music|qarsaklar?|applause|kulgi|laughter|foreign)$""", RegexOption.IGNORE_CASE)
private val SPACES = Regex("""\s+""")
private val HAS_LETTER_OR_DIGIT = Regex("""[\p{L}\p{N}]""")

/** [text] without music marks and sound labels; null when nothing is left to say. */
internal fun speakableText(text: String): String? {
    val cleaned =
        text
            .replace(BRACKETED, " ")
            .replace(MUSIC_SIGNS, " ")
            .replace(SPACES, " ")
            .trim()
    if (!HAS_LETTER_OR_DIGIT.containsMatchIn(cleaned)) return null
    if (ONLY_SOUND_WORD.matches(cleaned.trim('.', ',', '!', '?', '…', ' '))) return null
    return cleaned
}
