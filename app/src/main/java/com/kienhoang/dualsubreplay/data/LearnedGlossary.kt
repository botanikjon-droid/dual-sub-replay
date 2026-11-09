package com.kienhoang.dualsubreplay.data

/*
 * The glossary that grows while the doctor watches lessons. Claude suggests terms it met that the
 * approved UZI glossary does not cover; they wait as "pending" until the doctor approves, edits or
 * deletes them. The doctor can also add a term by hand (approved at once). Every Claude request
 * carries the approved terms as rules and the pending ones as earlier suggestions, so one lecture
 * and the next use the same Uzbek words. Stored as TSV in filesDir; pure functions here.
 */

internal enum class LearnedTermStatus(
    val code: String,
) {
    PENDING("pending"),
    APPROVED("approved"),
    ;

    companion object {
        fun of(code: String): LearnedTermStatus? = entries.firstOrNull { it.code == code.trim() }
    }
}

internal data class LearnedTerm(
    val english: String,
    val uzbek: String,
    val note: String = "",
    val status: LearnedTermStatus = LearnedTermStatus.PENDING,
    /** "claude" or "doctor". */
    val source: String = LEARNED_SOURCE_CLAUDE,
    /** How many Claude runs suggested it; a term met in many lectures is worth reviewing first. */
    val seen: Int = 1,
    /** The video it was first met in, so the doctor can recall the context. */
    val videoId: String = "",
) {
    val key: String get() = learnedTermKey(english)
}

internal const val LEARNED_SOURCE_CLAUDE = "claude"
internal const val LEARNED_SOURCE_DOCTOR = "doctor"
internal const val LEARNED_GLOSSARY_FILE = "learned-glossary.tsv"
private const val LEARNED_HEADER = "english\tuzbek\tnote\tstatus\tsource\tseen\tvideo"

