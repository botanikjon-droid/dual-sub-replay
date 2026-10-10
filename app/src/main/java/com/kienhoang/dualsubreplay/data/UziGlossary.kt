package com.kienhoang.dualsubreplay.data

/*
 * The doctor-reviewed ultrasound (UZI) glossary: English term -> approved Uzbek term and note.
 * The asset app/src/main/assets/uzi_glossary.tsv is generated from
 * docs/glossary/UZI_atamalari_tekshirilgan.xlsx by tools/glossary/convert_uzi_glossary.py.
 *
 * The glossary never rewrites a subtitle. It finds terms in the English caption, so the app can
 * show the approved Uzbek term and note beside the translation, and it checks whether the machine
 * translation used the approved term (a hint for the reader, not an automatic replacement).
 */

internal enum class GlossaryStatus(
    val code: String,
) {
    APPROVED("approved"),
    CORRECTED("corrected"),
    NEW("new"),
    ;

    companion object {
        fun of(code: String): GlossaryStatus? = entries.firstOrNull { it.code == code.trim() }
    }
}

internal data class GlossaryEntry(
    val id: Int,
    val category: String,
    val english: String,
    val aliases: List<String>,
    val uzbek: String,
    val note: String,
    val status: GlossaryStatus,
    /** A common word ("liver", "gain"): explained only when tapped, never listed under every caption. */
    val general: Boolean,
) {
    val forms: List<String> get() = listOf(english) + aliases
}

internal data class GlossaryParseResult(
    val entries: List<GlossaryEntry>,
    val errors: List<String>,
)

internal const val UZI_GLOSSARY_ASSET = "uzi_glossary.tsv"
private val GLOSSARY_HEADER = listOf("id", "category", "english", "aliases", "uzbek", "note", "status", "general")

/** Reads the TSV asset. Bad lines are reported and skipped; they never blank an existing entry. */
internal fun parseUziGlossary(tsv: String): GlossaryParseResult {
    val entries = mutableListOf<GlossaryEntry>()
    val errors = mutableListOf<String>()
    var headerSeen = false
    tsv.lineSequence().forEachIndexed { index, raw ->
        val line = raw.trimEnd('\r')
        if (line.isBlank() || line.startsWith("#")) return@forEachIndexed
        val cells = line.split('\t')
        if (!headerSeen) {
            headerSeen = true
            if (cells != GLOSSARY_HEADER) errors += "line ${index + 1}: unexpected header"
            return@forEachIndexed
        }
        val entry = glossaryEntryOf(cells)
        if (entry == null) errors += "line ${index + 1}: invalid entry" else entries += entry
    }
    if (!headerSeen) errors += "missing header"
    return GlossaryParseResult(entries, errors)
}

private fun glossaryEntryOf(cells: List<String>): GlossaryEntry? {
    if (cells.size != GLOSSARY_HEADER.size) return null
    val id = cells[0].trim().toIntOrNull() ?: return null
    val status = GlossaryStatus.of(cells[6]) ?: return null
    val english = cells[2].trim()
    val uzbek = cells[4].trim()
    if (english.isEmpty() || uzbek.isEmpty()) return null
    return GlossaryEntry(
        id = id,
        category = cells[1].trim(),
        english = english,
        aliases = cells[3].split(';').map(String::trim).filter(String::isNotEmpty),
        uzbek = uzbek,
        note = cells[5].trim(),
        status = status,
        general = cells[7].trim() == "1",
    )
}

/** One glossary term found in a caption, as the [start] until [end] characters of that caption. */
internal data class GlossaryMatch(
    val entry: GlossaryEntry,
    val start: Int,
    val end: Int,
    val text: String,
)

/** A word of English text with its place in that text. */
private data class EnglishWord(
    val text: String,
    val start: Int,
    val end: Int,
)

// Letters and digits; hyphens, slashes and spaces separate words, so "B-mode" = "B mode".
private val ENGLISH_WORD = Regex("[A-Za-z0-9]+(?:['’][A-Za-z]+)?")

private fun englishWords(text: String): List<EnglishWord> =
    ENGLISH_WORD.findAll(text).map { EnglishWord(it.value, it.range.first, it.range.last + 1) }.toList()

/** A form's words; an acronym (no lowercase letter, such as RI, TGC or TI-RADS) must match its case. */
private class GlossaryForm(
    val entry: GlossaryEntry,
    val words: List<String>,
    val acronym: Boolean,
    val order: Int,
)

