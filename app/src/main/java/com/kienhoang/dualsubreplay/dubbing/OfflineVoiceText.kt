package com.kienhoang.dualsubreplay.dubbing

/*
 * Text preparation for the offline Uzbek voice. The MMS Uzbek model reads Cyrillic letters only, so
 * the Latin subtitle is turned into spoken Cyrillic: numbers become words, then the letters are
 * transliterated, then each letter becomes the model's id with blanks between them (as VitsTokenizer
 * does with add_blank). Pure functions, so they are unit-tested against ids the exporter printed.
 */

private val ONES = listOf("", "bir", "ikki", "uch", "to‘rt", "besh", "olti", "yetti", "sakkiz", "to‘qqiz")
private val TENS = listOf("", "o‘n", "yigirma", "o‘ttiz", "qirq", "ellik", "oltmish", "yetmish", "sakson", "to‘qson")

private const val TEN = 10
private const val HUNDRED = 100
private const val THOUSAND = 1_000
private const val MILLION = 1_000_000
private const val MAX_SPOKEN_NUMBER = 999_999_999L

/** 0 to 999,999,999 in Uzbek words (Latin): 125 -> "bir yuz yigirma besh". */
internal fun uzbekNumberWords(value: Long): String {
    if (value == 0L) return "nol"
    if (value < 0 || value > MAX_SPOKEN_NUMBER) return value.toString()
    val parts = mutableListOf<String>()
    var rest = value
    if (rest >= MILLION) {
        parts += "${belowThousand((rest / MILLION).toInt())} million"
        rest %= MILLION
    }
    if (rest >= THOUSAND) {
        parts += "${belowThousand((rest / THOUSAND).toInt())} ming"
        rest %= THOUSAND
    }
    if (rest > 0) parts += belowThousand(rest.toInt())
    return parts.joinToString(" ")
}

private fun belowThousand(value: Int): String {
    val words = mutableListOf<String>()
    if (value >= HUNDRED) words += "${ONES[value / HUNDRED]} yuz"
    val tens = value % HUNDRED / TEN
    val ones = value % TEN
    if (tens > 0) words += TENS[tens]
    if (ones > 0) words += ONES[ones]
    return words.joinToString(" ")
}

private val NUMBER = Regex("(\\d+)(?:[.,](\\d+))?")

/** Every number in [text] as words; "2,5" -> "ikki butun besh". */
internal fun speakNumbers(text: String): String =
    NUMBER.replace(text) { match ->
        val whole = match.groupValues[1].toLongOrNull() ?: return@replace match.value
        val fraction = match.groupValues[2]
        val spokenWhole = uzbekNumberWords(whole)
        if (fraction.isEmpty()) spokenWhole else "$spokenWhole butun ${uzbekNumberWords(fraction.toLongOrNull() ?: 0)}"
    }

private val APOSTROPHES = setOf('\'', '‘', '’', 'ʻ', 'ʼ', '`')
private const val VOWELS = "aeiouAEIOU"

/** Uzbek Latin to Uzbek Cyrillic, following the official letter correspondences. */
internal fun latinToUzbekCyrillic(text: String): String {
    val out = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val char = text[index]
        val next = text.getOrNull(index + 1)
        val lower = char.lowercaseChar()
        val upper = char.isUpperCase()
        val wordStart = index == 0 || !text[index - 1].isLetter() && text[index - 1] !in APOSTROPHES

        fun emit(
            cyrillic: String,
            consumed: Int,
        ) {
            out.append(if (upper) cyrillic.replaceFirstChar { it.uppercaseChar() } else cyrillic)
            index += consumed
        }
        when {
            (lower == 'o' || lower == 'g') && next != null && next in APOSTROPHES -> emit(if (lower == 'o') "ў" else "ғ", 2)
            lower == 's' && next?.lowercaseChar() == 'h' -> emit("ш", 2)
            lower == 'c' && next?.lowercaseChar() == 'h' -> emit("ч", 2)
            lower == 'y' && next?.lowercaseChar() == 'o' && text.getOrNull(index + 2)?.let { it in APOSTROPHES } != true -> emit("ё", 2)
            lower == 'y' && next?.lowercaseChar() == 'u' -> emit("ю", 2)
            lower == 'y' && next?.lowercaseChar() == 'a' -> emit("я", 2)
            lower == 'y' && next?.lowercaseChar() == 'e' -> emit("е", 2)
            lower == 'e' -> emit(if (wordStart || text.getOrNull(index - 1)?.let { it in VOWELS } == true) "э" else "е", 1)
            char in APOSTROPHES -> emit("ъ", 1)
            else -> emit(LATIN_TO_CYRILLIC[lower]?.toString() ?: char.toString(), 1)
        }
    }
    return out.toString()
}

private val LATIN_TO_CYRILLIC =
    mapOf(
        'a' to 'а',
        'b' to 'б',
        'd' to 'д',
        'f' to 'ф',
        'g' to 'г',
        'h' to 'ҳ',
        'i' to 'и',
        'j' to 'ж',
        'k' to 'к',
        'l' to 'л',
        'm' to 'м',
        'n' to 'н',
        'o' to 'о',
        'p' to 'п',
        'q' to 'қ',
        'r' to 'р',
        's' to 'с',
        't' to 'т',
        'u' to 'у',
        'v' to 'в',
        'x' to 'х',
        'y' to 'й',
        'z' to 'з',
        'c' to 'с',
        'w' to 'в',
    )

/**
 * The model's input ids: lower case, characters the vocabulary does not know are dropped, and with
 * [addBlank] the blank id goes before, between and after them, exactly as VitsTokenizer builds them.
 */
internal fun mmsTokenIds(
    text: String,
    vocab: Map<String, Int>,
    addBlank: Boolean,
    blankId: Int,
): LongArray {
    val ids = text.lowercase().mapNotNull { vocab[it.toString()] }
    if (!addBlank) return LongArray(ids.size) { ids[it].toLong() }
    val out = LongArray(ids.size * 2 + 1) { blankId.toLong() }
    ids.forEachIndexed { position, id -> out[position * 2 + 1] = id.toLong() }
    return out
}

/** The whole path from a Latin subtitle sentence to model ids. */
internal fun offlineVoiceIds(
    latin: String,
    vocab: Map<String, Int>,
    addBlank: Boolean,
    blankId: Int,
): LongArray =
    mmsTokenIds(
        latin
            .replace(Regex("\\s+"), " ")
            .trim()
            .let(::speakNumbers)
            .let(::latinToUzbekCyrillic),
        vocab,
        addBlank,
        blankId,
    )

/** 16-bit mono PCM WAV bytes for [samples] in -1..1. */
internal fun wavBytes(
    samples: FloatArray,
    sampleRate: Int,
): ByteArray {
    val dataSize = samples.size * 2
    val buffer =
        java.nio.ByteBuffer
            .allocate(WAV_HEADER_BYTES + dataSize)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray()).putInt(WAV_HEADER_BYTES - 8 + dataSize).put("WAVE".toByteArray())
    buffer
        .put("fmt ".toByteArray())
        .putInt(FMT_CHUNK_BYTES)
        .putShort(1)
        .putShort(1)
    buffer
        .putInt(sampleRate)
        .putInt(sampleRate * 2)
        .putShort(2)
        .putShort(BITS_PER_SAMPLE)
    buffer.put("data".toByteArray()).putInt(dataSize)
    samples.forEach { buffer.putShort((it.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()) }
    return buffer.array()
}

private const val WAV_HEADER_BYTES = 44
private const val FMT_CHUNK_BYTES = 16
private const val BITS_PER_SAMPLE: Short = 16