/** Terms differ by case, spaces and hyphens only in writing, not in meaning. */
internal fun learnedTermKey(english: String): String =
    english
        .lowercase()
        .replace('-', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

private fun clean(value: String): String =
    value
        .replace('\t', ' ')
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()

internal fun formatLearnedGlossary(terms: List<LearnedTerm>): String =
    buildString {
        append(LEARNED_HEADER).append('\n')
        terms.forEach { term ->
            append(clean(term.english)).append('\t')
            append(clean(term.uzbek)).append('\t')
            append(clean(term.note)).append('\t')
            append(term.status.code).append('\t')
            append(clean(term.source)).append('\t')
            append(term.seen).append('\t')
            append(clean(term.videoId)).append('\n')
        }
    }

/** Unreadable lines are skipped; the file is the app's own, so nothing else is expected there. */
internal fun parseLearnedGlossary(text: String): List<LearnedTerm> =
    text
        .lineSequence()
        .map { it.trimEnd('\r') }
        .filter { it.isNotBlank() && it != LEARNED_HEADER }
        .mapNotNull { line ->
            val cells = line.split('\t')
            if (cells.size < 4) return@mapNotNull null
            val english = cells[0].trim()
            val uzbek = cells[1].trim()
            val status = LearnedTermStatus.of(cells[3]) ?: return@mapNotNull null
            if (english.isEmpty() || uzbek.isEmpty()) return@mapNotNull null
            LearnedTerm(
                english = english,
                uzbek = uzbek,
                note = cells[2].trim(),
                status = status,
                source = cells.getOrNull(4)?.trim()?.ifEmpty { null } ?: LEARNED_SOURCE_CLAUDE,
                seen =
                    cells
                        .getOrNull(5)
                        ?.trim()
                        ?.toIntOrNull()
                        ?.coerceAtLeast(1) ?: 1,
                videoId = cells.getOrNull(6)?.trim().orEmpty(),
            )
        }.distinctBy { it.key }
        .toList()

/**
 * Adds Claude's [suggestions] to [existing]. A term the approved glossary already covers
 * ([isKnown]) is dropped. A term already learned keeps the doctor's wording and status and only
 * counts one more sighting. New terms are pending.
 */
internal fun mergeLearnedSuggestions(
    existing: List<LearnedTerm>,
    suggestions: List<LearnedTerm>,
    videoId: String,
    isKnown: (String) -> Boolean = { false },
): List<LearnedTerm> {
    val byKey = LinkedHashMap<String, LearnedTerm>()
    existing.forEach { byKey[it.key] = it }
    val counted = mutableSetOf<String>()
    suggestions.forEach { suggestion ->
        val english = clean(suggestion.english)
        val uzbek = clean(suggestion.uzbek)
        val key = learnedTermKey(english)
        if (key.isEmpty() || uzbek.isEmpty() || isKnown(english) || !counted.add(key)) return@forEach
        val old = byKey[key]
        byKey[key] =
            old?.copy(seen = old.seen + 1)
                ?: LearnedTerm(
                    english = english,
                    uzbek = uzbek,
                    note = clean(suggestion.note),
                    status = LearnedTermStatus.PENDING,
                    source = LEARNED_SOURCE_CLAUDE,
                    seen = 1,
                    videoId = videoId,
                )
    }
    return byKey.values.toList()
}

/** Adds or replaces a term the doctor wrote or confirmed; it is approved. */
internal fun upsertDoctorTerm(
    existing: List<LearnedTerm>,
    english: String,
    uzbek: String,
    note: String = "",
): List<LearnedTerm> {
    val key = learnedTermKey(english)
    require(key.isNotEmpty() && uzbek.isNotBlank()) { "Inglizcha va o‘zbekcha atamani kiriting." }
    val old = existing.firstOrNull { it.key == key }
    val term =
        LearnedTerm(
            english = clean(english),
            uzbek = clean(uzbek),
            note = clean(note).ifEmpty { old?.note.orEmpty() },
            status = LearnedTermStatus.APPROVED,
            source = LEARNED_SOURCE_DOCTOR,
            seen = old?.seen ?: 1,
            videoId = old?.videoId.orEmpty(),
        )
    return if (old == null) existing + term else existing.map { if (it.key == key) term else it }
}

internal fun approveLearnedTerm(
    existing: List<LearnedTerm>,
    english: String,
): List<LearnedTerm> {
    val key = learnedTermKey(english)
    return existing.map { if (it.key == key) it.copy(status = LearnedTermStatus.APPROVED) else it }
}

internal fun removeLearnedTerm(
    existing: List<LearnedTerm>,
    english: String,
): List<LearnedTerm> {
    val key = learnedTermKey(english)
    return existing.filterNot { it.key == key }
}

/** Pending terms first (most seen first), then approved ones alphabetically: what to review is on top. */
internal fun learnedTermsForReview(terms: List<LearnedTerm>): List<LearnedTerm> =
    terms.sortedWith(
        compareBy<LearnedTerm> { it.status != LearnedTermStatus.PENDING }
            .thenByDescending { if (it.status == LearnedTermStatus.PENDING) it.seen else 0 }
            .thenBy { it.key },
    )

/** The learned terms whose English form occurs in [texts] (whole words, any case, hyphen = space). */
internal fun learnedTermsIn(
    terms: List<LearnedTerm>,
    texts: List<String>,
): List<LearnedTerm> {
    if (terms.isEmpty() || texts.isEmpty()) return emptyList()
    val haystack = " " + texts.joinToString(" ") { learnedTermKey(it).replace(Regex("[^\\p{L}\\p{N} ]"), " ") } + " "
    val normalized = haystack.replace(Regex("\\s+"), " ")
    return terms.filter { term ->
        val needle =
            term.key
                .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        needle.isNotEmpty() &&
            (normalized.contains(" $needle ") || normalized.contains(" ${needle}s ") || normalized.contains(" ${needle}es "))
    }
}