internal class UziGlossary(
    val entries: List<GlossaryEntry>,
) {
    private val formsByFirstWord: Map<String, List<GlossaryForm>>
    private val byKey: Map<String, GlossaryEntry>

    init {
        val forms = mutableListOf<GlossaryForm>()
        val keys = linkedMapOf<String, GlossaryEntry>()
        entries.forEachIndexed { order, entry ->
            entry.forms.forEach { form ->
                val words = englishWords(form).map { it.text }
                if (words.isEmpty()) return@forEach
                val acronym = form.none(Char::isLowerCase) && form.any(Char::isLetter)
                forms += GlossaryForm(entry, if (acronym) words else words.map(String::lowercase), acronym, order)
                // The first entry keeps a form two entries share, as the converter reports.
                keys.putIfAbsent(words.joinToString(" ") { it.lowercase() }, entry)
            }
        }
        formsByFirstWord = forms.groupBy { it.words.first().lowercase() }
        byKey = keys
    }

    /**
     * Terms in [text], longest first and never overlapping, so "portal vein" is not also read as
     * "vein" and "hepatic vein" never as "portal vein". Common words are left out unless asked for.
     */
    fun findTerms(
        text: String,
        includeGeneral: Boolean = false,
    ): List<GlossaryMatch> {
        val words = englishWords(text)
        val matches = mutableListOf<GlossaryMatch>()
        var index = 0
        while (index < words.size) {
            val best =
                formsByFirstWord[stripPossessive(words[index].text).lowercase()]
                    .orEmpty()
                    .filter { includeGeneral || !it.entry.general }
                    .filter { matchesAt(it, words, index) }
                    .maxWithOrNull(compareBy<GlossaryForm> { it.words.size }.thenByDescending { it.order })
            if (best == null) {
                index++
                continue
            }
            val last = words[index + best.words.size - 1]
            val start = words[index].start
            matches += GlossaryMatch(best.entry, start, last.end, text.substring(start, last.end))
            index += best.words.size
        }
        return matches
    }

    /** The term covering the characters [start] until [end] of [text] (a tapped word), common words included. */
    fun termAt(
        text: String,
        start: Int,
        end: Int,
    ): GlossaryMatch? = findTerms(text, includeGeneral = true).firstOrNull { it.start <= start && end <= it.end }

    /** The entry for a whole phrase ("Portal veins", "RI"), or null. */
    fun lookup(phrase: String): GlossaryEntry? =
        findTerms(phrase, includeGeneral = true)
            .singleOrNull()
            ?.takeIf { it.start == englishWords(phrase).firstOrNull()?.start && it.end == englishWords(phrase).lastOrNull()?.end }
            ?.entry

    /** The entry whose English form is exactly [form] (case and hyphens ignored). */
    fun entryForForm(form: String): GlossaryEntry? = byKey[englishWords(form).joinToString(" ") { it.text.lowercase() }]

    private fun matchesAt(
        form: GlossaryForm,
        words: List<EnglishWord>,
        index: Int,
    ): Boolean {
        if (index + form.words.size > words.size) return false
        return form.words.indices.all { offset ->
            val word = stripPossessive(words[index + offset].text)
            val wanted = form.words[offset]
            val isLast = offset == form.words.lastIndex
            if (form.acronym) {
                word == wanted || (isLast && word == wanted + "s")
            } else {
                englishWordMatches(word.lowercase(), wanted, isLast)
            }
        }
    }
}

private fun stripPossessive(word: String): String = word.removeSuffix("'s").removeSuffix("’s")

/** Exact, or (for a term's last word) a regular plural: vein/veins, focus/focuses, artery/arteries. */
private fun englishWordMatches(
    word: String,
    wanted: String,
    isLast: Boolean,
): Boolean {
    if (word == wanted) return true
    if (!isLast || wanted.length < 3) return false
    return word == wanted + "s" ||
        word == wanted + "es" ||
        (wanted.endsWith("y") && word == wanted.dropLast(1) + "ies")
}

// ---------- Uzbek side: did the machine translation use the approved term? ----------

private const val UZBEK_APOSTROPHES = "'‘’ʻʼʹ′`´＇"
private val UZBEK_WORD = Regex("[\\p{L}\\p{N}$UZBEK_APOSTROPHES]+")
private val PARENTHESES = Regex("\\(([^)]*)\\)")

/** Lower case, every apostrophe look-alike as ', so "Oʻt", "O‘t" and "o't" compare equal. */
internal fun uzbekMatchKey(text: String): String {
    val builder = StringBuilder(text.length)
    text.lowercase().forEach { builder.append(if (it in UZBEK_APOSTROPHES) '\'' else it) }
    return builder.toString()
}

private fun uzbekWords(text: String): List<String> =
    UZBEK_WORD
        .findAll(uzbekMatchKey(text))
        .map { it.value.trim('\'') }
        .filter(String::isNotEmpty)
        .toList()

/**
 * The ways an approved term may appear: "umumiy o‘t yo‘li (xoledox)" is "umumiy o‘t yo‘li" or
 * "xoledox"; "polikistik tuxumdonlar / PCOS" is either side of the slash.
 */
internal fun uzbekTermVariants(uzbek: String): List<List<String>> {
    // Parentheses first: a slash may sit inside them ("platsenta previa (… joylashuvi/ichki …)").
    val parts = listOf(PARENTHESES.replace(uzbek, " ")) + PARENTHESES.findAll(uzbek).map { it.groupValues[1] }
    return parts
        .flatMap { it.split('/', ';') }
        .map(::uzbekWords)
        .filter(List<String>::isNotEmpty)
        .distinct()
}

/** "venasi" also matches "venasining", "venasida" and "venalari"; short words must match from the start. */
private fun uzbekStem(word: String): String =
    when {
        word.length >= 6 -> word.dropLast(2)
        word.length >= 4 -> word.dropLast(1)
        else -> word
    }

/**
 * Whether [translation] contains the approved Uzbek term of [entry] (any variant, with Uzbek case
 * and possessive endings). A false result is shown as a hint; the translation itself is untouched.
 */
internal fun translationUsesApprovedTerm(
    entry: GlossaryEntry,
    translation: String,
): Boolean {
    val words = uzbekWords(translation)
    return uzbekTermVariants(entry.uzbek).any { variant ->
        val stems = variant.map(::uzbekStem)
        (0..words.size - stems.size).any { start -> stems.indices.all { words[start + it].startsWith(stems[it]) } }
    }
}

/** A glossary term of a caption, and whether its translation used the approved Uzbek term. */
internal data class GlossaryTermCheck(
    val match: GlossaryMatch,
    /** Null while the caption has no translation yet. */
    val approvedTermUsed: Boolean?,
)

internal fun checkGlossaryTerms(
    glossary: UziGlossary,
    original: String,
    translation: String?,
): List<GlossaryTermCheck> =
    glossary
        .findTerms(original)
        .distinctBy { it.entry.id }
        .map { match ->
            GlossaryTermCheck(match, translation?.takeIf(String::isNotBlank)?.let { translationUsesApprovedTerm(match.entry, it) })
        }

/** The glossary applies when English captions are translated into Uzbek. */
internal fun glossaryApplies(
    sourceLanguage: String?,
    targetLanguage: String?,
): Boolean {
    val target = targetLanguage?.lowercase().orEmpty()
    val source = sourceLanguage?.lowercase().orEmpty()
    return (target == "uz" || target.startsWith("uz-")) &&
        (source.isEmpty() || source == "auto" || source == "en" || source.startsWith("en-"))
}

/**
 * The glossary terms to show under one caption row. A long sentence is split over several rows,
 * so terms are found in the whole sentence (a term cut between two rows is still found) and each
 * is listed once, under the row where it starts. Only a row that holds its whole sentence is
 * checked against its translation; a row's slice of a longer translation may hold the term in
 * the next row, so such rows give no verdict.
 */
internal fun glossaryChecksForRow(
    glossary: UziGlossary,
    segment: SubtitleSegment,
): List<GlossaryTermCheck> {
    val sentence = segment.sentence
    if (sentence == null || sentence.cuts.isEmpty() || sentence.index !in 0..sentence.cuts.size) {
        return checkGlossaryTerms(glossary, segment.originalText, segment.translatedText)
    }
    val rowStart = if (sentence.index == 0) 0 else sentence.cuts[sentence.index - 1]
    val rowEnd = sentence.cuts.getOrElse(sentence.index) { sentence.text.length }
    return glossary
        .findTerms(sentence.text)
        .filter { it.start in rowStart until rowEnd }
        .distinctBy { it.entry.id }
        .map { GlossaryTermCheck(it, approvedTermUsed = null) }
}

/**
 * Upper-case abbreviations of the glossary (RI, TGC, CBD) with the Uzbek name to say instead, for
 * the voice's optional "read abbreviations in full" rule. An abbreviation whose Uzbek name still
 * holds it ("TAPSE (…)", "METAVIR shkalasi") is left out.
 */
internal fun glossaryAcronymExpansions(entries: List<GlossaryEntry>): Map<String, String> {
    val result = linkedMapOf<String, String>()
    entries.forEach { entry ->
        val spoken =
            PARENTHESES
                .replace(entry.uzbek, " ")
                .split('/')
                .first()
                .replace(Regex("\\s+"), " ")
                .trim()
        entry.forms
            .filter { form ->
                form.length in 2..8 && form.none(Char::isLowerCase) && form.none(Char::isWhitespace) &&
                    form.any(Char::isLetter)
            }.filterNot { spoken.isEmpty() || spoken.contains(it) }
            .forEach { result.putIfAbsent(it, spoken) }
    }
    return result
}
